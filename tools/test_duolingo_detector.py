import cv2
import numpy as np
import os
import unittest
from test_locator_v3 import find_bottom_edge_and_board
from robust_classifier import UltraRobustDuolingoClassifier

class DuolingoPipelineV3:
    def __init__(self):
        self.classifier = UltraRobustDuolingoClassifier()
        
    def process_image(self, img_path):
        img = cv2.imread(img_path)
        if img is None:
            raise FileNotFoundError(f"找不到文件: {img_path}")
            
        l, t, r, b = find_bottom_edge_and_board(img)
        step = (r - l) / 8.0
        
        cells_8x8 = []
        for row in range(8):
            row_cells = []
            for col in range(8):
                x1 = int(round(l + col * step))
                y1 = int(round(t + row * step))
                x2 = int(round(l + (col + 1) * step))
                y2 = int(round(t + (row + 1) * step))
                cell = img[y1:y2, x1:x2]
                row_cells.append(cell)
            cells_8x8.append(row_cells)
            
        raw_board = self.classifier.classify_board(cells_8x8)
        
        # 统计顶底白黑子数量判定朝向
        top_white = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].isupper())
        top_black = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].islower())
        bot_white = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].isupper())
        bot_black = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].islower())
        
        is_white_persp = (bot_white >= bot_black)
        
        if is_white_persp:
            standard_board = raw_board
            active_color = 'w'
        else:
            # 执黑时，屏幕底部是黑方rank 8，屏幕顶部是白方rank 1
            # 翻转 180 度得到标准从 rank 8 (黑方) 到 rank 1 (白方) 的标准 FEN 矩阵
            standard_board = [row[::-1] for row in raw_board[::-1]]
            active_color = 'b'
            
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
            'perspective': 'white' if is_white_persp else 'black',
            'raw_board': raw_board,
            'standard_board': standard_board,
            'board_fen': board_fen,
            'full_fen': full_fen
        }

class TestDuolingoChessDetector(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pipeline = DuolingoPipelineV3()
        
    def test_duolingo_1_dark_initial(self):
        """测试用例 1: 深色主题初始开局 (duolingo_1.jpeg)"""
        res = self.pipeline.process_image("duolingo_1.jpeg")
        print("\n--- Test 1: duolingo_1.jpeg ---")
        for r in res['raw_board']:
            print(" ".join(r))
        print("FEN:", res['board_fen'])
        self.assertEqual(res['perspective'], 'white')
        self.assertEqual(res['board_fen'], "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")

    def test_duolingo_2_dark_e4_black_perspective(self):
        """测试用例 2: 深色主题已走e4、黑方下侧视角 (duolingo_2.jpg)"""
        res = self.pipeline.process_image("duolingo_2.jpg")
        print("\n--- Test 2: duolingo_2.jpg ---")
        for r in res['raw_board']:
            print(" ".join(r))
        print("Standard FEN:", res['board_fen'])
        self.assertEqual(res['perspective'], 'black')
        # 黑方底线
        self.assertEqual(res['raw_board'][7], ['r', 'n', 'b', 'k', 'q', 'b', 'n', 'r'])
        # 白方底线
        self.assertEqual(res['raw_board'][0], ['R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R'])
        # 白方出兵
        self.assertEqual(res['raw_board'][3][2], 'P')

    def test_duolingo_3_light_initial(self):
        """测试用例 3: 浅色清新主题初始开局 (duolingo_3.jpg)"""
        res = self.pipeline.process_image("duolingo_3.jpg")
        print("\n--- Test 3: duolingo_3.jpg ---")
        for r in res['raw_board']:
            print(" ".join(r))
        print("FEN:", res['board_fen'])
        self.assertEqual(res['perspective'], 'white')
        self.assertEqual(res['board_fen'], "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")

if __name__ == '__main__':
    unittest.main()
