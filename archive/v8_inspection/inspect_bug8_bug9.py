import sys
import os
import cv2

for b_img in ["bug_8.jpg", "bug_9.jpg"]:
    if not os.path.exists(b_img):
        print(f"{b_img} does not exist!")
        continue
    img = cv2.imread(b_img)
    h, w = img.shape[:2]
    print(f"{b_img} shape: {img.shape}")
    
    # Save preview
    cv2.imwrite(f"scratch/preview_{b_img}", cv2.resize(img, (w // 2, h // 2)))
    
    # In bug_8.jpg, crop top 50% where MainActivity text is
    if "bug_8" in b_img:
        text_crop = img[:int(h * 0.5), :]
        cv2.imwrite(f"scratch/diag_{b_img}", text_crop)

print("Saved preview and diagnostic crops for bug_8 and bug_9")
