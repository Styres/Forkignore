import cv2
import numpy as np

img8 = cv2.imread("bug_8.jpg")
h, w = img8.shape[:2]

# Let's crop the text area in detail (top 45%)
text_area = img8[int(h * 0.05):int(h * 0.42), :]
cv2.imwrite("scratch/bug8_full_text.png", text_area)

# Let's save 3 vertical slices of the text area
th = text_area.shape[0]
for i in range(3):
    slice_img = text_area[int(th * i / 3.0):int(th * (i + 1) / 3.0), :]
    cv2.imwrite(f"scratch/bug8_text_slice_{i}.png", slice_img)

print("Saved detailed text slices of bug_8.jpg")
