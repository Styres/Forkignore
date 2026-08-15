import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np

def extract_features_from_cell(cell_img):
    """
    自适应质心对齐 + 滑动窗口钳制 + 分数级双区域特征提取 (36x36 ROI)
    """
    resized = cv2.resize(cell_img, (48, 48), interpolation=cv2.INTER_LINEAR)
    gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY) if len(resized.shape) == 3 else resized.copy()
    
    # 1. 占用门控统计 (基于 30x30 中心区)
    center_roi = gray[9:39, 9:39].astype(np.float32)
    center_std = float(np.std(center_roi))
    center_mean = float(np.mean(center_roi))
    
    # 全图 Sobel 梯度
    sobelx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    sobely = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag_full = np.sqrt(sobelx**2 + sobely**2)
    grad_mean = float(np.mean(mag_full[9:39, 9:39]))
    
    # 2. 4 角中值背景差分探测前景质心
    corners = [
        gray[0:3, 0:3], gray[0:3, 45:48],
        gray[45:48, 0:3], gray[45:48, 45:48]
    ]
    bg_val = float(np.median([np.median(c) for c in corners]))
    diff = np.abs(gray.astype(np.float32) - bg_val)
    mask = diff > 15.0
    
    if np.any(mask):
        y_idxs, x_idxs = np.where(mask)
        cy = float(np.mean(y_idxs))
        cx = float(np.mean(x_idxs))
    else:
        cy = 24.0
        cx = 24.0
        
    # 3. 滑动窗口原点钳制 (保证 ROI 恒为 36x36)
    x0 = int(np.clip(round(cx - 18), 0, 12))
    y0 = int(np.clip(round(cy - 18), 0, 12))
    
    # 4. 身体特征: 30x30 (从 36x36 中居中取 30x30)
    body_mag = mag_full[y0+3:y0+33, x0+3:x0+33].flatten()
    body_norm = np.linalg.norm(body_mag) + 1e-5
    f_body = (body_mag / body_norm).astype(np.float32)
    
    # 5. 头部特征: 10x30 (从 30x30 身体中取前 10 行)
    head_mag = mag_full[y0+3:y0+13, x0+3:x0+33].flatten()
    head_norm = np.linalg.norm(head_mag) + 1e-5
    f_head = (head_mag / head_norm).astype(np.float32)
    
    return {
        'f_body': f_body,
        'f_head': f_head,
        'center_std': center_std,
        'center_mean': center_mean,
        'grad_mean': grad_mean,
        'cy': cy,
        'cx': cx,
        'x0': x0,
        'y0': y0
    }

print("extract_features_from_cell pipeline defined successfully!")
