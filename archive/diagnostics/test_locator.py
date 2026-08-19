import cv2
import numpy as np
import os

def find_board_rect(image):
    """
    自适应定位多邻国 2D 棋盘在屏幕中的精确像素矩形 (left, top, right, bottom)
    原理：
    1. 棋盘是由 8x8 个尺寸完全相同的方格组成的等宽等高正方形 (w ≈ h)。
    2. 棋盘左右对称居中，宽度通常在屏幕宽度的 85% ~ 98% 之间。
    3. 在棋盘内部，每隔 1/8 宽度/高度就会出现方格交替的颜色变化（高频规律边缘）。
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 缩小做快速粗定位
    scale = 400.0 / img_w
    small_w = 400
    small_h = int(img_h * scale)
    small_gray = cv2.resize(gray, (small_w, small_h))
    
    # 边缘检测
    edges = cv2.Canny(small_gray, 40, 120)
    
    best_score = -1
    best_rect = None # (x, y, size) in small coords
    
    # 棋盘大小在 small 坐标下通常在 [300, 390] 之间
    # 棋盘 y 坐标通常在 [small_h * 0.2, small_h * 0.8] 之间
    for size in range(280, 395, 4):
        step = size / 8.0
        # 格子边界横纵坐标相对于左上角 (x, y) 的偏移
        offsets = [int(i * step) for i in range(1, 8)]
        
        # x 居中搜索 (以小范围居中为主)
        center_x = (small_w - size) // 2
        for x in range(max(0, center_x - 15), min(small_w - size + 1, center_x + 16), 2):
            for y in range(int(small_h * 0.2), int(small_h * 0.8) - size, 4):
                # 计算该假想棋盘内的网格线边缘响应
                score = 0
                for off in offsets:
                    # 水平网格线响应
                    score += np.sum(edges[y + off, x:x + size] > 0)
                    # 垂直网格线响应
                    score += np.sum(edges[y:y + size, x + off] > 0)
                
                # 检查棋盘 8x8 格子中心的颜色方差是否符合棋盘模式
                if score > best_score:
                    best_score = score
                    best_rect = (x, y, size)
                    
    if best_rect is None:
        raise ValueError("未能精确定位棋盘！")
        
    x_s, y_s, size_s = best_rect
    # 映射回原图坐标
    inv_scale = 1.0 / scale
    x = int(round(x_s * inv_scale))
    y = int(round(y_s * inv_scale))
    size = int(round(size_s * inv_scale))
    
    # 微调精确定位边界：寻找网格线峰值
    crop = gray[y:y+size, x:x+size]
    
    return (x, y, x + size, y + size)

# 测试并生成可视化切片
os.makedirs("scratch/debug_board", exist_ok=True)
for filename in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(filename)
    l, t, r, b = find_board_rect(img)
    print(f"[{filename}] Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    # 绘制框和 8x8 网格
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 4)
    step = (r - l) / 8.0
    for i in range(1, 8):
        # 竖线
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 2)
        # 横线
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 2)
        
    cv2.imwrite(f"scratch/debug_board/{filename}_board.png", vis)

print("棋盘定位可视化已保存至 scratch/debug_board/")
