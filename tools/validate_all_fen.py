import cv2
import numpy as np
import os
import chess
from tools.test_locator_robust import locate_duolingo_board_robust
from tools.robust_classifier import UltraRobustDuolingoClassifier

def validate_all_images():
    classifier = UltraRobustDuolingoClassifier(template_dir="android_copilot/app/src/main/assets/templates")
    
    images = ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg", "duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"]
    
    for img_name in images:
        if not os.path.exists(img_name):
            print(f"Skipping {img_name} (not found)")
            continue
        img = cv2.imread(img_name)
        l, t, r, b = locate_duolingo_board_robust(img)
        print(f"\n=================== Testing: {img_name} ===================")
        print(f"Located Board: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
        
        # 裁剪棋盘
        board_crop = img[t:b, l:r]
        res = classifier.classify_board(img, (l, t, r, b))
        
        full_fen = res["full_fen"]
        board_fen = res["board_fen"]
        is_white = res["is_white_perspective"]
        
        print(f"Perspective: {'White' if is_white else 'Black'}")
        print(f"Board FEN: {board_fen}")
        print(f"Full FEN:  {full_fen}")
        
        # 用 python-chess 验证 FEN 合法性
        try:
            board = chess.Board(full_fen)
            is_valid = board.is_valid()
            status = board.status()
            print(f"Lichess / python-chess Valid: {is_valid} (Status: {status})")
            if not is_valid:
                print(f"  -> Invalid reason: {status}")
        except Exception as e:
            print(f"Lichess / python-chess Parse Error: {e}")

if __name__ == '__main__':
    validate_all_images()
