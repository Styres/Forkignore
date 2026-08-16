# bug_19 定位器双峰验证: 同一图像上 T=731(Kotlin 离线面板) 与 T=1043(Python argmax) 两处候选框的梳状响应分对比
# 若两处分数接近，说明定位器响应存在平台/双峰，Kotlin/Python argmax 平局裁决差异即可解释 superbug
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import cv2
import numpy as np

def score_at(image, x_full, y_full, size_full):
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    scale = 400.0 / img_w
    s_w = 400
    s_h = int(img_h * scale)
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    gx = cv2.Sobel(s_gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(s_gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.abs(gx) + np.abs(gy)
    sat_gray = cv2.integral(s_gray)
    sat_mag = cv2.integral(mag)

    x, y, size = x_full * scale, y_full * scale, size_full * scale
    step = size / 8.0

    def rect_sum(sat, x1, y1, x2, y2):
        x1, y1 = max(0, int(x1)), max(0, int(y1))
        x2, y2 = min(s_w, int(x2)), min(s_h, int(y2))
        if x2 <= x1 or y2 <= y1: return 0.0
        return sat[y2, x2] - sat[y1, x2] - sat[y2, x1] + sat[y1, x1]

    def rect_mean(sat, x1, y1, x2, y2):
        w = max(1, int(x2) - int(x1)); h = max(1, int(y2) - int(y1))
        return rect_sum(sat, x1, y1, x2, y2) / (w * h)

    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0

    edge_score = 0.0
    for i in range(1, 8):
        ly = int(y + i * step); lx = int(x + i * step)
        edge_score += rect_mean(sat_mag, x, ly - 1, x + size, ly + 2)
        edge_score += rect_mean(sat_mag, lx - 1, y, lx + 2, y + size)
    grid_means = np.zeros((8, 8), dtype=np.float32)
    corner_w = max(1, int(step * 0.18))
    for r in range(8):
        cy1 = y + r * step; cy2 = cy1 + step
        for c in range(8):
            cx1 = x + c * step; cx2 = cx1 + step
            m1 = rect_mean(sat_gray, cx1, cy1, cx1 + corner_w, cy1 + corner_w)
            m2 = rect_mean(sat_gray, cx2 - corner_w, cy1, cx2, cy1 + corner_w)
            m3 = rect_mean(sat_gray, cx1, cy2 - corner_w, cx1 + corner_w, cy2)
            m4 = rect_mean(sat_gray, cx2 - corner_w, cy2 - corner_w, cx2, cy2)
            grid_means[r, c] = (m1 + m2 + m3 + m4) * 0.25
    g_norm = grid_means - np.mean(grid_means)
    corr = abs(np.sum(g_norm * pattern))
    bottom_ratio = (y + size) / s_h
    pos_prior = 1.0 if 0.70 <= bottom_ratio <= 0.98 else 0.35
    return (corr * 2.0 + edge_score * 0.4) * pos_prior, corr, edge_score, pos_prior

img = cv2.imread("bug_19.jpg")
h, w = img.shape[:2]
print(f"bug_19.jpg 尺寸: {w}x{h}")

# Kotlin 离线面板框 [L=22,T=731,R=1254,B=1963] -> size=1232
# Python argmax 框  [L=16,T=1043,R=1248,B=2275] -> size=1232
for label, (x, y) in [("Kotlin面板框 T=731", (22, 731)), ("Python argmax T=1043", (16, 1043))]:
    score, corr, edge, prior = score_at(img, x, y, 1232)
    print(f"{label}: score={score:.1f} (corr={corr:.1f}, edge={edge:.1f}, prior={prior})")

# 垂直扫描: 固定 x=16 size=1232，y 从 500 到 h-1232 步长 20，找所有高分峰
print("\n垂直响应扫描 (score > 400 的 y):")
y_max_full = h - 1232
y = 500
while y <= y_max_full:
    score, corr, edge, prior = score_at(img, 16, y, 1232)
    if score > 400:
        print(f"  y={y}: score={score:.1f} (corr={corr:.1f}, prior={prior})")
    y += 20
