import cv2
import numpy as np
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from grid_calibrate import locate_board, load_image

def comb_filter_locate_board(image):
    """
    基于 8 周期梳状谐振滤波器 + 格线直测精标定的超高鲁棒性棋盘定位算法
    """
    res = locate_board(image, top_n=3)
    if res is None:
        return (0, 0, 0, 0)
    x0, y0, size = res['rect']
    return (x0, y0, x0 + size, y0 + size)

if __name__ == '__main__':
    # 测试全量 6 张图
    os.makedirs("scratch/debug_comb", exist_ok=True)
    all_images = [
        "duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg",
        "duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"
    ]

    for filename in all_images:
        if not os.path.exists(filename):
            continue
        img = load_image(filename)
        l, t, r, b = comb_filter_locate_board(img)
        print(f"[{filename}] Comb Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
        
        vis = img.copy()
        cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 2)
        step = (r - l) / 8.0
        for i in range(1, 8):
            cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 1)
            cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 1)
        cv2.imwrite(f"scratch/debug_comb/{filename}_board.png", vis)

    print("全部测试图梳状滤波定位完成！已保存至 scratch/debug_comb/")

