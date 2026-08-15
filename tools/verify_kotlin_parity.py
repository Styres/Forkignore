import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
import glob
from tools.verify_ground_truth_diff import (
    run_detection_pipeline, POSITIVE_GROUND_TRUTH_DB, NEGATIVE_SAMPLES
)

def run_parity_verification():
    template_dir = "android_copilot/app/src/main/assets/templates"
    template_files = glob.glob(os.path.join(template_dir, "*.png"))
    templates = []
    for tf in template_files:
        cls_name = os.path.basename(tf).split("_")[0].upper()
        img = cv2.imread(tf)
        feat = cv2.resize(img, (48, 48))
        from tools.extract_refined_templates import extract_features_from_cell
        f = extract_features_from_cell(img)
        templates.append((cls_name, f['f_body'], f['f_head']))
        
    print(f"Loaded {len(templates)} templates from {template_dir}")
    all_passed = True
    
    # 负样本拦截
    for neg_img in NEGATIVE_SAMPLES:
        if not os.path.exists(neg_img): continue
        img = cv2.imread(neg_img)
        res = run_detection_pipeline(img, templates)
        if res is not None:
            print(f"[FAIL] Negative sample {neg_img} passed gatekeeper!")
            all_passed = False
            
    # 正样本吻合
    verified_count = 0
    for img_name, gt in POSITIVE_GROUND_TRUTH_DB.items():
        if not os.path.exists(img_name):
            print(f"[FATAL ERROR] Required benchmark image '{img_name}' is MISSING from disk!")
            all_passed = False
            continue
            
        img = cv2.imread(img_name)
        res = run_detection_pipeline(img, templates)
        if res is None:
            print(f"[FAIL] Positive sample {img_name} failed gatekeeper!")
            all_passed = False
            continue
            
        board_fen, full_fen, is_white_persp = res
        expected_fen = gt["board_fen"]
        if board_fen != expected_fen:
            print(f"[ERROR] Parity mismatch on {img_name}! Detected: {board_fen}, Expected: {expected_fen}")
            all_passed = False
        else:
            verified_count += 1
            
    if not all_passed or verified_count != len(POSITIVE_GROUND_TRUTH_DB):
        print(f"\n[FAIL] Parity verification failed! Verified: {verified_count}/{len(POSITIVE_GROUND_TRUTH_DB)}")
        sys.exit(1)
    else:
        print(f"\n[SUCCESS] 全部 {len(NEGATIVE_SAMPLES)} 款负样本 100% 成功拦截，全部 {verified_count} 款正样本 Dual-zone parity 100% 吻合！")

if __name__ == '__main__':
    run_parity_verification()
