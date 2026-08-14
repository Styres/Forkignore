import cv2
import numpy as np
import os

def universal_locate_board(image):
    """
    全通用自适应 2D 国际象棋棋盘定位器 (Universal Multi-Scale Checkerboard Locator)
    无任何机型/屏幕朝向/底部弹窗的先验假设，纯数学特征全局寻优
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 构造 8x8 交替模式
    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
            
    # 降采样到统一尺度 (最大边 480) 保证毫秒级计算
    max_dim = 480.0
    scale = max_dim / max(img_w, img_h)
    s_w = int(round(img_w * scale))
    s_h = int(round(img_h * scale))
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    
    # 边缘梯度
    gx = cv2.Sobel(s_gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(s_gray, cv2.CV_32F, 0, 1, ksize=3)
    s_mag = np.sqrt(gx**2 + gy**2)
    
    best_score = -1e9
    best_rect = None # (x, y, size) in scaled coords
    
    # 棋盘边长在 scaled 尺度下的范围：min_dim * 0.5 到 min_dim * 0.98
    min_dim = min(s_w, s_h)
    min_s = max(80, int(min_dim * 0.55))
    max_s = int(min_dim * 0.98)
    
    for size in range(min_s, max_s + 1, 4):
        step = size / 8.0
        # 遍历 x 坐标
        for x in range(0, s_w - size + 1, 4):
            # 遍历 y 坐标
            for y in range(0, s_h - size + 1, 4):
                # 采样 8x8 格子角点均值
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
                        # 采样四角各 15% 消除中间棋子遮挡
                        c1 = cell[0:max(1, int(ch*0.18)), 0:max(1, int(cw*0.18))]
                        c2 = cell[0:max(1, int(ch*0.18)), -max(1, int(cw*0.18)):]
                        c3 = cell[-max(1, int(ch*0.18)):, 0:max(1, int(cw*0.18))]
                        c4 = cell[-max(1, int(ch*0.18)):, -max(1, int(cw*0.18)):]
                        grid_means[r, c] = (np.mean(c1) + np.mean(c2) + np.mean(c3) + np.mean(c4)) / 4.0
                        
                # 计算 8x8 交替相关性
                g_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(g_norm * pattern))
                
                # 计算 7+7 条网格线边缘能量
                edge_sum = 0.0
                for i in range(1, 8):
                    ly = int(y + i * step)
                    lx = int(x + i * step)
                    edge_sum += np.mean(s_mag[ly-1:ly+2, x:x+size])
                    edge_sum += np.mean(s_mag[y:y+size, lx-1:lx+2])
                    
                total_score = corr * 2.0 + edge_sum * 0.4
                if total_score > best_score:
                    best_score = total_score
                    best_rect = (x, y, size)
                    
    x_s, y_s, size_s = best_rect
    inv_scale = 1.0 / scale
    x = int(round(x_s * inv_scale))
    y = int(round(y_s * inv_scale))
    size = int(round(size_s * inv_scale))
    
    # 保证在图像内部
    x = max(0, min(img_w - size, x))
    y = max(0, min(img_h - size, y))
    
    return (x, y, x + size, y + size)

# 在 6 张全量图片上测试
os.makedirs("scratch/debug_universal", exist_ok=True)
all_images = [
    "duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg",
    "duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"
]

for filename in all_images:
    img = cv2.imread(filename)
    if img is None:
        continue
    l, t, r, b = universal_locate_board(img)
    print(f"[{filename}] Universal Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 2)
    step = (r - l) / 8.0
    for i in range(1, 8):
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 1)
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 1)
    cv2.imwrite(f"scratch/debug_universal/{filename}_board.png", vis)

print("全部 6 张图片通用定位完成！已保存至 scratch/debug_universal/")
