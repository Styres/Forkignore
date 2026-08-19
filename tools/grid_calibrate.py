"""格线直测精标定 (阶段一 Python 原型, Kotlin refineByGridLines 的对偶实现)。

设计原则 (方案: 格线直测定位重构):
- 粗框(任意现有定位器输出)只负责给近似框, 精标定用实测几何定终值;
- 横向: 统一等差拟合 x_i = x0 + i*step, 无满宽/留边距 if-else 分支;
  梳状波长先验 (lambda ~ W/8) 经两遍拟合+孤立峰剔除实现, 防棋子轮廓假峰;
  与满宽先验偏差 <=2px 时吸附归零;
- 纵向: 上下框长矩形内边界为主锚, 双重门禁 (高~宽 <=4px 且两侧 std 骤降显著),
  不满足则平滑退化为内部横分割线剖面等差拟合;
- 输出 RefineResult(rect, confidence, residual); 残差 >2.5px 或线数不足时保持粗框并降级置信度。
"""
import numpy as np
import cv2

RELATIVE_RESIDUAL_GATE_RATIO = 0.05  # 5% 格宽拟合残差门禁 (全分辨率尺度自适应)
RELATIVE_SQUARE_GATE_RATIO = 0.015    # 1.5% 棋盘尺寸方约束门禁 (最低 4.0px)
RELATIVE_SNAP_RATIO = 0.005           # 0.5% 屏幕宽度满宽吸附门禁 (最低 2.0px)
MIN_LINES = 5                         # 参与拟合的最少内部分割线数 (共 7 条)
OUTLIER_FRAC = 0.25                   # 偏离等差网格超过 0.25*step 的峰判为棋子轮廓伪峰
WAVE_GATE = 0.015                     # 横向拟合波长与谐振波长最大相对偏差 (防假峰集锁错波长)
WAVE_GATE_V = 0.025                   # 纵向退化拟合波长门禁 (可见线少, 容差放宽)
BAR_STD_GATE = 16.0                   # 框边界均匀带判据: 条带侧 4 行的平均行 std 上限
BAR_GRAD_MIN = 3.0                    # 行均值剖面梯度最低幅值 (全宽强边)


