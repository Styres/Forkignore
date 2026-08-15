import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from tools.test_pure_in_check import is_king_in_check

fen6 = "r3k1nr/ppp2ppp/2np1p2/8/3bq1b1/8/PPP1B1PP/RNB1K2R"
rows = fen6.split('/')
board = []
for r_str in rows:
    row = []
    for ch in r_str:
        if ch.isdigit(): row.extend(['.'] * int(ch))
        else: row.append(ch)
    board.append(row)

# White King is on e1 (row 7 col 4)
# White Bishop is on e2 (row 6 col 4)
# Black Queen is on e4 (row 4 col 4)

# Initially, is White in check?
print("Initial in check (Bishop blocks Queen on e4):", is_king_in_check(board, True))

# Simulate move e2f3 (Bishop moves from row 6 col 4 to row 5 col 5)
board_e2f3 = [list(r) for r in board]
board_e2f3[5][5] = 'B'
board_e2f3[6][4] = '.'

print("After move 'e2f3', is White King in check (Pin violation)?", is_king_in_check(board_e2f3, True))
