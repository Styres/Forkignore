import cv2
import numpy as np

img = cv2.imread("bug_4.jpg")
# let's crop c3 and h1 and save them as diagnostic png
# Board Box for bug_4.jpg was [16, 1043, 1248, 2275]
step = (1248 - 16) / 8.0
c3 = img[int(1043 + 5*step):int(1043 + 6*step), int(16 + 2*step):int(16 + 3*step)]
h1 = img[int(1043 + 7*step):int(1043 + 8*step), int(16 + 7*step):int(16 + 8*step)]
cv2.imwrite("scratch/bug4_c3.png", c3)
cv2.imwrite("scratch/bug4_h1.png", h1)
print("Saved bug4_c3.png and bug4_h1.png")
