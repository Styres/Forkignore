import cv2
import numpy as np
import os
import glob

class MultiModalPieceClassifier:
    """
    多模态多邻国棋子分类器
    集成了深色主题/浅色主题、黑方/白方的全部 6 类棋子标准特征库
    """
    def __init__(self):
        # 构建各类别的已知样本路径
        # img1: r0: r,n,b,q,k,b,n,r; r1: p; r6: P; r7: R,N,B,Q,K,B,N,R
        # img2: r0: R,N,B,K,Q,B,N,R; r1: P,P,.,P,P,P,P,P; r3: .,.,P; r6: p; r7: r,n,b,k,q,b,n,r
        # img3: r0: r,n,b,q,k,b,n,r; r1: p; r6: P; r7: R,N,B,Q,K,B,N,R
        
        self.sample_map = {
            'R': [
                "scratch/all_cells/img1/r7_c0.png", "scratch/all_cells/img1/r7_c7.png",
                "scratch/all_cells/img2/r0_c0.png", "scratch/all_cells/img2/r0_c7.png",
                "scratch/all_cells/img3/r7_c0.png", "scratch/all_cells/img3/r7_c7.png"
            ],
            'N': [
                "scratch/all_cells/img1/r7_c1.png", "scratch/all_cells/img1/r7_c6.png",
                "scratch/all_cells/img2/r0_c1.png", "scratch/all_cells/img2/r0_c6.png",
                "scratch/all_cells/img3/r7_c1.png", "scratch/all_cells/img3/r7_c6.png"
            ],
            'B': [
                "scratch/all_cells/img1/r7_c2.png", "scratch/all_cells/img1/r7_c5.png",
                "scratch/all_cells/img2/r0_c2.png", "scratch/all_cells/img2/r0_c5.png",
                "scratch/all_cells/img3/r7_c2.png", "scratch/all_cells/img3/r7_c5.png"
            ],
            'Q': [
                "scratch/all_cells/img1/r7_c3.png",
                "scratch/all_cells/img2/r0_c4.png", # 注意：img2中王在c3，后在c4
                "scratch/all_cells/img3/r7_c3.png"
            ],
            'K': [
                "scratch/all_cells/img1/r7_c4.png",
                "scratch/all_cells/img2/r0_c3.png",
                "scratch/all_cells/img3/r7_c4.png"
            ],
            'P': [
                "scratch/all_cells/img1/r6_c0.png", "scratch/all_cells/img1/r6_c3.png",
                "scratch/all_cells/img2/r1_c0.png", "scratch/all_cells/img2/r3_c2.png",
                "scratch/all_cells/img3/r6_c0.png", "scratch/all_cells/img3/r6_c4.png"
            ],
            # 黑棋样本
            'r': [
                "scratch/all_cells/img1/r0_c0.png", "scratch/all_cells/img1/r0_c7.png",
                "scratch/all_cells/img2/r7_c0.png", "scratch/all_cells/img2/r7_c7.png",
                "scratch/all_cells/img3/r0_c0.png", "scratch/all_cells/img3/r0_c7.png"
            ],
            'n': [
                "scratch/all_cells/img1/r0_c1.png", "scratch/all_cells/img1/r0_c6.png",
                "scratch/all_cells/img2/r7_c1.png", "scratch/all_cells/img2/r7_c6.png",
                "scratch/all_cells/img3/r0_c1.png", "scratch/all_cells/img3/r0_c6.png"
            ],
            'b': [
                "scratch/all_cells/img1/r0_c2.png", "scratch/all_cells/img1/r0_c5.png",
                "scratch/all_cells/img2/r7_c2.png", "scratch/all_cells/img2/r7_c5.png",
                "scratch/all_cells/img3/r0_c2.png", "scratch/all_cells/img3/r0_c5.png"
            ],
            'q': [
                "scratch/all_cells/img1/r0_c3.png",
                "scratch/all_cells/img2/r7_c4.png",
                "scratch/all_cells/img3/r0_c3.png"
            ],
            'k': [
                "scratch/all_cells/img1/r0_c4.png",
                "scratch/all_cells/img2/r7_c3.png",
                "scratch/all_cells/img3/r0_c4.png"
            ],
            'p': [
                "scratch/all_cells/img1/r1_c0.png", "scratch/all_cells/img1/r1_c4.png",
                "scratch/all_cells/img2/r6_c0.png", "scratch/all_cells/img2/r6_c4.png",
                "scratch/all_cells/img3/r1_c0.png", "scratch/all_cells/img3/r1_c3.png"
            ]
        }
        
        self.templates = {}
        for piece_type, paths in self.sample_map.items():
            feats = []
            for p in paths:
                if os.path.exists(p):
                    img = cv2.imread(p)
                    feats.append(self._get_feature(img))
            self.templates[piece_type] = feats

    def _get_feature(self, cell_img):
        # 归一化为 48x48
        resized = cv2.resize(cell_img, (48, 48))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        
        # 提取中心 10%~90% 区域，去除边缘网格线
        gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        mag = np.sqrt(gx**2 + gy**2)
        
        mask = np.zeros_like(mag)
        mask[6:42, 6:42] = 1.0
        mag = mag * mask
        
        # 梯度能量归一化
        norm = np.linalg.norm(mag)
        mag_norm = mag / (norm + 1e-6)
        
        # 灰度中心对比特征
        center_gray = gray[10:38, 10:38]
        
        return {
            'mag_norm': mag_norm,
            'gray': gray,
            'center_std': np.std(center_gray),
            'center_mean': np.mean(center_gray),
            'energy': np.sum(mag)
        }

    def classify(self, cell_img):
        feat = self._get_feature(cell_img)
        
        # 1. 空格检测：内部变化极小
        if feat['energy'] < 100 or feat['center_std'] < 6.5:
            return '.'
            
        # 2. 与各类别模版比对余弦相似度
        best_type = '.'
        best_sim = -1.0
        
        for p_type, feat_list in self.templates.items():
            for t_feat in feat_list:
                # 梯度余弦相似度
                cos_sim = np.sum(feat['mag_norm'] * t_feat['mag_norm'])
                
                # 亮度匹配惩罚（白棋与黑棋的亮度区分）
                # 如果一个是白棋（大写），一个是黑棋（小写），中心均值相差很大时进行惩罚
                is_t_white = p_type.isupper()
                # 粗略判断当前格是否较亮
                is_f_white = feat['center_mean'] > 120
                
                penalty = 0.0
                if is_t_white != is_f_white:
                    # 降低异色匹配得分
                    penalty = 0.15
                    
                final_sim = cos_sim - penalty
                
                if final_sim > best_sim:
                    best_sim = final_sim
                    best_type = p_type
                    
        return best_type

print("MultiModalPieceClassifier 模块加载成功！")
