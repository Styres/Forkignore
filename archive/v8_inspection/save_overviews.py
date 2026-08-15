import cv2
import os

img6 = cv2.imread("bug_6.jpg")
img7 = cv2.imread("bug_7.jpg")
img5 = cv2.imread("bug_5.jpg")

os.makedirs("scratch/crops_bug6", exist_ok=True)
os.makedirs("scratch/crops_bug7", exist_ok=True)

# Save resized overview
cv2.imwrite("scratch/overview_bug5.png", cv2.resize(img5, (315, 700)))
cv2.imwrite("scratch/overview_bug6.png", cv2.resize(img6, (315, 700)))
cv2.imwrite("scratch/overview_bug7.png", cv2.resize(img7, (315, 700)))

print("Overviews saved. Let's analyze bug_6 in detail.")
