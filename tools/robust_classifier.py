import cv2
import numpy as np
import os

class UltraRobustDuolingoClassifier:
    """
    终极多模态多邻国 2D 棋子分类器
    纯净数学度量：
    1. 严格 18%~82% 核心 ROI
    2. 多模态归一化梯度场 + 归一化互相关 (NCC)
    3. 自适应 2-Means 聚类区分白黑阵营
    """
    def __init__(self):
        # 收集来自三套主题环境中的真实正样本
        self.class_samples = {
            'P': [
                "scratch/all_cells/img1/r6_c0.png", "scratch/all_cells/img1/r6_c1.png", "scratch/all_cells/img1/r6_c4.png", "scratch/all_cells/img1/r6_c6.png",
                "scratch/all_cells/img1/r1_c0.png", "scratch/all_cells/img1/r1_c3.png", "scratch/all_cells/img1/r1_c6.png", "scratch/all_cells/img1/r1_c7.png",
                "scratch/all_cells/img2/r1_c0.png", "scratch/all_cells/img2/r1_c1.png", "scratch/all_cells/img2/r1_c5.png", "scratch/all_cells/img2/r1_c6.png", "scratch/all_cells/img2/r3_c2.png",
                "scratch/all_cells/img2/r6_c0.png", "scratch/all_cells/img2/r6_c4.png", "scratch/all_cells/img2/r6_c6.png", "scratch/all_cells/img2/r6_c7.png",
                "scratch/all_cells/img3/r6_c0.png", "scratch/all_cells/img3/r6_c3.png", "scratch/all_cells/img3/r6_c6.png", "scratch/all_cells/img3/r6_c7.png",
                "scratch/all_cells/img3/r1_c0.png", "scratch/all_cells/img3/r1_c4.png", "scratch/all_cells/img3/r1_c6.png", "scratch/all_cells/img3/r1_c7.png"
            ],
            'R': [
                "scratch/all_cells/img1/r7_c0.png", "scratch/all_cells/img1/r7_c7.png",
                "scratch/all_cells/img1/r0_c0.png", "scratch/all_cells/img1/r0_c7.png",
                "scratch/all_cells/img2/r0_c0.png", "scratch/all_cells/img2/r0_c7.png",
                "scratch/all_cells/img2/r7_c0.png", "scratch/all_cells/img2/r7_c7.png",
                "scratch/all_cells/img3/r7_c0.png", "scratch/all_cells/img3/r7_c7.png",
                "scratch/all_cells/img3/r0_c0.png", "scratch/all_cells/img3/r0_c7.png"
            ],
            'N': [
                "scratch/all_cells/img1/r7_c1.png", "scratch/all_cells/img1/r7_c6.png",
                "scratch/all_cells/img1/r0_c1.png", "scratch/all_cells/img1/r0_c6.png",
                "scratch/all_cells/img2/r0_c1.png", "scratch/all_cells/img2/r0_c6.png",
                "scratch/all_cells/img2/r7_c1.png", "scratch/all_cells/img2/r7_c6.png",
                "scratch/all_cells/img3/r7_c1.png", "scratch/all_cells/img3/r7_c6.png",
                "scratch/all_cells/img3/r0_c1.png", "scratch/all_cells/img3/r0_c6.png"
            ],
            'B': [
                "scratch/all_cells/img1/r7_c2.png", "scratch/all_cells/img1/r7_c5.png",
                "scratch/all_cells/img1/r0_c2.png", "scratch/all_cells/img1/r0_c5.png",
                "scratch/all_cells/img2/r0_c2.png", "scratch/all_cells/img2/r0_c5.png",
                "scratch/all_cells/img2/r7_c2.png", "scratch/all_cells/img2/r7_c5.png",
                "scratch/all_cells/img3/r7_c2.png", "scratch/all_cells/img3/r7_c5.png",
                "scratch/all_cells/img3/r0_c2.png", "scratch/all_cells/img3/r0_c5.png"
            ],
            'Q': [
                "scratch/all_cells/img1/r7_c3.png", "scratch/all_cells/img1/r0_c3.png",
                "scratch/all_cells/img2/r0_c4.png", "scratch/all_cells/img2/r7_c4.png",
                "scratch/all_cells/img3/r7_c3.png", "scratch/all_cells/img3/r0_c3.png"
            ],
            'K': [
                "scratch/all_cells/img1/r7_c4.png", "scratch/all_cells/img1/r0_c4.png",
                "scratch/all_cells/img2/r0_c3.png", "scratch/all_cells/img2/r7_c3.png",
                "scratch/all_cells/img3/r7_c4.png", "scratch/all_cells/img3/r0_c4.png"
            ]
        }
        
        self.templates = {}
        for cls_name, paths in self.class_samples.items():
            feats = []
            for p in paths:
                if os.path.exists(p):
                    img = cv2.imread(p)
                    feats.append(self._extract_feature(img))
            self.templates[cls_name] = feats

    def _extract_feature(self, cell_img):
        # 归一化为 48x48
        resized = cv2.resize(cell_img, (48, 48))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        
        # 严格截取中心 18%~82% 核心区域 (消除外框网格线)
        core_gray = gray[9:39, 9:39]
        
        # Sobel 梯度
        gx = cv2.Sobel(core_gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(core_gray, cv2.CV_32F, 0, 1, ksize=3)
        mag = np.sqrt(gx**2 + gy**2)
        
        norm = np.linalg.norm(mag)
        mag_norm = mag / (norm + 1e-5)
        
        return {
            'mag_norm': mag_norm,
            'core_gray': core_gray,
            'center_std': np.std(core_gray),
            'center_mean': np.mean(core_gray),
            'grad_mean': np.mean(mag)
        }

    def classify_board(self, cells_8x8):
        feats_8x8 = [[self._extract_feature(cells_8x8[r][c]) for c in range(8)] for r in range(8)]
        
        # 1. 判定空格子 vs 有子格
        occupied = []
        for r in range(8):
            for c in range(8):
                f = feats_8x8[r][c]
                # 空格子没有任何边缘和内部方差
                if f['center_std'] < 6.0 or f['grad_mean'] < 8.0:
                    continue
                    
                # 匹配 6 类棋子，选取模板库中相似度最高者
                best_cls = 'P'
                best_sim = -1e9
                for cls_name, t_list in self.templates.items():
                    for t in t_list:
                        # 纯余弦互相关 (Cosine NCC)
                        cos_sim = float(np.sum(f['mag_norm'] * t['mag_norm']))
                        if cos_sim > best_sim:
                            best_sim = cos_sim
                            best_cls = cls_name
                            
                occupied.append((r, c, f, best_cls))
                
        if not occupied:
            return [['.' for _ in range(8)] for _ in range(8)]
            
        # 2. 纯净 2-Means 聚类自适应划分黑白方（零硬编码阈值）
        means = [item[2]['center_mean'] for item in occupied]
        c1, c2 = min(means), max(means)
        for _ in range(10):
            g1 = [m for m in means if abs(m - c1) <= abs(m - c2)]
            g2 = [m for m in means if abs(m - c1) > abs(m - c2)]
            if g1: c1 = float(np.mean(g1))
            if g2: c2 = float(np.mean(g2))
            
        split_thresh = (c1 + c2) / 2.0
        
        # 3. 生成 8x8 棋盘
        board_res = [['.' for _ in range(8)] for _ in range(8)]
        for r, c, f, cls_name in occupied:
            is_white = (f['center_mean'] >= split_thresh)
            symbol = cls_name if is_white else cls_name.lower()
            board_res[r][c] = symbol
            
        return board_res

print("UltraRobustDuolingoClassifier 增强版加载成功！")
