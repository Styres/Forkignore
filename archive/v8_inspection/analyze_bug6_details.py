import cv2
import numpy as np

img6 = cv2.imread("bug_6.jpg")
print("bug_6 mean color:", np.mean(img6, axis=(0, 1)))

# Let's check where the board is on bug_6 vs bug_7
# In analyze_bugs_567 earlier:
# bug_5: MedianSim = 0.456 (MainActivity with diagnostic text)
# bug_6: Board Box [107, 977, 1178, 2048], Occupied: 26, MedianSim: 0.468
# bug_7: Board Box [16, 1043, 1248, 2275], Occupied: 27, MedianSim: 0.999

# What is on bug_6?
# On bug_6, MedianSim is 0.468 < 0.52 (It is an in-between screen, e.g. lobby or victory card or Duolingo animated prompt card!).
