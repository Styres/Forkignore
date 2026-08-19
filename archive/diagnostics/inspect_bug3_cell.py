import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell

img = cv2.imread("bug_3.jpg")
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

print(f"bug_3 board: [{l},{t},{r},{b}], step={step:.1f}")

# Row 2 col 6 (above Bishop) vs Row 3 col 6 (Bishop)
c_r2 = img[int(t + 2*step):int(t + 3*step), int(l + 6*step):int(l + 7*step)]
c_r3 = img[int(t + 3*step):int(t + 4*step), int(l + 6*step):int(l + 7*step)]

f2 = extract_features_from_cell(c_r2)
f3 = extract_features_from_cell(c_r3)

print(f"Row 2 Col 6 (empty with head overlap): std={f2['center_std']:.1f}, grad={f2['grad_mean']:.1f}")
print(f"Row 3 Col 6 (Bishop):                  std={f3['center_std']:.1f}, grad={f3['grad_mean']:.1f}")
