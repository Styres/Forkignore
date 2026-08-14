import cv2
import numpy as np
import os
from test_locator_v3 import find_bottom_edge_and_board
from piece_classifier import DuolingoPieceClassifier

class DuolingoChessDetector:
    def __init__(self):
        self.classifier = DuolingoPieceClassifier()
        
    def detect_image(self, image_path):
        img = cv2.imread(image_path)
        if img is None:
            raise FileNotFoundError(f"找不到图片: {image_path}")
            
        l, t, r, b = find_bottom_edge_and_board(img)
        step = (r - l) / 8.0
        
        raw_board = [] # 8x8 从屏幕顶部到屏幕底部
        for row in range(8):
            row_symbols = []
            for col in range(8):
                x1 = int(round(l + col * step))
                y1 = int(round(t + row * step))
                x2 = int(round(l + (col + 1) * step))
                y2 = int(round(t + (row + 1) * step))
                
                cell = img[y1:y2, x1:x2]
                is_white_square = ((row + col) % 2 == 0)
                symbol, is_white_piece = self.classifier.classify_cell(cell, is_white_square)
                row_symbols.append(symbol)
            raw_board.append(row_symbols)
            
        # 判定视角朝向 (Orientation):
        # 统计屏幕顶部 2 行和底部 2 行的棋子阵营
        top_white_count = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].isupper())
        top_black_count = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].islower())
        
        bottom_white_count = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].isupper())
        bottom_black_count = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].islower())
        
        # 如果底部大多是白棋，说明是白方视角（正常朝向：第8行到第1行）
        # 如果底部大多是黑棋，说明是黑方视角（旋转朝向：屏幕顶部是白方第1行，屏幕底部是黑方第8行）
        is_white_perspective = (bottom_white_count >= bottom_black_count)
        
        if is_white_perspective:
            standard_board = raw_board # 屏幕第0行对应棋盘 rank 8
            active_color = 'w'
        else:
            # 翻转 180 度得到标准从 rank 8 到 rank 1 的矩阵
            standard_board = [row[::-1] for row in raw_board[::-1]]
            active_color = 'b'
            
        # 构建 FEN 字符串
        fen_rows = []
        for row in standard_board:
            fen_row = ''
            empty_count = 0
            for sym in row:
                if sym == '.':
                    empty_count += 1
                else:
                    if empty_count > 0:
                        fen_row += str(empty_count)
                        empty_count = 0
                    fen_row += sym
            if empty_count > 0:
                fen_row += str(empty_count)
            fen_rows.append(fen_row)
            
        board_fen = '/'.join(fen_rows)
        full_fen = f"{board_fen} {active_color} KQkq - 0 1"
        
        return {
            'rect': (l, t, r, b),
            'perspective': 'white' if is_white_perspective else 'black',
            'raw_board': raw_board,
            'standard_board': standard_board,
            'fen': board_fen,
            'full_fen': full_fen
        }

if __name__ == '__main__':
    detector = DuolingoChessDetector()
    for name in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
        print(f"\n==================== Analyzing {name} ====================")
        res = detector.detect_image(name)
        print(f"Perspective: {res['perspective']}")
        print("Raw Screen Board:")
        for r in res['raw_board']:
            print(" ".join(r))
        print(f"Generated FEN: {res['fen']}")
        print(f"Full FEN: {res['full_fen']}")
