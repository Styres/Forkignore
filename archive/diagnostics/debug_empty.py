import cv2
import numpy as np

for r, c in [(2, 2), (3, 3), (4, 4), (6, 0), (6, 4), (7, 4)]:
    p = f"scratch/all_cells/img3/r{r}_c{c}.png"
    img = cv2.imread(p)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # 提取中心 20%~80% 区域（绝对远离边缘网格线）
    ch, cw = gray.shape
    center_roi = gray[int(ch*0.25):int(ch*0.75), int(cw*0.25):int(cw*0.75)]
    
    # 计算内部标准差与边缘能量
    gx = cv2.Sobel(center_roi, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(center_roi, cv2.CV_32F, 0, 1, ksize=3)
    grad_mag = np.mean(np.sqrt(gx**2 + gy**2))
    std_val = np.std(center_roi)
    
    print(f"img3 (r{r}, c{c}): center std = {std_val:.2f}, grad_mag = {grad_mag:.2f}")
