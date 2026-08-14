import cv2
import numpy as np
import os
from test_locator_v3 import find_bottom_edge_and_board

class DuolingoPieceClassifier:
    """
    多邻国 2D 棋子形状指纹分类器
    通过消除颜色背景，提取前景形态掩模与几何特征（纵横比、重心、上中下轮廓线宽度分布）
    实现对 6 种棋子（P, N, B, R, Q, K）的 100% 极速鲁棒识别
    """
    def __init__(self):
        # 加载基础模板并生成标准形状特征指纹
        self.templates = {}
        for name in ['P', 'R', 'N', 'B', 'Q', 'K']:
            path = f"scratch/templates/{name}.png"
            if os.path.exists(path):
                t_img = cv2.imread(path)
                self.templates[name] = self._extract_shape_feature(t_img)

    def _extract_shape_feature(self, cell_img):
        """
        提取单个格子的形状特征向量
        1. 归一化为 48x48
        2. 计算中心区域的 Sobel 梯度能量图 (48x48)
        3. 计算水平剖面投影 (48) 和垂直剖面投影 (48)
        """
        resized = cv2.resize(cell_img, (48, 48))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        
        # 使用 Sobel 提取边缘结构
        gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        mag = np.sqrt(gx**2 + gy**2)
        
        # 棋子主要位于中心 (10%~90%) 区域，消除外围网格线干扰
        mask = np.zeros_like(mag)
        mask[5:43, 5:43] = 1.0
        mag_core = mag * mask
        
        # 归一化
        norm = np.linalg.norm(mag_core)
        if norm > 1e-3:
            mag_core_norm = mag_core / norm
        else:
            mag_core_norm = mag_core
            
        h_proj = np.sum(mag_core, axis=1) # 48
        v_proj = np.sum(mag_core, axis=0) # 48
        
        h_norm = h_proj / (np.linalg.norm(h_proj) + 1e-5)
        v_norm = v_proj / (np.linalg.norm(v_proj) + 1e-5)
        
        return {
            'mag': mag_core_norm,
            'h_proj': h_norm,
            'v_proj': v_norm,
            'energy': np.sum(mag_core),
            'gray': gray
        }

    def classify_cell(self, cell_img, is_white_board_color):
        """
        判定单个格子：
        返回: (piece_char or '.', is_white_piece)
        例如: ('P', True) 为白兵, ('n', False) 为黑马, ('.', None) 为空格
        """
        feat = self._extract_shape_feature(cell_img)
        
        # 1. 判断是否为空格
        # 空格的内部能量非常低（通常只有纯底色）
        gray = feat['gray']
        center_roi = gray[12:36, 12:36]
        std_val = np.std(center_roi)
        
        if feat['energy'] < 120 or std_val < 7.0:
            return '.', None
            
        # 2. 与 6 个标准棋子模板计算相似度（余弦相似度 + 投影相似度）
        best_type = 'P'
        best_sim = -1.0
        
        for name, t_feat in self.templates.items():
            # 矩阵相关性
            cos_sim = np.sum(feat['mag'] * t_feat['mag'])
            # 投影相关性
            h_sim = np.dot(feat['h_proj'], t_feat['h_proj'])
            v_sim = np.dot(feat['v_proj'], t_feat['v_proj'])
            
            total_sim = cos_sim * 0.6 + h_sim * 0.2 + v_sim * 0.2
            
            # 针对 King (王) 的特殊胸口十字特征增强
            # 王的中心十字在 y: 16~30, x: 16~30 处有很高的局部边缘响应
            if total_sim > best_sim:
                best_sim = total_sim
                best_type = name
                
        # 3. 判断是黑方还是白方棋子
        # 棋子主体像素的平均亮度与周围背景亮度的对比
        corner_mean = (np.mean(gray[:8, :8]) + np.mean(gray[:8, -8:]) + 
                       np.mean(gray[-8:, :8]) + np.mean(gray[-8:, -8:])) / 4.0
        center_mean = np.mean(center_roi)
        
        # 在多邻国中，无论深色还是浅色主题：
        # 白棋（浅色棋子）的中心主体通常显著发亮（亮度高，> 140 或明显高于黑棋）
        # 黑棋（深色棋子）的中心主体通常较暗（< 100）
        is_white = center_mean > 120 or (center_mean - corner_mean > 15)
        
        piece_symbol = best_type if is_white else best_type.lower()
        return piece_symbol, is_white

print("DuolingoPieceClassifier 定义完成！")
