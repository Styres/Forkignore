import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board

img = cv2.imread("duolingo_2.jpg")
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

print("Row 1 (pawns) and Row 3 (pawn move) in duolingo_2.jpg:")
for col in range(8):
    # Row 1
    cx1 = int(l + col * step)
    cy1 = int(t + 1 * step)
    cx2 = int(l + (col + 1) * step)
    cy2 = int(t + 2 * step)
    cell1 = img[cy1:cy2, cx1:cx2]
    
    # Row 3
    cy3_1 = int(t + 3 * step)
    cy3_2 = int(t + 4 * step)
    cell3 = img[cy3_1:cy3_2, cx1:cx2]
    
    gray1 = cv2.cvtColor(cell1, cv2.COLOR_BGR2GRAY)
    gray3 = cv2.cvtColor(cell3, cv2.COLOR_BGR2GRAY)
    
    print(f"Col {col} ({chr(ord('a')+col)}): Row 1 std={np.std(gray1):.1f}, Row 3 std={np.std(gray3):.1f}")
