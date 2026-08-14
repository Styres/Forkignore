import cv2
import numpy as np

# 我们对 img1, img2, img3 里的每种棋子计算其特征值：
# 1. 十字响应 (cross_score)
# 2. 左右不对称度 (asymmetry)
# 3. 顶部锯齿度/开叉度 (top_crown)
# 4. 宽高比与高度 (height_ratio)

def analyze_piece_anatomy(cell_img):
    resized = cv2.resize(cell_img, (60, 60))
    gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
    
    # 提取纯中心区域
    center = gray[12:48, 12:48]
    
    # 边缘
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.sqrt(gx**2 + gy**2)[10:50, 10:50]
    
    # 十字响应 (王 K)
    # 中心 15:25 区域
    cross_kernel = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=np.float32)
    cross_resp = cv2.filter2D(mag, -1, cross_kernel)
    cross_max = np.max(cross_resp[10:30, 10:30])
    
    # 左右不对称度 (马 N)
    left_side = mag[:, :20]
    right_side = cv2.flip(mag[:, 20:], 1)[:, :20]
    asym = np.mean(np.abs(left_side - right_side))
    
    # 顶部 1/3 宽度分布 (后 Q vs 车 R vs 象 B)
    top_strip = mag[5:15, :]
    top_profile = np.sum(top_strip, axis=0)
    
    return {
        'cross': cross_max,
        'asym': asym,
        'mean_mag': np.mean(mag)
    }

for cls_name, p in [('King', 'scratch/all_cells/img1/r7_c4.png'),
                    ('Queen', 'scratch/all_cells/img1/r7_c3.png'),
                    ('Knight', 'scratch/all_cells/img1/r7_c1.png'),
                    ('Rook', 'scratch/all_cells/img1/r7_c0.png'),
                    ('Bishop', 'scratch/all_cells/img1/r7_c2.png'),
                    ('Pawn', 'scratch/all_cells/img1/r6_c0.png')]:
    img = cv2.imread(p)
    info = analyze_piece_anatomy(img)
    print(f"[{cls_name:6s}] cross: {info['cross']:.1f}, asym: {info['asym']:.1f}, mag: {info['mean_mag']:.1f}")
