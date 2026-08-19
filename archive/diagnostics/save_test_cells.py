import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
from tools.test_full_pipeline_v2 import fast_sat_locate_board

for img_name in ["duolingo_test_1.jfif", "duolingo_test_2.jfif"]:
    if not os.path.exists(img_name): continue
    img = cv2.imread(img_name)
    l, t, r, b = fast_sat_locate_board(img)
    step = (r - l) / 8.0
    
    out_dir = f"scratch/{img_name}_cells"
    os.makedirs(out_dir, exist_ok=True)
    for row in range(8):
        for col in range(8):
            cx1 = int(l + col * step)
            cy1 = int(t + row * step)
            cx2 = int(l + (col + 1) * step)
            cy2 = int(t + (row + 1) * step)
            cell_crop = img[cy1:cy2, cx1:cx2]
            cv2.imwrite(f"{out_dir}/r{row}_c{col}.png", cell_crop)

print("Saved cells for test 1 and test 2!")
