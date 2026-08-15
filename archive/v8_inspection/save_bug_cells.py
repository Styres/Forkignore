import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
from tools.test_full_pipeline_v2 import fast_sat_locate_board

for b_img in ["bug_3.jpg", "bug_4.jpg"]:
    img = cv2.imread(b_img)
    l, t, r, b = fast_sat_locate_board(img)
    step = (r - l) / 8.0
    out_dir = f"scratch/{b_img}_cells"
    os.makedirs(out_dir, exist_ok=True)
    for row in range(8):
        for col in range(8):
            cx1 = int(round(l + col * step))
            cy1 = int(round(t + row * step))
            cx2 = int(round(l + (col + 1) * step))
            cy2 = int(round(t + (row + 1) * step))
            crop = img[cy1:cy2, cx1:cx2]
            cv2.imwrite(f"{out_dir}/r{row}_c{col}.png", crop)
print("Saved bug_3 and bug_4 cells for manual ground truth verification!")
