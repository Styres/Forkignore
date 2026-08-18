import cv2
import numpy as np
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from grid_calibrate import locate_board, load_image

def fast_sat_locate_board(image, return_score=False, top_n=3):
    """
    与 Kotlin ChessLocator 保持 1:1 对偶一致的格线精标定定位器
    """
    res = locate_board(image, top_n=top_n)
    if res is None:
        return (0, 0, 0, 0) if not return_score else (0, 0, 0, 0, 0.0)
    x0, y0, size = res['rect']
    score = float(res['candidates'][0][0][1]) if 'candidates' in res and res['candidates'] else 0.0
    if return_score:
        return x0, y0, x0 + size, y0 + size, score
    return x0, y0, x0 + size, y0 + size

if __name__ == '__main__':
    images = [
        "duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg",
        "duolingo_test_1.jfif", "duolingo_test_2.jfif", "Screenshot_20260817_162715.jpg"
    ]
    for img_name in images:
        if not os.path.exists(img_name):
            continue
        img = load_image(img_name)
        res = locate_board(img, top_n=3)
        if res is not None:
            x0, y0, size = res['rect']
            print(f"[{img_name}] Rect=({x0}, {y0}, {x0+size}, {y0+size}), Size={size}x{size}, Conf={res['confidence']}, Resid={res['residual']:.2f}px")