def load_image(path):
    """中文/特殊字符路径安全读图 (cv2.imread 对中文路径返回 None)。"""
    data = np.fromfile(path, dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(f'无法读取图像: {path}')
    return img


def coarse_candidates(image, top_n=3):
    """放宽后的孞生粗定位器 (对照 Kotlin ChessLocator 阶段二改造形态)。

    与 fast_sat_locate_board 同构, 但解除两处把真值挡在搜索空间外的限制:
    1. max_size 由 0.98*s_w 放宽到 s_w (满宽棋盘不再被排除);
    2. 取消强制水平居中, x 在合法域内自由搜索 (微边距/满宽一视同仁)。
    返回按分数降序、互相充分去重的 top_n 个候选 [((x0, y0, size), score), ...]
    (原图像素坐标)。
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    scale = 400.0 / img_w
    s_w = 400
    s_h = int(img_h * scale)
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    mag = np.abs(cv2.Sobel(s_gray, cv2.CV_32F, 1, 0, ksize=3)) + \
          np.abs(cv2.Sobel(s_gray, cv2.CV_32F, 0, 1, ksize=3))
    sat_gray = cv2.integral(s_gray)
    sat_mag = cv2.integral(mag)
    rr, cc = np.indices((8, 8))
    pattern = np.where((rr + cc) % 2 == 0, 1.0, -1.0).astype(np.float32)

    def rect_sum(sat, x1, y1, x2, y2):
        x1, y1 = max(0, int(x1)), max(0, int(y1))
        x2, y2 = min(s_w, int(x2)), min(s_h, int(y2))
        if x2 <= x1 or y2 <= y1:
            return 0.0
        return sat[y2, x2] - sat[y1, x2] - sat[y2, x1] + sat[y1, x1]

    def rect_mean(sat, x1, y1, x2, y2):
        w = max(1, int(x2) - int(x1))
        h = max(1, int(y2) - int(y1))
        return rect_sum(sat, x1, y1, x2, y2) / (w * h)

    def score_box(x, y, size):
        step = size / 8.0
        edge = 0.0
        for i in range(1, 8):
            ly = int(y + i * step)
            lx = int(x + i * step)
            edge += rect_mean(sat_mag, x, ly - 1, x + size, ly + 2)
            edge += rect_mean(sat_mag, lx - 1, y, lx + 2, y + size)
        gm = np.zeros((8, 8), dtype=np.float32)
        cw = max(1, int(step * 0.18))
        for r in range(8):
            cy1 = y + r * step
            cy2 = cy1 + step
            for c in range(8):
                cx1 = x + c * step
                cx2 = cx1 + step
                gm[r, c] = 0.25 * (rect_mean(sat_gray, cx1, cy1, cx1 + cw, cy1 + cw) +
                                   rect_mean(sat_gray, cx2 - cw, cy1, cx2, cy1 + cw) +
                                   rect_mean(sat_gray, cx1, cy2 - cw, cx1 + cw, cy2) +
                                   rect_mean(sat_gray, cx2 - cw, cy2 - cw, cx2, cy2))
        corr = abs(np.sum((gm - gm.mean()) * pattern))
        bottom_ratio = (y + size) / s_h
        prior = 1.0 if 0.60 <= bottom_ratio <= 0.99 else 0.35
        return (corr * 2.0 + edge * 0.4) * prior

    min_size = int(0.60 * s_w)  # 覆盖横屏/裁剪图 (棋盘可仅占屏宽度 60%)
    max_size = s_w
    # 粗扫向量化: 原实现三重循环逐框调用 score_box (单帧 ~7 万次纯 Python
    # 调用, 占整个定位 99% 耗时); 积分表已建好, 每档 size 的边缘能量与
    # 8x8 角点均值都能用滑窗视图一次算完所有 (x, y), 语义与逐框版一致:
    # 边缘=7 内部分割线 3 行/列带均值和, 棋盘格相关=角点 8x8 与棋盘纹相关
    y_min = max(0, int(s_h * 0.15))
    found = []  # [(score, x, y, size)]

    def push(score, x, y, size):
        for i, (s0, x0, y0, sz0) in enumerate(found):
            if abs(x - x0) < 0.1 * s_w and abs(y - y0) < 0.1 * s_w and abs(size - sz0) < 0.08 * s_w:
                if score > s0:
                    found[i] = (score, x, y, size)
                return
        found.append((score, x, y, size))
        if len(found) > 30:
            found.sort(reverse=True, key=lambda t: t[0])
            del found[20:]

    def coarse_scan_size(size: int) -> None:
        # 严格对照逐框版 score_box: 线位置取 int(y + i*step) (随窗口 y 截断,
        # 不同 y 行偏移不同, 不可预四舍五入), y 上界不含 s_h-size
        step = size / 8.0
        n_y = max(0, s_h - size - y_min)  # y ∈ [y_min, s_h-size)
        n_x = s_w - size + 1
        if n_y <= 0:
            return
        yidx = np.arange(n_y) + y_min
        xidx = np.arange(n_x)
        # 纯整数数组二维索引 sat[row_idx[:, None], col_idx] → 稳定 (n_y, n_x),
        # 避免 (数组, 切片) 混用时轴序随数组位置变化的陷阱
        col_x = xidx[None, :]           # (1, n_x): SAT 列坐标 x
        col_xw = col_x + size           # x + size
        row_y = yidx[:, None]           # (n_y, 1): SAT 行坐标 y
        row_yw = row_y + size           # y + size
        edge = np.zeros((n_y, n_x), dtype=np.float64)
        for i in range(1, 8):
            # ly = int(y + i*step) 逐 y 截断: 行带高 3 宽 size, 均值只随 (x,y) 变
            ly = (yidx + i * step).astype(int)[:, None]
            edge += ((sat_mag[ly + 2, col_xw] - sat_mag[ly - 1, col_xw]
                      - sat_mag[ly + 2, col_x] + sat_mag[ly - 1, col_x])
                     / (3.0 * size))
            # lx = int(x + i*step) 逐 x 截断: 列带宽 3 高 size;
            # 右缘 x+lx+2 可能越界 → 按原版 min(s_w, x2) 裁剪语义逐 x 宽度
            lx = (xidx + i * step).astype(int)[None, :]
            lx2 = np.minimum(s_w, lx + 2)  # SAT 列索引钳位
            cw_band = lx2 - (lx - 1)
            edge += ((sat_mag[row_yw, lx2] - sat_mag[row_y, lx2]
                      - sat_mag[row_yw, lx - 1] + sat_mag[row_y, lx - 1])
                     / (cw_band * size))
        cw = max(1, int(step * 0.18))
        inv_cw2 = 1.0 / (cw * cw)
        gm = np.zeros((n_y, n_x, 64), dtype=np.float64)
        # 积分图四角公式: patch 均值 = (S[y+cw,x+cw]-S[y,x+cw]-S[y+cw,x]+S[y,x])/cw^2;
        # 角点坐标 cy1 = int(y + r*step) 逐 y 截断, 用逐行偏移数组索引积分图
        for r in range(8):
            cy1 = (yidx + r * step).astype(int)[:, None]
            cy2b = (yidx + (r + 1) * step).astype(int)[:, None] - cw
            for c in range(8):
                cx1 = int(c * step)   # x 为整数, int(x + c*step) = x + int(c*step) (截断)
                cx2r = int((c + 1) * step) - cw
                # 四个角点 patch: (cy1,cx1) (cy1,cx2r) (cy2b,cx1) (cy2b,cx2r);
                # ro 为 (n_y,1), col_x+标量 为 (1,n_x) → 纯数组索引广播得 (n_y,n_x)
                for ro, coloff in ((cy1, cx1), (cy1, cx2r), (cy2b, cx1),
                                   (cy2b, cx2r)):
                    gm[:, :, r * 8 + c] += (
                        sat_gray[ro + cw, col_x + coloff + cw]
                        - sat_gray[ro, col_x + coloff + cw]
                        - sat_gray[ro + cw, col_x + coloff]
                        + sat_gray[ro, col_x + coloff]) * inv_cw2
        gm *= 0.25
        corr = np.abs(np.einsum('xyk,k->xy', gm - gm.mean(axis=2, keepdims=True),
                                pattern.ravel()))
        bottom = (yidx + size) / s_h
        prior = np.where((bottom >= 0.60) & (bottom <= 0.99), 1.0, 0.35)[:, None]
        sc = (corr * 2.0 + edge * 0.4) * prior
        for row in range(n_y):
            x = int(np.argmax(sc[row]))
            push(float(sc[row, x]), x, y_min + row, size)
    
    # 阶段一: 粗扫 size 步长 8 (x/y 全枚举由向量化承担, 不再需要 step=4 降采样)
    for size in range(min_size, max_size + 1, 8):
        coarse_scan_size(size)
    found.sort(reverse=True, key=lambda t: t[0])
    found = found[:6]

    # 阶段二: 逐候选精修 ±5 像素 step=1 (覆盖粗扫 size 步长 8 的间隙)
    refined = []
    for _, bx, by, bsz in found:
        best = (score_box(bx, by, bsz), bx, by, bsz)
        for size in range(max(min_size, bsz - 5), min(max_size, bsz + 5) + 1):
            for x in range(max(0, bx - 4), min(s_w - size, bx + 4) + 1):
                for y in range(max(0, by - 4), min(s_h - size, by + 4) + 1):
                    sc = score_box(x, y, size)
                    if sc > best[0]:
                        best = (sc, x, y, size)
        refined.append(best)

    inv = 1.0 / scale
    out = []
    for sc, x, y, size in sorted(refined, reverse=True, key=lambda t: t[0]):
        rx = max(0, min(img_w - int(round(size * inv)), int(round(x * inv))))
        ry = max(0, min(img_h - int(round(size * inv)), int(round(y * inv))))
        rs = int(round(size * inv))
        out.append(((rx, ry, rs), float(sc)))
        if len(out) >= top_n:
            break
    return out


def _detect_peaks(prof, min_sep, thr_floor=1.5, pct=75.0):
    """1D 剖面梯度峰检: 局部极大且幅值过阈, 间距 < min_sep 的峰保留最强者。"""
    g = np.abs(np.diff(prof.astype(np.float64)))
    g = np.convolve(g, np.ones(3) / 3.0, mode='same')
    thr = max(thr_floor, np.percentile(g, pct))
    cand = []
    for i in range(1, len(g) - 1):
        if g[i] >= thr and g[i] >= g[i - 1] and g[i] > g[i + 1]:
            cand.append((g[i], i))
    cand.sort(reverse=True)
    keep = []
    for amp, i in cand:
        if all(abs(i - k) >= min_sep for k in keep):
            keep.append(i)
    return sorted(keep)


def _cluster_lines(positions, tol):
    """跨行/列带峰聚类: 间距 < tol 的峰合并为中值, 返回 (位置, 得票数)。"""
    if not positions:
        return []
    ps = sorted(positions)
    groups = [[ps[0]]]
    for p in ps[1:]:
        if p - groups[-1][-1] < tol:
            groups[-1].append(p)
        else:
            groups.append([p])
    return [(float(np.median(g)), len(g)) for g in groups]


def _fit_arithmetic(indexed):
    """对 (i, p_i) 做最小二乘 p = p0 + i*step, 返回 (p0, step, 平均残差)。"""
    idx = np.array([t[0] for t in indexed], dtype=np.float64)
    pos = np.array([t[1] for t in indexed], dtype=np.float64)
    step, p0 = np.polyfit(idx, pos, 1)
    resid = float(np.mean(np.abs(pos - (p0 + idx * step))))
    return float(p0), float(step), resid


def _two_pass_fit(lines, x0_est, step_est):
    """多遍等差拟合: 粗参索引分配 -> 拟合 -> 重分配剔孤立峰 -> 重拟合 -> 剔最大残差点。

    lines: [(position, votes)]。返回 dict(p0, step, residual, n_lines, ok, ok_soft)。
    第一遍用粗框参数做梳状波长先验的索引分配; 第二遍以拟合结果为基准
    剔除偏离 > OUTLIER_FRAC*step 的孤立峰 (棋子轮廓假峰); 第三遍若残差仍超门禁
    则踢除残差最大点重拟 (需保留 >=MIN_LINES 线)。ok_soft: 残差在 (GATE, 4.0]
    且周期与先验偏差 <=1% 的软通过档 (供纵向退化路径降权使用)。
    """
    def assign(p0, step):
        out = []
        for p, _v in lines:
            i = int(round((p - p0) / step))
            if 1 <= i <= 7 and abs(p - (p0 + i * step)) <= OUTLIER_FRAC * step + 2.0:
                out.append((i, p))
        return out

    cand = assign(x0_est, step_est)
    if len(cand) < MIN_LINES:
        return {'p0': None, 'step': None, 'residual': None, 'n_lines': len(cand),
                'ok': False, 'ok_soft': False}
    p0, step, _ = _fit_arithmetic(cand)
    cand2 = assign(p0, step)
    if len(cand2) < MIN_LINES:
        return {'p0': None, 'step': None, 'residual': None, 'n_lines': len(cand2),
                'ok': False, 'ok_soft': False}
    p0, step, resid = _fit_arithmetic(cand2)
    # 第三遍: 残差超门禁时踢除残差最大点 (孤立伪线), 保留 >=MIN_LINES 才生效
    gate = step_est * RELATIVE_RESIDUAL_GATE_RATIO
    if resid > gate and len(cand2) > MIN_LINES:
        worst = max(cand2, key=lambda t: abs(t[1] - (p0 + t[0] * step)))
        cand3 = [t for t in cand2 if t != worst]
        p0b, stepb, residb = _fit_arithmetic(cand3)
        if residb < resid:
            p0, step, resid, cand2 = p0b, stepb, residb, cand3
    ok = resid <= gate
    ok_soft = (not ok) and resid <= step_est * 0.08 and abs(step - step_est) <= 0.01 * step_est
    return {'p0': p0, 'step': step, 'residual': resid, 'n_lines': len(cand2),
            'ok': ok, 'ok_soft': ok_soft}


def refine_horizontal(gray, box):
    """横向梳状谐振定标: 尺寸扫描锁定格宽波长 -> 相位精修 -> 峰拟合 -> 满宽吸附。

    第一层梳状谐振对每个候选 (size, phase) 把棋盘纵切 8 个行带, 逐带累加 7 条
    内部纵线的垂直边缘能量后取跨带中值——真格线全宽贯通 8 带均强, 棋子假峰
    只在个别行带, 中值投票天然压制假峰; 第二层在谐振最优参数附近用带中值
    剖面峰检+两遍等差拟合做亚像素精修。
    """
    x0c, y0c, sizec = [int(v) for v in box]
    H, W = gray.shape[:2]
    gx = np.abs(cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3))
    # 行带窗保持紧 (±0.05*size): 小图格宽小, 宽窗会把 UI 元素 (头像/按钮)
    # 扫进中央 6 带污染中值投票; 粗框纵向偏移由纵向相位多档试探消化
    y1 = int(max(0, y0c - 0.05 * sizec))
    y2 = int(max(y1 + 8, min(H, y0c + sizec + 0.05 * sizec)))
    # 8 个行带取中央 6 带 (首末带常是兵链横行区, 假峰密集):
    # 真格线全宽贯通 6 带均强, 棋子假峰最多污染 1~2 带, 偶数带中值取中二均值天然压制
    bands = np.array_split(np.arange(y1, y2), 8)[1:7]
    band_prof = np.stack([gx[ys, :].astype(np.float64).mean(axis=0) for ys in bands])

    def line_energy(xi):
        """预测列 xi 处的跨带中值边缘能量 (±1 窗内取最大再跨带中值)。"""
        if xi < 1 or xi >= W - 1:
            return 0.0
        win = band_prof[:, xi - 1:xi + 2].max(axis=1)
        return float(np.median(win))

    def comb_score(size, x0):
        sc = sum(line_energy(int(round(x0 + i * size / 8.0))) for i in range(1, 8))
        # 波长先验: 谐振尺寸以粗框尺寸为中心的高斯先验 (sigma=6%),
        # 防密集假峰集在远离先验处形成更强伪谐振 (实测伪谐振比真谐振
        # 高 12%, 但在先验 7.8 sigma 外被压制); 平滑因子无 if-else 分支
        sc *= np.exp(-0.5 * ((size - sizec) / (0.06 * sizec)) ** 2)
        return sc

    # 第一层: 尺寸扫描 (梳状波长先验 lambda ~ W/8), 相位逐像素
    # 相位窗 ±0.25*size: 消化粗定位 x 偏差 (横屏裁剪图可差半格以上),
    # 行带窗紧, 宽相位窗不会引入带外污染
    s_lo = max(8.0, sizec * 0.88)
    s_hi = min(float(W), sizec * 1.14)
    best = (-1.0, None, None)
    for size in np.arange(s_lo, s_hi + 0.25, 0.25):
        xc_lo = max(-size * 0.05, x0c - 0.25 * sizec)
        xc_hi = min(W - size + size * 0.05, x0c + 0.25 * sizec)
        for x0 in np.arange(xc_lo, xc_hi + 0.9, 1.0):
            sc = comb_score(size, x0)
            if sc > best[0]:
                best = (sc, float(size), float(x0))
    sc_best, size_r, x0_r = best
    if size_r is None:
        return {'ok': False, 'x0': x0c, 'size': sizec, 'size_comb': float(sizec),
                'detail': {'p0': None, 'step': None, 'residual': None, 'n_lines': 0, 'ok': False}}

    # 第二层: 谐振最优相位附近峰检 (±2 列, 带中值剖面), 两遍等差拟合亚像素精修
    med_prof = np.median(band_prof, axis=0)
    raw = []
    for i in range(1, 8):
        xc = int(round(x0_r + i * size_r / 8.0))
        lo, hi = max(1, xc - 2), min(W - 2, xc + 2)
        seg = med_prof[lo:hi + 1]
        raw.append(lo + int(np.argmax(seg)))
    lines = [(float(p), 1) for p in raw]
    fit = _two_pass_fit(lines, x0_r, size_r / 8.0)
    # 波长一致性门禁: 拟合 step 必须贴近谐振波长; 密集假峰集可给出低残差
    # 但波长偏离 (实测正常帧 <=0.95%, 假峰锁定 1.96%), 超限判拟合失败
    if fit['ok'] and abs(fit['step'] - size_r / 8.0) > WAVE_GATE * size_r / 8.0:
        fit = {'p0': x0_r, 'step': size_r / 8.0, 'residual': None,
               'n_lines': fit['n_lines'], 'ok': False}
    if fit['ok']:
        x0, size = fit['p0'], 8.0 * fit['step']
    else:
        x0, size = x0_r, size_r
        if fit.get('residual') is None:
            fit = {'p0': x0_r, 'step': size_r / 8.0, 'residual': None,
                   'n_lines': fit['n_lines'], 'ok': False}
    # 满宽吸附: 拟合结果与满宽先验偏差 <= snap_px 时对齐归零 (满宽是拟合的自然特例)
    snap_px = max(2.0, W * RELATIVE_SNAP_RATIO)
    if abs(x0) <= snap_px and abs(size - W) <= snap_px:
        x0, size = 0.0, float(W)
    # size_comb: 谐振最优尺寸 (拟合未通过时的次优证据), 供纵向退化路径使用
    return {'ok': True, 'x0': x0, 'size': size, 'size_comb': float(size_r),
            'detail': fit, 'comb_score': sc_best}


def _outer_edge_score(gray, x0, size, y_fit):
    """退化拟合相位判别: 上下外边界处的横向边缘能量 (真解双边界均有强边)。

    双解歧义 (差一格的两相位拟合残差都低) 的唯一可靠判据: 真解的 y0 与
    y0+size 处应各有贯通棋盘的强横边, 伪解总有一侧落在棋盘内部或 UI 空隙。
    返回 (top_edge_energy, bottom_edge_energy, y_top_edge, y_bottom_edge)。
    """
    H, W = gray.shape[:2]
    gy = np.abs(cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)).astype(np.float64)
    xa = int(max(0, min(W, x0)))
    xb = int(max(xa + 4, min(W, x0 + size)))
    rowg = gy[:, xa:xb].mean(axis=1)

    def local(y):
        yc = int(round(y))
        lo, hi = max(0, yc - 5), min(H, yc + 6)
        if lo >= hi:
            return 0.0, yc
        seg = rowg[lo:hi]
        k = int(np.argmax(seg))
        return float(seg[k]), lo + k

    te, yt = local(y_fit)
    be, yb = local(y_fit + size)
    return te, be, yt, yb


def _vertical_bar_anchors(gray, box, expected_size, x_extent=None):
    """上下框主锚: 行均值剖面强边 + 板内侧低 std 判据, 方约束配对选优。

    返回 (top_edge, bottom_edge, dev) 或 None。上框下边界=棋盘上边界,
    下框上边界=棋盘下边界; 上下框独立检测 (不对称、锚点质量不同)。
    x_extent=(x0, size): 剖面与梯度限制在实测横向范围内 (含 2% 外拓),
    防止横屏/裁剪图中全宽 UI 横边冒充框锚。
    """
    x0c, y0c, sizec = box
    H, W = gray.shape[:2]
    if x_extent is not None:
        xa = int(max(0, min(W, x_extent[0] - 0.02 * W)))
        xb = int(max(xa + 4, min(W, x_extent[0] + x_extent[1] + 0.02 * W)))
        sub = gray[:, xa:xb]
    else:
        sub = gray
    span = int(0.18 * W)
    t_lo, t_hi = max(0, y0c - span), min(H - 1, y0c + int(0.05 * W))
    b_lo, b_hi = max(0, y0c + sizec - int(0.05 * W)), min(H - 2, y0c + sizec + span)
    prof = sub.astype(np.float64).mean(axis=1)
    g = np.abs(np.diff(prof))
    row_std = sub.astype(np.float64).std(axis=1)

    def cands(lo, hi, side):
        out = []
        for y in range(lo, hi):
            if g[y] < BAR_GRAD_MIN:
                continue
            if not (g[y] >= g[y - 1] and g[y] >= g[min(len(g) - 1, y + 1)]):
                continue
            # 均匀带判据看条带侧(棋盘外): 棋盘顶部边界上方存在亮条带/暗吃子栏底部,
            # 底部边界下方存在暗吃子条; 边界内侧也可能是棋盘自身细边框带, 双向都查
            if side == 'top':
                bands = [row_std[max(0, y - 4):y], row_std[y + 1:y + 5]]
            else:
                bands = [row_std[max(0, y - 3):y + 1], row_std[y + 1:y + 5]]
            if any(b.size >= 3 and b.mean() < BAR_STD_GATE for b in bands):
                out.append(y)
        return out

    tops = cands(t_lo, t_hi, 'top')
    bots = cands(b_lo, b_hi, 'bottom')
    best = None
    square_gate = max(4.0, expected_size * RELATIVE_SQUARE_GATE_RATIO)
    for t in tops:
        for b in bots:
            dev = abs((b - t) - expected_size)
            if dev <= square_gate and (best is None or dev < best[2]):
                best = (t, b, dev)
    # 多候选时优先取最贴近粗框边缘的配对 (粗框本身就是近似, 防止远端 UI 横边冒充框锚)
    ties = [(t, b, abs((b - t) - expected_size)) for t in tops for b in bots
            if abs((b - t) - expected_size) <= square_gate]
    if ties:
        best = min(ties, key=lambda e: (e[2], abs(e[0] - y0c) + abs(e[1] - (y0c + sizec))))
    return best


def _fit_horizontal_lines(gray, box):
    """退化路径: 内部横分割线行剖面等差拟合 (两遍, 同横向逻辑)。"""
    x0c, y0c, sizec = box
    H, W = gray.shape[:2]
    s_est = sizec / 8.0
    raw = []
    for c in range(8):
        x1 = int(max(0, x0c + (c + 0.22) * s_est))
        x2 = int(min(W, x0c + (c + 0.78) * s_est))
        if x2 - x1 < 4:
            continue
        # 列带窄 (0.56*step), 窗宽 ±0.5*size 也不易被棋子/UI 污染,
        # 可覆盖粗框 y 偏差达半格以上的场景
        y_lo = int(max(0, y0c - 0.5 * sizec))
        y_hi = int(min(H, y0c + sizec + 0.5 * sizec))
        prof = gray[y_lo:y_hi, x1:x2].astype(np.float64).mean(axis=1)
        for p in _detect_peaks(prof, min_sep=0.5 * s_est):
            raw.append(p + y_lo)
    lines = _cluster_lines(raw, tol=0.25 * s_est)
    return _two_pass_fit(lines, y0c, s_est)


def refine_grid(img_bgr, coarse_box):
    """精标定主入口: 粗框 -> 横向等差拟合 + 纵向框锚(退化内部横线) -> RefineResult。

    coarse_box: (x0, y0, size)。返回 dict(rect=(x0,y0,size), confidence, residual, detail)。
    confidence: high=横纵均过门禁; medium=单轴精标定; low=双轴退化保持粗框。
    """
    x0c, y0c, sizec = [int(v) for v in coarse_box]
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)

    horiz = refine_horizontal(gray, (x0c, y0c, sizec))
    size = horiz['size'] if horiz['ok'] else sizec
    x0 = horiz['x0'] if horiz['ok'] else x0c
    # 纵向退化路径的尺寸基准: 拟合通过用拟合值, 否则用谐振最优尺寸
    # (粗 sizec 可能被假峰拉偏, 导致多档试探锁到 UI 竖边伪解)
    size_v = horiz['size'] if horiz['ok'] else horiz['size_comb']

    bars = _vertical_bar_anchors(gray, (x0c, y0c, sizec), expected_size=size,
                                 x_extent=(x0, size))
    if bars is not None:
        # 第三重门禁: 内部横线拟合交叉验证——以框锚 y0 为相位基准拟合 7 条
        # 内部分割线, 残差超限即判假锚 (假边界恰好方约束成立的场景), 转退化路径
        cross = _fit_horizontal_lines(gray, (x0c, bars[0], int(round(size))))
        if cross['ok']:
            y0 = float(bars[0])
            v_path, v_resid = 'bars', bars[2]
        else:
            bars = None
    if bars is None:
        # 相位多档试探: 粗框 y 可能差一格以上 (横屏裁剪图实测差 ~1.2 格),
        # 指数分配以试探 y 为锚; 由近及远试 0, ±0.5, ±1, ±1.5, ±2 格。
        # 门禁三重: 残差 + 波长一致性 (防假峰集低残差锁错波长,
        # 实测: 真相位 step 贴近先验, 伪相位常锁 0.9x 假波长) + 边界边缘一致性;
        # 遍历全档取残差最优 (不能首过即停: 近档伪解可能先过)
        s_est = size_v / 8.0
        chosen = None
        for k in (0, -0.5, 0.5, -1.0, 1.0, -1.5, 1.5, -2.0, 2.0):
            y_try = y0c + k * s_est
            hfit = _fit_horizontal_lines(gray, (x0c, int(round(y_try)), int(round(size_v))))
            if not hfit['ok']:
                continue
            if abs(hfit['step'] - s_est) > WAVE_GATE_V * s_est:
                continue
            y0_fit = hfit['p0']
            te, be, yt, yb = _outer_edge_score(gray, x0, size_v, y0_fit)
            if te >= BAR_GRAD_MIN and be >= BAR_GRAD_MIN:
                if chosen is None or hfit['residual'] < chosen[1]:
                    # 吸附到实测边缘位置 (±3px 内), 消化剖面相位误差
                    chosen = (float(yt), hfit['residual'])
        if chosen is not None:
            y0, v_path, v_resid = chosen[0], 'gridlines', chosen[1]
        else:
            y0, v_path, v_resid = float(y0c), 'coarse', None

    # 横纵交替二次精修: 首轮横向行带窗以粗框 y 为准 (可能含 UI/字幕污染),
    # 纵向收敛后以精标定框重跑横向, 行带窗精确落在棋盘区,
    # 剩离带外假峰; 拟合通过才采纳 (失败保持首轮结果)
    if v_path != 'coarse':
        horiz2 = refine_horizontal(gray, (int(round(x0)), int(round(y0)), int(round(size))))
        if horiz2['ok']:
            x0, size = horiz2['x0'], horiz2['size']
            horiz = horiz2

    if horiz['ok'] and v_path != 'coarse':
        confidence = 'high' if v_path == 'bars' else 'medium'
    elif horiz['ok'] or v_path != 'coarse':
        confidence = 'medium'
    else:
        confidence = 'low'

    residual = max(horiz['detail']['residual'] or 0.0,
                   v_resid if v_resid is not None else 99.0)
    return {
        'rect': (int(round(x0)), int(round(y0)), int(round(size))),
        'confidence': confidence,
        'residual': residual,
        'detail': {
            'horizontal': horiz,
            'vertical_path': v_path,
            'bars': bars,
            'coarse': (x0c, y0c, sizec),
        },
    }


_CONF_RANK = {'high': 2, 'medium': 1, 'low': 0}


def locate_board(image, top_n=3):
    """完整定位主入口: 放宽粗定位 top-N -> 逐候选独立 refine -> 置信度选优。

    逐候选独立精标定 (不共享锚), 防止伪候选区域内强行吸附;
    选优次序: 置信度等级 > 残差小 > size 贴近屏宽 (满宽先验)。
    """
    H, W = image.shape[:2]
    scored = coarse_candidates(image, top_n=top_n)
    if not scored:
        return None
    cands = [c for c, _s in scored]
    scores = {c: s for c, s in scored}
    results = [refine_grid(image, c) for c in cands]

    snap_px = max(2.0, W * RELATIVE_SNAP_RATIO)
    def _key(r):
        full = 1 if abs(r['rect'][2] - W) <= snap_px else 0
        # 残差接近 (差一格双解歧义) 时回落粗定位分数: 粗扫含 8x8 棋盘格
        # 相关性, 对绝对相位敏感, 可判别精标定无法区分的同构双解
        coarse = r['detail']['coarse']
        return (-_CONF_RANK[r['confidence']], round(r['residual'] * 2) / 2,
                -full, -scores.get(coarse, 0.0))

    results.sort(key=_key)
    best = results[0]
    best['candidates'] = list(zip(cands, results))
    return best
