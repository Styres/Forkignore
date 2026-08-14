import cv2
import numpy as np

for name in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(name)
    if img is not None:
        h, w, c = img.shape
        print(f"{name}: shape = {w}x{h}, aspect = {h/w:.2f}")
