import cv2
import numpy as np
import os

def find_board_rect_checkerboard_corr(image):
    """
    基于 8x8 棋盘格交替相关性（Checkerboard Alternating Correlation）精准定位棋盘
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 构造标准的 8x8 交替模式 (+1, -1, +1, -1 ...)
    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
            
    # 缩小做快速搜索
    scale = 300.0 / img_w
    small_w = 300
    small_h = int(img_h * scale)
    small_gray = cv2.resize(gray, (small_w, small_h)).astype(np.float32)
    
    best_score = -1.0
    best_rect = None
    
    # 搜索尺寸 (在 small 尺度下，棋盘宽度通常为 250 ~ 298)
    for size in range(240, min(small_w, 298), 2):
        # x 居中搜索
        center_x = (small_w - size) // 2
        for x in range(max(0, center_x - 10), min(small_w - size + 1, center_x + 11), 2):
            # y 坐标在屏幕 25% 到 85% 之间
            for y in range(int(small_h * 0.2), int(small_h * 0.9) - size, 3):
                # 提取 8x8 格子均值
                step = size / 8.0
                grid_means = np.zeros((8, 8), dtype=np.float32)
                
                # 为避免棋子中心干扰，取每个格子的 4 个角落或边缘区域采样均值
                for r in range(8):
                    y1 = int(y + r * step)
                    y2 = int(y + (r + 1) * step)
                    for c in range(8):
                        x1 = int(x + c * step)
                        x2 = int(x + (c + 1) * step)
                        
                        # 采样格子的角隅区域（不受中间棋子遮挡）
                        cell = small_gray[y1:y2, x1:x2]
                        if cell.size == 0:
                            continue
                        ch, cw = cell.shape
                        # 采样四角各 20%
                        corner_vals = [
                            cell[0:max(1, int(ch*0.25)), 0:max(1, int(cw*0.25))],
                            cell[0:max(1, int(ch*0.25)), -max(1, int(cw*0.25)):],
                            cell[-max(1, int(ch*0.25)):, 0:max(1, int(cw*0.25))],
                            cell[-max(1, int(ch*0.25)):, -max(1, int(cw*0.25)):]
                        ]
                        grid_means[r, c] = np.mean([np.mean(cv) for cv in corner_vals])
                        
                # 计算与 Pattern 的相关性能量
                # 去除整体平均值
                grid_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(grid_norm * pattern))
                
                if corr > best_score:
                    best_score = corr
                    best_rect = (x, y, size)
                    
    x_s, y_s, size_s = best_rect
    inv_scale = 1.0 / scale
    x = int(round(x_s * inv_scale))
    y = int(round(y_s * inv_scale))
    size = int(round(size_s * inv_scale))
    
    # 确保不越界
    x = max(0, min(img_w - size, x))
    y = max(0, min(img_h - size, y))
    
    return (x, y, x + size, y + size)

# 重新运行并可视化
os.makedirs("scratch/debug_board_corr", exist_ok=True)
for filename in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(filename)
    l, t, r, b = find_board_rect_checkerboard_corr(img)
    print(f"[{filename}] Corr Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 4)
    step = (r - l) / 8.0
    for i in range(1, 8):
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 2)
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 2)
        
    cv2.imwrite(f"scratch/debug_board_corr/{filename}_board.png", vis)
