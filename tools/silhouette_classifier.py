import cv2
import numpy as np
import os

class RobustSilhouetteClassifier:
    """
    基于前景轮廓掩模 (Silhouette) + 边缘金字塔的多尺度棋子分类器
    在 36px 极低分辨率和 155px 高清下均能保持极高鲁棒性
    """
    def __init__(self):
        # 加载标准样本并提取纯形态轮廓
        self.classes = ['P', 'R', 'N', 'B', 'Q', 'K']
        self.templates = {c: [] for c in self.classes}
        
        # 收集来自各样本的纯形态
        sample_paths = {
            'P': ["scratch/all_cells/img1/r6_c0.png", "scratch/all_cells/img1/r6_c2.png", "scratch/all_cells/img3/r6_c0.png"],
            'R': ["scratch/all_cells/img1/r7_c0.png", "scratch/all_cells/img1/r7_c7.png", "scratch/all_cells/img3/r7_c0.png"],
            'N': ["scratch/all_cells/img1/r7_c1.png", "scratch/all_cells/img1/r7_c6.png", "scratch/all_cells/img3/r7_c1.png"],
            'B': ["scratch/all_cells/img1/r7_c2.png", "scratch/all_cells/img1/r7_c5.png", "scratch/all_cells/img3/r7_c2.png"],
            'Q': ["scratch/all_cells/img1/r7_c3.png", "scratch/all_cells/img3/r7_c3.png"],
            'K': ["scratch/all_cells/img1/r7_c4.png", "scratch/all_cells/img3/r7_c4.png"]
        }
        
        for cls_name, paths in sample_paths.items():
            for p in paths:
                if os.path.exists(p):
                    img = cv2.imread(p)
                    self.templates[cls_name].append(self._extract_silhouette(img))

    def _extract_silhouette(self, cell_img):
        # 统一缩放到 36x36 (匹配低分辨率基准)
        resized = cv2.resize(cell_img, (36, 36))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        
        # 截取中心 20%~80% 核心区域 (约 7:29)
        core = gray[7:29, 7:29]
        
        # 计算局部四角背景均值
        ch, cw = core.shape
        bg_val = (np.mean(core[:4, :4]) + np.mean(core[:4, -4:]) + 
                  np.mean(core[-4:, :4]) + np.mean(core[-4:, -4:])) / 4.0
                  
        # 前景差分
        diff = np.abs(core.astype(np.float32) - bg_val)
        
        # 归一化前景轮廓
        norm = np.linalg.norm(diff)
        diff_norm = diff / (norm + 1e-5)
        
        # 水平轮廓宽度剖面 (22)
        h_profile = np.sum(diff_norm, axis=1)
        h_profile /= (np.linalg.norm(h_profile) + 1e-5)
        
        # 垂直剖面 (22)
        v_profile = np.sum(diff_norm, axis=0)
        v_profile /= (np.linalg.norm(v_profile) + 1e-5)
        
        return {
            'diff_norm': diff_norm,
            'h_prof': h_profile,
            'v_prof': v_profile,
            'std': np.std(core),
            'mean': np.mean(core),
            'energy': np.sum(diff)
        }

    def classify_board(self, cells_8x8):
        feats = [[self._extract_silhouette(cells_8x8[r][c]) for c in range(8)] for r in range(8)]
        
        # 1. 判定非空格子
        occupied = []
        for r in range(8):
            for c in range(8):
                f = feats[r][c]
                # 空格内部差分能量极低
                if f['std'] < 5.0 or f['energy'] < 1200:
                    continue
                    
                best_cls = 'P'
                best_sim = -1e9
                for cls_name, t_list in self.templates.items():
                    for t in t_list:
                        # 2D 轮廓相关度 + 剖面投影相关度
                        sim_2d = np.sum(f['diff_norm'] * t['diff_norm'])
                        sim_h = np.dot(f['h_prof'], t['h_prof'])
                        sim_v = np.dot(f['v_prof'], t['v_prof'])
                        
                        total_sim = sim_2d * 0.6 + sim_h * 0.2 + sim_v * 0.2
                        if total_sim > best_sim:
                            best_sim = total_sim
                            best_cls = cls_name
                            
                occupied.append((r, c, f, best_cls))
                
        if not occupied:
            return [['.' for _ in range(8)] for _ in range(8)]
            
        # 2. 2-Means 自适应聚类判定黑白方
        means = [item[2]['mean'] for item in occupied]
        c1, c2 = min(means), max(means)
        for _ in range(10):
            g1 = [m for m in means if abs(m - c1) <= abs(m - c2)]
            g2 = [m for m in means if abs(m - c1) > abs(m - c2)]
            if g1: c1 = float(np.mean(g1))
            if g2: c2 = float(np.mean(g2))
            
        split_thresh = (c1 + c2) / 2.0
        
        # 3. 填入 8x8 结果
        board_res = [['.' for _ in range(8)] for _ in range(8)]
        for r, c, f, cls_name in occupied:
            is_white = (f['mean'] >= split_thresh)
            symbol = cls_name if is_white else cls_name.lower()
            board_res[r][c] = symbol
            
        return board_res

print("RobustSilhouetteClassifier 加载完成！")
