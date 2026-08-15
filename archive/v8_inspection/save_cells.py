import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
from tools.test_full_pipeline_v2 import fast_sat_locate_board

img = cv2.imread("duolingo_2.jpg")
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

os.makedirs("scratch/duo2_cells", exist_ok=True)
for row in [0, 7]:
    for col in range(8):
        cx1 = int(l + col * step)
        cy1 = int(t + row * step)
        cx2 = int(l + (col + 1) * step)
        cy2 = int(t + (row + 1) * step)
        cell_crop = img[cy1:cy2, cx1:cx2]
        cv2.imwrite(f"scratch/duo2_cells/r{row}_c{col}.png", cell_crop)

print("Saved cells to scratch/duo2_cells/")
