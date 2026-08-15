import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.robust_classifier import UltraRobustDuolingoClassifier

img = cv2.imread("duolingo_2.jpg")
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

classifier = UltraRobustDuolingoClassifier()
cells = []
for row in range(8):
    row_cells = []
    for col in range(8):
        cx1 = int(l + col * step)
        cy1 = int(t + row * step)
        cx2 = int(l + (col + 1) * step)
        cy2 = int(t + (row + 1) * step)
        cell_crop = img[cy1:cy2, cx1:cx2]
        row_cells.append(cell_crop)
    cells.append(row_cells)

raw_board = classifier.classify_board(cells)
print("RAW BOARD before sanitize:")
for r, row in enumerate(raw_board):
    print(f"Row {r}: {' '.join(row)}")
