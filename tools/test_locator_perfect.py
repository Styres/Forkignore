import cv2
import numpy as np
import os

def locate_duolingo_board(image):
    """
    针对多邻国下棋界面的超稳健 2D 棋盘自适应定位器
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 1. 棋盘宽度通常为屏幕宽度的 90% ~ 98%
    # 左右居中，边距左右相等
    # 我们搜索棋盘宽度 W in [0.88 * img_w, 0.98 * img_w]
    
    # 计算水平和垂直方向的 Sobel 梯度
    grad_x = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    grad_y = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.abs(grad_x) + np.abs(grad_y)
    
    best_score = -1e9
    best_box = None
    
    # 缩小做快速精细网格扫描
    scale = 400.0 / img_w
    s_w = 400
    s_h = int(img_h * scale)
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    s_mag = cv2.resize(mag, (s_w, s_h))
    
    # 棋盘大小在 s_w 下通常在 [350, 396] 之间
    min_size = int(0.85 * s_w)
    max_size = min(s_w, int(0.98 * s_w))
    
    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
            
    for size in range(min_size, max_size + 1, 2):
        step = size / 8.0
        center_x = (s_w - size) // 2
        # x 左右微调搜索
        for x in range(max(0, center_x - 6), min(s_w - size + 1, center_x + 7), 2):
            # y 坐标：棋盘在下半部分（距离底部约 10~15% 留白）
            # 搜索范围：从 (s_h * 0.3) 到 (s_h - size - 2)
            y_min = int(s_h * 0.25)
            y_max = s_h - size
            for y in range(y_min, y_max, 2):
                # 计算 1: 7条内分割线的边缘能量
                edge_score = 0.0
                for i in range(1, 8):
                    line_y = int(y + i * step)
                    line_x = int(x + i * step)
                    # 采样水平分割线上的梯度
                    edge_score += np.mean(s_mag[line_y-1:line_y+2, x:x+size])
                    # 采样垂直分割线上的梯度
                    edge_score += np.mean(s_mag[y:y+size, line_x-1:line_x+2])
                
                # 计算 2: 棋盘格交替特征
                grid_means = np.zeros((8, 8), dtype=np.float32)
                for r in range(8):
                    cy1 = int(y + r * step)
                    cy2 = int(y + (r + 1) * step)
                    for c in range(8):
                        cx1 = int(x + c * step)
                        cx2 = int(x + (c + 1) * step)
                        cell = s_gray[cy1:cy2, cx1:cx2]
                        if cell.size == 0:
                            continue
                        ch, cw = cell.shape
                        # 采样四周角落
                        c_vals = [
                            cell[0:max(1, int(ch*0.2)), 0:max(1, int(cw*0.2))],
                            cell[0:max(1, int(ch*0.2)), -max(1, int(cw*0.2)):],
                            cell[-max(1, int(ch*0.2)):, 0:max(1, int(cw*0.2))],
                            cell[-max(1, int(ch*0.2)):, -max(1, int(cw*0.2)):]
                        ]
                        grid_means[r, c] = np.mean([np.mean(cv) for cv in c_vals])
                
                grid_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(grid_norm * pattern))
                
                # 综合打分：分割线边缘能量 + 棋盘格交替相关性 + 靠近底部的合理先验
                # 多邻国棋盘底部一般位于屏幕靠底 80%~98% 处
                bottom_ratio = (y + size) / s_h
                pos_prior = 1.0 if 0.75 <= bottom_ratio <= 0.98 else 0.4
                
                total_score = (corr * 2.0 + edge_score * 0.5) * pos_prior
                
                if total_score > best_score:
                    best_score = total_score
                    best_box = (x, y, size)
                    
    x_s, y_s, size_s = best_box
    inv_scale = 1.0 / scale
    x = int(round(x_s * inv_scale))
    y = int(round(y_s * inv_scale))
    size = int(round(size_s * inv_scale))
    
    return (x, y, x + size, y + size)

# 重新运行并可视化
os.makedirs("scratch/debug_board_perfect", exist_ok=True)
for filename in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(filename)
    l, t, r, b = locate_duolingo_board(img)
    print(f"[{filename}] Perfect Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 4)
    step = (r - l) / 8.0
    for i in range(1, 8):
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 2)
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 2)
        
    cv2.imwrite(f"scratch/debug_board_perfect/{filename}_board.png", vis)
