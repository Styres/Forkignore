import cv2

for b_img in ["bug_5.jpg", "bug_6.jpg", "bug_7.jpg"]:
    img = cv2.imread(b_img)
    # let's save the top half (which contains the MainActivity text view or game view)
    h, w = img.shape[:2]
    top_crop = img[:int(h * 0.45), :]
    cv2.imwrite(f"scratch/top_{b_img}", top_crop)
print("Saved top crops")
