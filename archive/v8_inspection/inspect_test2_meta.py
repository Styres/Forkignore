import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board

img = cv2.imread("duolingo_test_2.jfif")
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

print(f"duolingo_test_2: shape={img.shape}, board=[{l},{t},{r},{b}]")
