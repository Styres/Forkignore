import cv2
import numpy as np

img6 = cv2.imread("bug_6.jpg")
h, w = img6.shape[:2]

# Let's check what UI or board is on bug_6.jpg
# Let's save 4 slices from top to bottom
for i in range(4):
    slice_img = img6[int(h * i / 4.0):int(h * (i + 1) / 4.0), :]
    cv2.imwrite(f"scratch/bug6_slice_{i}.png", slice_img)
print("Saved 4 slices of bug_6.jpg")
