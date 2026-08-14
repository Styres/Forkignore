import cv2
import numpy as np
from comb_locator import comb_filter_locate_board
from silhouette_classifier import RobustSilhouetteClassifier

classifier = RobustSilhouetteClassifier()

for name in ["duolingo_test_1.jfif", "duolingo_test_2.jfif"]:
    print(f"\n==================== Testing {name} with Silhouette Classifier ====================")
    img = cv2.imread(name)
    l, t, r, b = comb_filter_locate_board(img)
    step = (r - l) / 8.0
    
    cells = []
    for row in range(8):
        row_cells = []
        for col in range(8):
            x1 = int(round(l + col * step))
            y1 = int(round(t + row * step))
            x2 = int(round(l + (col + 1) * step))
            y2 = int(round(t + (row + 1) * step))
            row_cells.append(img[y1:y2, x1:x2])
        cells.append(row_cells)
        
    board = classifier.classify_board(cells)
    for r in board:
        print(" ".join(r))
