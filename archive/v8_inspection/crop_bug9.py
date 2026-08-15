import cv2

img9 = cv2.imread("bug_9.jpg")
h, w = img9.shape[:2]

# Save cropped board and capsule from bug_9.jpg
cv2.imwrite("scratch/bug9_top.png", img9[:int(h * 0.4), :])
cv2.imwrite("scratch/bug9_board.png", img9[int(h * 0.35):int(h * 0.85), :])
print("Saved bug_9 crops")
