import cv2
import numpy as np

img9 = cv2.imread("bug_9.jpg")
h, w = img9.shape[:2]

# Let's crop around the capsule area (top 15% to 35%)
capsule_area = img9[int(h * 0.15):int(h * 0.38), :]
cv2.imwrite("scratch/bug9_capsule.png", capsule_area)
print("Saved bug_9 capsule")
