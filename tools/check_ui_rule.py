import cv2
import numpy as np

# 我们直接测量 3 张图上棋盘的真实像素边界：
# 1. duolingo_1.jpeg: 750 x 1334
# 2. duolingo_2.jpg: 1179 x 2004
# 3. duolingo_3.jpg: 1260 x 2800

for filename in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(filename)
    h, w = img.shape[:2]
    
    # 让我们直接通过自底向上扫描棋盘底部边界线（棋盘圆角外框下边缘）：
    # 棋盘是一个独立容器卡片，在底部有明显的水平分界线
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # 观察底部 60% ~ 98% 区域
    bottom_strip = gray[int(h*0.7):int(h*0.99), :]
    
    print(f"File {filename}: W={w}, H={h}")
