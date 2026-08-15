import cv2
import numpy as np

for name in ["bug_5.jpg", "bug_6.jpg", "bug_7.jpg"]:
    img = cv2.imread(name)
    h, w = img.shape[:2]
    # Let's crop the text area in MainActivity (top 40% of screen)
    text_area = img[int(h * 0.05):int(h * 0.35), :]
    cv2.imwrite(f"scratch/text_area_{name}", text_area)
print("Saved text areas")
