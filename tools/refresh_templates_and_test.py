import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell

# 1. 收集各张图已知位置的标准棋子并提取模板
TEMPLATE_SOURCES = [
    # (image_path, row, col, class_name)
    ("duolingo_1.jpeg", 7, 0, "R"),
    ("duolingo_1.jpeg", 7, 1, "N"),
    ("duolingo_1.jpeg", 7, 2, "B"),
    ("duolingo_1.jpeg", 7, 3, "Q"),
    ("duolingo_1.jpeg", 7, 4, "K"),
    ("duolingo_1.jpeg", 7, 5, "B"),
    ("duolingo_1.jpeg", 7, 6, "N"),
    ("duolingo_1.jpeg", 7, 7, "R"),
    ("duolingo_1.jpeg", 6, 0, "P"),
    ("duolingo_1.jpeg", 6, 1, "P"),
    ("duolingo_1.jpeg", 6, 2, "P"),
    ("duolingo_1.jpeg", 6, 3, "P"),
    ("duolingo_1.jpeg", 6, 4, "P"),
    ("duolingo_1.jpeg", 6, 5, "P"),
    ("duolingo_1.jpeg", 6, 6, "P"),
    ("duolingo_1.jpeg", 6, 7, "P"),
    
    # duolingo_2 (Row 0 opponent white pieces, Row 7 player black pieces)
    ("duolingo_2.jpg", 0, 0, "R"),
    ("duolingo_2.jpg", 0, 1, "N"),
    ("duolingo_2.jpg", 0, 2, "B"),
    ("duolingo_2.jpg", 0, 3, "K"),
    ("duolingo_2.jpg", 0, 4, "Q"),
    ("duolingo_2.jpg", 0, 5, "B"),
    ("duolingo_2.jpg", 0, 6, "N"),
    ("duolingo_2.jpg", 0, 7, "R"),
    ("duolingo_2.jpg", 7, 0, "R"),
    ("duolingo_2.jpg", 7, 1, "N"),
    ("duolingo_2.jpg", 7, 2, "B"),
    ("duolingo_2.jpg", 7, 3, "K"),
    ("duolingo_2.jpg", 7, 4, "Q"),
    ("duolingo_2.jpg", 7, 5, "B"),
    ("duolingo_2.jpg", 7, 6, "N"),
    ("duolingo_2.jpg", 7, 7, "R"),
    
    # duolingo_3 (Light clean theme)
    ("duolingo_3.jpg", 7, 0, "R"),
    ("duolingo_3.jpg", 7, 1, "N"),
    ("duolingo_3.jpg", 7, 2, "B"),
    ("duolingo_3.jpg", 7, 3, "Q"),
    ("duolingo_3.jpg", 7, 4, "K"),
    ("duolingo_3.jpg", 7, 5, "B"),
    ("duolingo_3.jpg", 7, 6, "N"),
    ("duolingo_3.jpg", 7, 7, "R"),
    ("duolingo_3.jpg", 6, 0, "P"),
    ("duolingo_3.jpg", 6, 1, "P"),
    ("duolingo_3.jpg", 6, 2, "P"),
    ("duolingo_3.jpg", 6, 3, "P"),
    ("duolingo_3.jpg", 6, 4, "P"),
    ("duolingo_3.jpg", 6, 5, "P"),
    ("duolingo_3.jpg", 6, 6, "P"),
    ("duolingo_3.jpg", 6, 7, "P"),
    ("duolingo_3.jpg", 0, 0, "R"),
    ("duolingo_3.jpg", 0, 1, "N"),
    ("duolingo_3.jpg", 0, 2, "B"),
    ("duolingo_3.jpg", 0, 3, "Q"),
    ("duolingo_3.jpg", 0, 4, "K"),
    ("duolingo_3.jpg", 0, 5, "B"),
    ("duolingo_3.jpg", 0, 6, "N"),
    ("duolingo_3.jpg", 0, 7, "R"),
]

def build_template_bank():
    templates = {}
    assets_dir = "android_copilot/app/src/main/assets/templates"
    os.makedirs(assets_dir, exist_ok=True)
    
    # 清空旧模板
    for f in os.listdir(assets_dir):
        if f.endswith(".png"):
            os.remove(os.path.join(assets_dir, f))
            
    counter = {}
    for img_path, r, c, cls_name in TEMPLATE_SOURCES:
        if not os.path.exists(img_path): continue
        img = cv2.imread(img_path)
        l, t, right, b = fast_sat_locate_board(img)
        step = (right - l) / 8.0
        
        cx1 = int(l + c * step)
        cy1 = int(t + r * step)
        cx2 = int(l + (c + 1) * step)
        cy2 = int(t + (r + 1) * step)
        crop = img[cy1:cy2, cx1:cx2]
        
        # 保存 48x48 模板到 assets/templates
        idx = counter.get(cls_name, 0)
        counter[cls_name] = idx + 1
        
        tpl_name = f"{cls_name}_{idx}.png"
        tpl_path = os.path.join(assets_dir, tpl_name)
        resized_48 = cv2.resize(crop, (48, 48), interpolation=cv2.INTER_LINEAR)
        cv2.imwrite(tpl_path, resized_48)
        
        feat = extract_features_from_cell(crop)
        if cls_name not in templates:
            templates[cls_name] = []
        templates[cls_name].append(feat)
        
    print(f"Generated {sum(counter.values())} templates in {assets_dir}: {counter}")
    return templates

if __name__ == '__main__':
    build_template_bank()
