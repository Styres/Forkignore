import cv2
import numpy as np
import os
from test_locator_v3 import find_bottom_edge_and_board
from robust_classifier import UltraRobustDuolingoClassifier
from engine_evaluator import evaluate_fen_cloud

for name in ["duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"]:
    img = cv2.imread(name)
    if img is None:
        print(f"Error loading {name}")
        continue
    h, w = img.shape[:2]
    print(f"Image {name}: {w}x{h}, aspect={h/w:.2f}")
