import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board

img6 = cv2.imread("bug_6.jpg")
box = fast_sat_locate_board(img6)
print("Auto detected board box for bug_6.jpg:", box)
