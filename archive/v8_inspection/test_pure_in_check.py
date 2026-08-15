def is_square_attacked(board, target_r, target_c, by_white):
    # Check knight attacks
    knight_offsets = [(-2, -1), (-2, 1), (-1, -2), (-1, 2), (1, -2), (1, 2), (2, -1), (2, 1)]
    k_sym = 'N' if by_white else 'n'
    for dr, dc in knight_offsets:
        nr, nc = target_r + dr, target_c + dc
        if 0 <= nr < 8 and 0 <= nc < 8 and board[nr][nc] == k_sym:
            return True

    # Check pawn attacks
    p_sym = 'P' if by_white else 'p'
    p_dr = 1 if by_white else -1  # If attacked by white, attacker is at row+1, moving up
    for p_dc in [-1, 1]:
        nr, nc = target_r + p_dr, target_c + p_dc
        if 0 <= nr < 8 and 0 <= nc < 8 and board[nr][nc] == p_sym:
            return True

    # Check straight lines (R, Q)
    straight_syms = ['R', 'Q'] if by_white else ['r', 'q']
    for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
        step = 1
        while True:
            nr, nc = target_r + dr * step, target_c + dc * step
            if not (0 <= nr < 8 and 0 <= nc < 8): break
            p = board[nr][nc]
            if p != '.':
                if p in straight_syms: return True
                break
            step += 1

    # Check diagonal lines (B, Q)
    diag_syms = ['B', 'Q'] if by_white else ['b', 'q']
    for dr, dc in [(-1, -1), (-1, 1), (1, -1), (1, 1)]:
        step = 1
        while True:
            nr, nc = target_r + dr * step, target_c + dc * step
            if not (0 <= nr < 8 and 0 <= nc < 8): break
            p = board[nr][nc]
            if p != '.':
                if p in diag_syms: return True
                break
            step += 1

    # Check king 1-step
    king_sym = 'K' if by_white else 'k'
    for dr in [-1, 0, 1]:
        for dc in [-1, 0, 1]:
            if dr == 0 and dc == 0: continue
            nr, nc = target_r + dr, target_c + dc
            if 0 <= nr < 8 and 0 <= nc < 8 and board[nr][nc] == king_sym:
                return True
                
    return False

def is_king_in_check(board, is_white):
    king_sym = 'K' if is_white else 'k'
    kr, kc = -1, -1
    for r in range(8):
        for c in range(8):
            if board[r][c] == king_sym:
                kr, kc = r, c
                break
        if kr != -1: break
    if kr == -1: return False
    return is_square_attacked(board, kr, kc, not is_white)

# Test position on bug_7.jpg
# FEN: r1b1k1nr/pppp1ppp/2n5/5P2/3bq3/8/PPP3PP/RNB1KB1R w KQkq - 0 1
fen = "r1b1k1nr/pppp1ppp/2n5/5P2/3bq3/8/PPP3PP/RNB1KB1R"
rows = fen.split('/')
board = []
for r_str in rows:
    row = []
    for ch in r_str:
        if ch.isdigit():
            row.extend(['.'] * int(ch))
        else:
            row.append(ch)
    board.append(row)

in_check = is_king_in_check(board, True)
print(f"Is White King (K on e1) in check on bug_7 position? {in_check}")

# Test simulated move f5f6 (from row 3 col 5 to row 2 col 5)
board_after_f5f6 = [list(r) for r in board]
board_after_f5f6[2][5] = 'P'
board_after_f5f6[3][5] = '.'
in_check_after_f5f6 = is_king_in_check(board_after_f5f6, True)
print(f"After move 'f5f6', is White King still in check? {in_check_after_f5f6} (Illegal move!)")

# Test simulated move Be2 (from row 7 col 5 to row 6 col 4)
board_after_Be2 = [list(r) for r in board]
board_after_Be2[6][4] = 'B'
board_after_Be2[7][5] = '.'
in_check_after_Be2 = is_king_in_check(board_after_Be2, True)
print(f"After move 'f1e2' (Be2), is White King in check? {in_check_after_Be2} (Legal defense!)")

# Test simulated move Ne2 (from row 7 col 1 to row 6 col 4)
board_after_Ne2 = [list(r) for r in board]
board_after_Ne2[6][4] = 'N'
board_after_Ne2[7][1] = '.'
in_check_after_Ne2 = is_king_in_check(board_after_Ne2, True)
print(f"After move 'b1e2' (Ne2), is White King in check? {in_check_after_Ne2} (Legal defense!)")
