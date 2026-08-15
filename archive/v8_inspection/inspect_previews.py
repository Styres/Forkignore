import cv2
for name in ["bug_5.jpg", "bug_6.jpg", "bug_7.jpg"]:
    img = cv2.imread(name)
    # Save a downscaled version so we can understand what screen it is
    h, w = img.shape[:2]
    small = cv2.resize(img, (w // 2, h // 2))
    cv2.imwrite(f"scratch/preview_{name}", small)
    # Also crop top diagnostic card if this is MainActivity
    diag_crop = img[:800, :]
    cv2.imwrite(f"scratch/diag_{name}", diag_crop)
print("Saved preview and diag images")
