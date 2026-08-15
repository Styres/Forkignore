import cv2
import numpy as np

img8 = cv2.imread("bug_8.jpg")
h, w = img8.shape[:2]

# Let's save small crops of lines in bug_8.jpg
# Top card:
# Lines:
# 1. 【离线诊断结果】
# 2. 棋盘坐标: [L=16, T=1043, R=1248, B=2275]
# 3. 视角方向: 执白 (White)
# 4. 局面 FEN: ...
# 5. 完整 FEN: ...
# 6. -----------------------------
# 7. 推荐走法: ...
# 8. 局势评估分: +0.00
# 9. 搜索深度: 0 层 [兜底生成器]
# 10. -----------------------------
# 11. 【引擎就绪 (路径1 (nativeLibDir))】
# 12. 路径1 [nativeLibDir]: exists=true, canExec=true
# 13. 进程启动: 成功 (路径1 (nativeLibDir))
# 14. 握手 [uciok]: 成功 (14ms)
# 15. 握手 [readyok]: 成功 (8ms)
# 16. 总耗时: 28ms | 真实 Stockfish 准备就绪

# Let's inspect the exact lines!
for i in range(10):
    y1 = int(h * (0.07 + i * 0.03))
    y2 = int(h * (0.07 + (i + 1) * 0.03))
    line_crop = img8[y1:y2, :]
    cv2.imwrite(f"scratch/line_{i}.png", line_crop)
print("Saved 10 line crops from bug_8.jpg")
