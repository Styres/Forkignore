import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
import glob
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell

images = [
    "duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg",
    "duolingo_test_1.jfif", "bug_3.jpg"
]

print(f"{'Image':<22} | {'Grad Threshold 8':<18} | {'Grad Threshold 22':<18}")
print("-" * 65)

for img_name in images:
    if not os.path.exists(img_name): continue
    img = cv2.imread(img_name)
    l, t, r, b = fast_sat_locate_board(img)
    step = (r - l) / 8.0
    
    occ_8 = 0
    occ_22 = 0
    
    for row in range(8):
        for col in range(8):
            cx1 = int(round(l + col * step))
            cy1 = int(round(t + row * step))
            cx2 = int(round(l + (col + 1) * step))
            cy2 = int(round(t + (row + 1) * step))
            cell = img[cy1:cy2, cx1:cx2]
            f = extract_features_from_cell(cell)
            
            if f['center_std'] >= 6.0 and f['grad_mean'] >= 8.0:
                occ_8 += 1
            if f['center_std'] >= 6.0 and f['grad_mean'] >= 22.0:
                occ_22 += 1
                
    print(f"{img_name:<22} | {occ_8:<18} | {occ_22:<18}")
