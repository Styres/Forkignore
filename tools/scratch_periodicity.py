import cv2
import numpy as np

# 我们分析真实棋盘与人物文字区域在水平/垂直周期性投影上的特征
for name in ["duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif", "duolingo_1.jpeg"]:
    img = cv2.imread(name)
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # 计算垂直 Sobel (水平线) 和 水平 Sobel (垂直线)
    gx = np.abs(cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3))
    gy = np.abs(cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3))
    
    # 真正的棋盘在 X 方向跨度通常是图像的主要居中区域
    print(f"File {name}: {w}x{h}")
