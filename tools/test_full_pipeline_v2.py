import cv2
import numpy as np
import os
import sys

def fast_sat_locate_board(image, return_score=False):
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    scale = 400.0 / img_w
    s_w = 400
    s_h = int(img_h * scale)
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    
    gx = cv2.Sobel(s_gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(s_gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.abs(gx) + np.abs(gy)
    
    sat_gray = cv2.integral(s_gray)
    sat_mag = cv2.integral(mag)
    
    def rect_sum(sat, x1, y1, x2, y2):
        x1, y1 = max(0, int(x1)), max(0, int(y1))
        x2, y2 = min(s_w, int(x2)), min(s_h, int(y2))
        if x2 <= x1 or y2 <= y1: return 0.0
        return sat[y2, x2] - sat[y1, x2] - sat[y2, x1] + sat[y1, x1]

    def rect_mean(sat, x1, y1, x2, y2):
        w = max(1, int(x2) - int(x1))
        h = max(1, int(y2) - int(y1))
        return rect_sum(sat, x1, y1, x2, y2) / (w * h)

    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
            
    best_score = -1e9
    best_box = (0, 0, int(0.9 * s_w))
    
    min_size = int(0.85 * s_w)
    max_size = min(s_w, int(0.98 * s_w))
    
    # 阶段一：粗扫 (step=4)
    for size in range(min_size, max_size + 1, 4):
        step = size / 8.0
        center_x = (s_w - size) // 2
        for x in range(max(0, center_x - 8), min(s_w - size + 1, center_x + 9), 4):
            y_min = int(s_h * 0.20)
            y_max = s_h - size
            for y in range(y_min, y_max, 4):
                # 1. 7条横纵分割线边缘能量
                edge_score = 0.0
                for i in range(1, 8):
                    ly = int(y + i * step)
                    lx = int(x + i * step)
                    edge_score += rect_mean(sat_mag, x, ly - 1, x + size, ly + 2)
                    edge_score += rect_mean(sat_mag, lx - 1, y, lx + 2, y + size)
                    
                # 2. 8x8 格子 4 角采样
                grid_means = np.zeros((8, 8), dtype=np.float32)
                corner_w = max(1, int(step * 0.18))
                for r in range(8):
                    cy1 = y + r * step
                    cy2 = cy1 + step
                    for c in range(8):
                        cx1 = x + c * step
                        cx2 = cx1 + step
                        m1 = rect_mean(sat_gray, cx1, cy1, cx1 + corner_w, cy1 + corner_w)
                        m2 = rect_mean(sat_gray, cx2 - corner_w, cy1, cx2, cy1 + corner_w)
                        m3 = rect_mean(sat_gray, cx1, cy2 - corner_w, cx1 + corner_w, cy2)
                        m4 = rect_mean(sat_gray, cx2 - corner_w, cy2 - corner_w, cx2, cy2)
                        grid_means[r, c] = (m1 + m2 + m3 + m4) * 0.25
                        
                g_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(g_norm * pattern))
                
                # 3. 垂直合理性先验 (多邻国棋盘底部一般在屏幕下部 70%~98%)
                bottom_ratio = (y + size) / s_h
                pos_prior = 1.0 if 0.70 <= bottom_ratio <= 0.98 else 0.35
                
                score = (corr * 2.0 + edge_score * 0.4) * pos_prior
                if score > best_score:
                    best_score = score
                    best_box = (x, y, size)
                    
    # 阶段二：精修 (在最佳点周围 ±4 像素做 step=1 搜索)
    opt_x, opt_y, opt_size = best_box
    for size in range(max(min_size, opt_size - 4), min(max_size, opt_size + 5), 1):
        step = size / 8.0
        for x in range(max(0, opt_x - 4), min(s_w - size + 1, opt_x + 5), 1):
            for y in range(max(0, opt_y - 4), min(s_h - size + 1, opt_y + 5), 1):
                edge_score = 0.0
                for i in range(1, 8):
                    ly = int(y + i * step)
                    lx = int(x + i * step)
                    edge_score += rect_mean(sat_mag, x, ly - 1, x + size, ly + 2)
                    edge_score += rect_mean(sat_mag, lx - 1, y, lx + 2, y + size)
                grid_means = np.zeros((8, 8), dtype=np.float32)
                corner_w = max(1, int(step * 0.18))
                for r in range(8):
                    cy1 = y + r * step
                    cy2 = cy1 + step
                    for c in range(8):
                        cx1 = x + c * step
                        cx2 = cx1 + step
                        m1 = rect_mean(sat_gray, cx1, cy1, cx1 + corner_w, cy1 + corner_w)
                        m2 = rect_mean(sat_gray, cx2 - corner_w, cy1, cx2, cy1 + corner_w)
                        m3 = rect_mean(sat_gray, cx1, cy2 - corner_w, cx1 + corner_w, cy2)
                        m4 = rect_mean(sat_gray, cx2 - corner_w, cy2 - corner_w, cx2, cy2)
                        grid_means[r, c] = (m1 + m2 + m3 + m4) * 0.25
                g_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(g_norm * pattern))
                bottom_ratio = (y + size) / s_h
                pos_prior = 1.0 if 0.70 <= bottom_ratio <= 0.98 else 0.35
                score = (corr * 2.0 + edge_score * 0.4) * pos_prior
                if score > best_score:
                    best_score = score
                    best_box = (x, y, size)

    x_s, y_s, size_s = best_box
    inv_scale = 1.0 / scale
    x = int(round(x_s * inv_scale))
    y = int(round(y_s * inv_scale))
    size = int(round(size_s * inv_scale))
    
    x = max(0, min(img_w - size, x))
    y = max(0, min(img_h - size, y))
    if return_score:
        return x, y, x + size, y + size, float(best_score)
    return x, y, x + size, y + size

if __name__ == '__main__':
    images = ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg", "duolingo_test_1.jfif", "duolingo_test_2.jfif"]
    for img_name in images:
        if not os.path.exists(img_name): continue
        img = cv2.imread(img_name)
        l, t, r, b = fast_sat_locate_board(img)
        print(f"[{img_name}] Size={r-l}x{b-t}, Rect=[L={l}, T={t}, R={r}, B={b}], Img={img.shape[1]}x{img.shape[0]}")
