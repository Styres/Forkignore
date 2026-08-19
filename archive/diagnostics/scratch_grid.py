import cv2
import numpy as np

def locate_board_by_grid(img_path):
    img = cv2.imread(img_path)
    h, w = img.shape[:2]
    
    # 棋盘通常在屏幕中央偏下，宽度占屏幕宽度的 90%~100%
    # 转换为灰度图
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # 寻找边缘
    edges = cv2.Canny(gray, 30, 100)
    
    # 沿水平方向投影边缘点数
    row_proj = np.sum(edges, axis=1)
    
    # 沿垂直方向投影边缘点数
    col_proj = np.sum(edges, axis=0)
    
    # 寻找高频方格变化区域
    print(f"--- Analyzing {img_path} ({w}x{h}) ---")
    
    # 让我们可视化或者保存裁剪的棋盘区域
    # 多邻国棋盘是正方形，每边有 8 个方格。
    # 我们可以通过寻找 8 个等宽等高的交替方格区域来精确定位。
    return img

for name in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    locate_board_by_grid(name)
