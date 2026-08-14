import cv2
import numpy as np
import os

def comb_filter_locate_board(image):
    """
    基于 8 周期梳状谐振滤波器 (Comb Filter Resonator) 的超高鲁棒性棋盘定位算法
    原理：
    1. 棋盘水平方向永远居中 (x = (W - size) / 2)
    2. 棋盘由 8 个高度为 step = size / 8 的行组成，在 7 条内部水平线和 7 条内部垂直线处具有强共振响应
    3. 梳状滤波器只对等间距周期性网格产生极大响应，彻底过滤文字与人物立绘杂波
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 梯度图
    gx = np.abs(cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3))
    gy = np.abs(cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3))
    
    # 平滑梯度以连接断线
    k_w = max(3, int(img_w * 0.04))
    h_lines = cv2.blur(gy, (k_w, 3)) # 突出水平连续线
    v_lines = cv2.blur(gx, (3, k_w)) # 突出垂直连续线
    
    # 棋盘尺寸搜索范围：
    # 竖屏模式：size 占屏幕宽度的 88% ~ 98%
    # 横屏/平板模式：size 占屏幕高度的 75% ~ 95%
    if img_w <= img_h:
        min_size = int(0.85 * img_w)
        max_size = min(img_w - 2, int(0.98 * img_w))
    else:
        min_size = int(0.70 * img_h)
        max_size = min(img_h - 2, int(0.95 * img_h))
        
    best_score = -1e9
    best_box = None
    
    # 搜索尺寸
    step_s = max(2, int((max_size - min_size) / 30))
    for size in range(min_size, max_size + 1, step_s):
        grid_step = size / 8.0
        x = (img_w - size) // 2 # 水平严格居中
        
        # y 搜索范围：在 0 到 img_h - size 之间
        step_y = max(2, int((img_h - size) / 60))
        for y in range(0, img_h - size + 1, step_y):
            # 1. 梳状滤波响应：7条内部分割线处的响应
            h_peak = 0.0
            h_valley = 0.0
            for i in range(1, 8):
                line_y = int(y + i * grid_step)
                mid_y = int(y + (i - 0.5) * grid_step)
                if 0 <= line_y < img_h:
                    h_peak += np.mean(h_lines[max(0, line_y-1):min(img_h, line_y+2), x:x+size])
                if 0 <= mid_y < img_h:
                    h_valley += np.mean(h_lines[max(0, mid_y-1):min(img_h, mid_y+2), x:x+size])
                    
            # 垂直梳状响应
            v_peak = 0.0
            v_valley = 0.0
            for i in range(1, 8):
                line_x = int(x + i * grid_step)
                mid_x = int(x + (i - 0.5) * grid_step)
                if 0 <= line_x < img_w:
                    v_peak += np.mean(v_lines[y:y+size, max(0, line_x-1):min(img_w, line_x+2)])
                if 0 <= mid_x < img_w:
                    v_valley += np.mean(v_lines[y:y+size, max(0, mid_x-1):min(img_w, mid_x+2)])
                    
            # 共振对比度 (峰值能量 / 谷值能量)
            comb_score = (h_peak + v_peak) - (h_valley + v_valley) * 0.7
            
            # 2. 采样 8x8 格子角点的棋盘格交替方差
            pattern = np.zeros((8, 8), dtype=np.float32)
            grid_means = np.zeros((8, 8), dtype=np.float32)
            for r in range(8):
                for c in range(8):
                    pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
                    cy1 = int(y + r * grid_step)
                    cy2 = int(y + (r + 1) * grid_step)
                    cx1 = int(x + c * grid_step)
                    cx2 = int(x + (c + 1) * grid_step)
                    cell = gray[cy1:cy2, cx1:cx2]
                    if cell.size == 0:
                        continue
                    ch, cw = cell.shape
                    # 采样角点
                    c1 = cell[0:max(1, int(ch*0.15)), 0:max(1, int(cw*0.15))]
                    c2 = cell[0:max(1, int(ch*0.15)), -max(1, int(cw*0.15)):]
                    c3 = cell[-max(1, int(ch*0.15)):, 0:max(1, int(cw*0.15))]
                    c4 = cell[-max(1, int(ch*0.15)):, -max(1, int(cw*0.15)):]
                    grid_means[r, c] = (np.mean(c1) + np.mean(c2) + np.mean(c3) + np.mean(c4)) / 4.0
                    
            g_norm = grid_means - np.mean(grid_means)
            corr = abs(np.sum(g_norm * pattern))
            
            total_score = comb_score * 0.8 + corr * 1.5
            if total_score > best_score:
                best_score = total_score
                best_box = (x, y, size)
                
    bx, by, bsize = best_box
    return (bx, by, bx + bsize, by + bsize)

# 测试全量 6 张图
os.makedirs("scratch/debug_comb", exist_ok=True)
all_images = [
    "duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg",
    "duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"
]

for filename in all_images:
    img = cv2.imread(filename)
    if img is None:
        continue
    l, t, r, b = comb_filter_locate_board(img)
    print(f"[{filename}] Comb Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 2)
    step = (r - l) / 8.0
    for i in range(1, 8):
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 1)
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 1)
    cv2.imwrite(f"scratch/debug_comb/{filename}_board.png", vis)

print("全部 6 张图梳状滤波定位完成！已保存至 scratch/debug_comb/")
