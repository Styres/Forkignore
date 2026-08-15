import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np

img6 = cv2.imread("bug_6.jpg")
h, w = img6.shape[:2]

# Let's crop the center chessboard area of bug_6.jpg
# Duolingo chessboards typically occupy the center of the screen
# Let's save slices around the board area
board_crop = img6[int(h * 0.35):int(h * 0.85), :]
cv2.imwrite("scratch/bug6_board_crop.png", board_crop)
print(f"bug_6 shape: {img6.shape}, board crop saved to scratch/bug6_board_crop.png")
