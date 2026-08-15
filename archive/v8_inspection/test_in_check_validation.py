import chess

fen = "r1b1k1nr/pppp1ppp/2n5/5P2/3bq3/8/PPP3PP/RNB1KB1R w KQkq - 0 1"
board = chess.Board(fen)

print(f"Is White King in Check? {board.is_check()}")
legal_moves = [move.uci() for move in board.legal_moves]
print(f"Total legal moves ({len(legal_moves)}): {legal_moves}")

# Is 'f5f6' in legal moves?
print(f"Is 'f5f6' a legal move when in check? {'f5f6' in legal_moves}")
