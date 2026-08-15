import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_locator_robust import locate_duolingo_board_robust
from tools.robust_classifier import UltraRobustDuolingoClassifier

def inspect_fens():
    classifier = UltraRobustDuolingoClassifier(template_dir="android_copilot/app/src/main/assets/templates")
    images = ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg", "duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"]
    
    for img_name in images:
        if not os.path.exists(img_name): continue
        img = cv2.imread(img_name)
        l, t, r, b = locate_duolingo_board_robust(img)
        res = classifier.classify_board(img, (l, t, r, b))
        
        full_fen = res["full_fen"]
        board_fen = res["board_fen"]
        print(f"\n=================== [{img_name}] ===================")
        print(f"Board Rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
        print(f"Raw Screen:\n{res['raw_screen']}")
        print(f"Perspective: {'White' if res['is_white_perspective'] else 'Black'}")
        print(f"Board FEN: {board_fen}")
        print(f"Full FEN:  {full_fen}")
        
        # 详细检查 Lichess 合法性规则
        ranks = board_fen.split('/')
        if len(ranks) != 8:
            print(f"  ❌ Invalid rank count: {len(ranks)}")
        
        white_kings = full_fen.count('K')
        black_kings = full_fen.count('k')
        if white_kings != 1:
            print(f"  ❌ Invalid White King count: {white_kings} (Must be 1)")
        if black_kings != 1:
            print(f"  ❌ Invalid Black King count: {black_kings} (Must be 1)")
            
        # 检查 rank 1 和 rank 8 是否有兵
        if 'p' in ranks[0] or 'P' in ranks[0]:
            print(f"  ❌ Pawns on rank 8 (top row in FEN): {ranks[0]}")
        if 'p' in ranks[7] or 'P' in ranks[7]:
            print(f"  ❌ Pawns on rank 1 (bottom row in FEN): {ranks[7]}")
            
        for idx, rk in enumerate(ranks):
            cnt = 0
            for ch in rk:
                if ch.isdigit(): cnt += int(ch)
                else: cnt += 1
            if cnt != 8:
                print(f"  ❌ Rank {8 - idx} total squares = {cnt} (Must be 8): {rk}")

if __name__ == '__main__':
    inspect_fens()
