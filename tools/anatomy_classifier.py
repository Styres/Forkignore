import cv2
import numpy as np

def extract_geometry_profile(cell_img):
    """
    提取棋子不受分辨率和压缩影响的 6 大本质几何物理不变量：
    1. aspect_ratio: 纵横比
    2. top_vs_bottom_width: 顶部宽度与底部宽度比值（Queen > 1.2, Rook ≈ 1.0, Pawn < 0.7）
    3. asymmetry: 左右质心与边缘不对称度（Knight 显著极高）
    4. area_fill: 轮廓面积占外接框比例
    5. cross_response: 中心十字卷积响应（King 独有）
    """
    # 统一尺寸 40x40
    resized = cv2.resize(cell_img, (40, 40))
    gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
    core = gray[6:34, 6:34] # 28x28
    
    # 局部背景
    bg = (np.mean(core[:3, :3]) + np.mean(core[:3, -3:]) + 
          np.mean(core[-3:, :3]) + np.mean(core[-3:, -3:])) / 4.0
    
    # 前景二值化
    diff = np.abs(core.astype(np.float32) - bg)
    thresh = np.max(diff) * 0.35
    mask = (diff > thresh).astype(np.uint8)
    
    # 若无有效前景
    if np.sum(mask) < 25:
        return {'is_empty': True, 'mean': np.mean(core)}
        
    # 水平每行宽度
    row_widths = np.sum(mask, axis=1)
    active_rows = np.where(row_widths > 1)[0]
    if len(active_rows) == 0:
        return {'is_empty': True, 'mean': np.mean(core)}
        
    top_r = active_rows[0]
    bot_r = active_rows[-1]
    height = bot_r - top_r + 1
    
    # 顶部 1/3 宽度 vs 底部 1/3 宽度
    top_w = np.mean(row_widths[top_r : top_r + max(1, height//3)])
    bot_w = np.mean(row_widths[max(top_r, bot_r - height//3) : bot_r + 1])
    top_bot_ratio = top_w / (bot_w + 1e-3)
    
    # 左右不对称度
    w_half = mask.shape[1] // 2
    left_m = mask[:, :w_half]
    right_m = cv2.flip(mask[:, w_half:], 1)[:, :w_half]
    asym = np.sum(np.abs(left_m - right_m)) / (np.sum(mask) + 1e-3)
    
    # 十字响应
    cross_kernel = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=np.float32)
    cross_resp = cv2.filter2D(diff, -1, cross_kernel)
    cross_score = np.max(cross_resp[6:22, 6:22]) / (np.max(diff) + 1e-3)
    
    return {
        'is_empty': False,
        'mean': np.mean(core),
        'height': height,
        'area': np.sum(mask),
        'top_bot_ratio': top_bot_ratio,
        'asym': asym,
        'cross': cross_score
    }

class DuolingoAnatomyClassifier:
    def classify_board(self, cells_8x8):
        profiles = [[extract_geometry_profile(cells_8x8[r][c]) for c in range(8)] for r in range(8)]
        
        occupied = []
        for r in range(8):
            for c in range(8):
                p = profiles[r][c]
                if p['is_empty'] or p['area'] < 40:
                    continue
                    
                # 规则物理决策：
                # 1. 马 Knight: 左右显著不对称 (asym 高)
                if p['asym'] > 0.28:
                    cls_name = 'N'
                # 2. 王 King: 胸口有十字特征且面积大
                elif p['cross'] > 3.2 and p['area'] > 120:
                    cls_name = 'K'
                # 3. 后 Queen: 顶部开叉外扩，top_bot_ratio 很高
                elif p['top_bot_ratio'] > 0.95 and p['area'] > 110:
                    cls_name = 'Q'
                # 4. 车 Rook: 上下等宽直筒，且高度适中
                elif 0.75 <= p['top_bot_ratio'] <= 1.05 and p['area'] > 110:
                    cls_name = 'R'
                # 5. 象 Bishop: 尖顶，顶部宽度小，中间饱满
                elif p['top_bot_ratio'] < 0.75 and p['area'] > 95:
                    cls_name = 'B'
                # 6. 兵 Pawn: 面积最小，高度最矮
                else:
                    cls_name = 'P'
                    
                occupied.append((r, c, p, cls_name))
                
        if not occupied:
            return [['.' for _ in range(8)] for _ in range(8)]
            
        # 自适应 2-Means 划分黑白
        means = [item[2]['mean'] for item in occupied]
        c1, c2 = min(means), max(means)
        for _ in range(10):
            g1 = [m for m in means if abs(m - c1) <= abs(m - c2)]
            g2 = [m for m in means if abs(m - c1) > abs(m - c2)]
            if g1: c1 = float(np.mean(g1))
            if g2: c2 = float(np.mean(g2))
            
        split_thresh = (c1 + c2) / 2.0
        
        board_res = [['.' for _ in range(8)] for _ in range(8)]
        for r, c, p, cls_name in occupied:
            is_white = (p['mean'] >= split_thresh)
            symbol = cls_name if is_white else cls_name.lower()
            board_res[r][c] = symbol
            
        return board_res

print("DuolingoAnatomyClassifier 几何物理分类器构建完成！")
