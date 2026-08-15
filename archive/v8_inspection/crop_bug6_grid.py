import sys
import os
import cv2
import numpy as np

img6 = cv2.imread("bug_6.jpg")
h, w = img6.shape[:2]

l, t, r, b = 16, 1043, 1248, 2275
step = (r - l) / 8.0

os.makedirs("scratch/bug6_grid", exist_ok=True)
for row in range(8):
    for col in range(8):
        cx1 = int(round(l + col * step))
        cy1 = int(round(t + row * step))
        cx2 = int(round(l + (col + 1) * step))
        cy2 = int(round(t + (row + 1) * step))
        cell = img6[cy1:cy2, cx1:cx2]
        cv2.imwrite(f"scratch/bug6_grid/r{row}_c{col}.png", cell)

print(f"Cropped 64 squares for bug_6.jpg at standard board bounds [{l}, {t}, {r}, {b}]")
