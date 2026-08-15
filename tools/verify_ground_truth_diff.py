import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
import glob
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell
from tools.validate_all_fen import sanitize_board_py, build_fen_py

# 人工逐格严格核实背书的真机基准图 Ground Truth 目录
GROUND_TRUTH_DB = {
    "duolingo_1.jpeg": {
        "perspective": True, # White
        "board_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
        "full_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    },
    "duolingo_2.jpg": {
        "perspective": False, # Black
        "board_fen": "rnbqkbnr/pppppppp/8/8/5P2/8/PPPPP1PP/RNBQKBNR",
        "full_fen": "rnbqkbnr/pppppppp/8/8/5P2/8/PPPPP1PP/RNBQKBNR b KQkq - 0 1"
    },
    "duolingo_3.jpg": {
        "perspective": True, # White
        "board_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
        "full_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    },
    "duolingo_test_1.jfif": {
        "perspective": True, # White
        "board_fen": "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR",
        "full_fen": "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 1"
    }
}

def run_ground_truth_diff_verification():
    template_dir = "android_copilot/app/src/main/assets/templates"
    template_files = glob.glob(os.path.join(template_dir, "*.png"))
    templates = []
    for tf in template_files:
        cls_name = os.path.basename(tf).split("_")[0].upper()
        img = cv2.imread(tf)
        feat = extract_features_from_cell(img)
        templates.append((cls_name, feat['f_body'], feat['f_head']))
        
    print(f"Loaded {len(templates)} templates from {template_dir}")
    if len(templates) == 0:
        print("[FATAL ERROR] Template bank is EMPTY! Failing gating.")
        sys.exit(1)
        
    all_matched = True
    verified_count = 0
    
    for img_name, gt in GROUND_TRUTH_DB.items():
        if not os.path.exists(img_name):
            print(f"[FATAL ERROR] Required benchmark image '{img_name}' is MISSING from disk!")
            all_matched = False
            continue
            
        img = cv2.imread(img_name)
        if img is None:
            print(f"[FATAL ERROR] Failed to load benchmark image '{img_name}'!")
            all_matched = False
            continue
            
        l, t, r, b = fast_sat_locate_board(img)
        step = (r - l) / 8.0
        
        occupied = []
        for row in range(8):
            for col in range(8):
                cx1 = int(round(l + col * step))
                cy1 = int(round(t + row * step))
                cx2 = int(round(l + (col + 1) * step))
                cy2 = int(round(t + (row + 1) * step))
                cell = img[cy1:cy2, cx1:cx2]
                f = extract_features_from_cell(cell)
                
                if f['center_std'] >= 6.0 and f['grad_mean'] >= 8.0:
                    best_cls = 'P'
                    best_sim = -1e9
                    for t_cls, t_body, t_head in templates:
                        body_cos = float(np.sum(f['f_body'] * t_body))
                        head_cos = float(np.sum(f['f_head'] * t_head))
                        score = 0.65 * body_cos + 0.35 * head_cos
                        if score > best_sim:
                            best_sim = score
                            best_cls = t_cls
                    occupied.append((row, col, f, best_cls))
                    
        # 2-Means (含单色残局极差保护)
        raw_board = [['.' for _ in range(8)] for _ in range(8)]
        if occupied:
            means = [item[2]['center_mean'] for item in occupied]
            min_val, max_val = min(means), max(means)
            if max_val - min_val < 35.0:
                avg = float(np.mean(means))
                thresh = min_val - 1.0 if avg >= 120.0 else max_val + 1.0
            else:
                c1, c2 = min_val, max_val
                for _ in range(10):
                    g1 = [m for m in means if abs(m - c1) <= abs(m - c2)]
                    g2 = [m for m in means if abs(m - c1) > abs(m - c2)]
                    if g1: c1 = float(np.mean(g1))
                    if g2: c2 = float(np.mean(g2))
                thresh = (c1 + c2) / 2.0
            
            for r_idx, c_idx, f, cls_name in occupied:
                is_white = f['center_mean'] >= thresh
                raw_board[r_idx][c_idx] = cls_name.upper() if is_white else cls_name.lower()
                
        sanitized = sanitize_board_py(raw_board)
        top_w = sum(1 for row_p in sanitized[:2] for p in row_p if p.isupper())
        top_b = sum(1 for row_p in sanitized[:2] for p in row_p if p.islower())
        bot_w = sum(1 for row_p in sanitized[6:] for p in row_p if p.isupper())
        bot_b = sum(1 for row_p in sanitized[6:] for p in row_p if p.islower())
        is_white_persp = bot_w >= bot_b
        
        board_fen, full_fen = build_fen_py(sanitized, is_white_persp)
        expected_fen = gt["board_fen"]
        
        print(f"\n=================== Ground Truth Diff: [{img_name}] ===================")
        print(f"Detected Board FEN: {board_fen}")
        print(f"Expected Board FEN: {expected_fen}")
        
        if board_fen == expected_fen:
            print("  --> [MATCH] 64格逐格比对 100% 吻合！差异数: 0")
            verified_count += 1
        else:
            print(f"  --> [DIFF MISMATCH] 不吻合！")
            all_matched = False

    # 硬性覆盖率断言：必须 100% 完整校验所有基准图
    if verified_count != len(GROUND_TRUTH_DB) or not all_matched:
        print(f"\n[FAIL] Ground truth verification failed! Verified {verified_count}/{len(GROUND_TRUTH_DB)} images.")
        sys.exit(1)
    else:
        print(f"\n[SUCCESS] 全部 {verified_count} 款基准图像 64 格 Ground Truth 逐格 Diff 校验 100% 通过！")

if __name__ == '__main__':
    run_ground_truth_diff_verification()
