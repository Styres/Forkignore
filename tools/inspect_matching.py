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

print("Rank 1 (row 7) cell matches in duolingo_2.jpg:")
for col in range(8):
    cx1 = int(l + col * step)
    cy1 = int(t + 7 * step)
    cx2 = int(l + (col + 1) * step)
    cy2 = int(t + 8 * step)
    cell_crop = img[cy1:cy2, cx1:cx2]
    
    feat = classifier._extract_feature(cell_crop)
    scores = {}
    for cls_name, t_list in classifier.templates.items():
        sims = [float(np.sum(feat['mag_norm'] * t['mag_norm'])) for t in t_list]
        scores[cls_name] = max(sims)
    
    sorted_scores = sorted(scores.items(), key=lambda x: x[1], reverse=True)
    print(f"Col {col} ({chr(ord('a')+col)}1): {sorted_scores[:3]}")
