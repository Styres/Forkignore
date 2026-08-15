import cv2
import numpy as np
import os

img6 = cv2.imread("bug_6.jpg")
img7 = cv2.imread("bug_7.jpg")
img5 = cv2.imread("bug_5.jpg")

print(f"bug_5 shape: {img5.shape}")
print(f"bug_6 shape: {img6.shape}")
print(f"bug_7 shape: {img7.shape}")

# Let's check if bug_6 and bug_7 are identical or different
if img6.shape == img7.shape:
    diff = np.max(np.abs(img6.astype(np.int32) - img7.astype(np.int32)))
    print(f"Pixel difference between bug_6 and bug_7: {diff}")
else:
    print("bug_6 and bug_7 have different shapes!")
