import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell

img = cv2.imread("duolingo_test_2.jfif")
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

print(f"duolingo_test_2.jfif shape: {img.shape}, board: [{l},{t},{r},{b}]")
for row in range(8):
    for col in range(8):
        cx1 = int(round(l + col * step))
        cy1 = int(round(t + row * step))
        cx2 = int(round(l + (col + 1) * step))
        cy2 = int(round(t + (row + 1) * step))
        cell = img[cy1:cy2, cx1:cx2]
        f = extract_features_from_cell(cell)
        if f['center_std'] >= 6.0 and f['grad_mean'] >= 8.0:
            print(f"Cell ({row},{col}): center_mean={f['center_mean']:.1f}, std={f['center_std']:.1f}, grad={f['grad_mean']:.1f}")
