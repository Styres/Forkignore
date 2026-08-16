# -*- coding: utf-8 -*-
"""
基础验证层 (用户指令: 不再表面修补, 从棋盘/棋子识别与行棋规则做底层验证)
用 python-chess 权威规则库交叉验证历次 Bug 案例:
  A. 识别出的 FEN 是否符合棋规 (双方恰一王 / 王不相邻 / 兵不在 1-8 横线 / 走子方王不被将军)
  B. 悬浮层建议着法在该 FEN 下是否合法
只诊断输出, 不作为 CI 门禁 (历史案例无 ground truth 图像对照时不阻断)。
"""
import chess

CASES = [
    # (bug_id, 来源, FEN, 建议着法 UCI)
    ("bug_11", "实机复现(污染帧)", "r3k2r/pp3ppp/5n2/4q3/1n6/2PP4/P1PBNPbP/R3K1R1 b KQkq - 0 1", "b4c2"),
    ("bug_16", "离线复现(=Python复现, 干净帧)", "7r/1p5P/pr3p2/P7/4k3/7P/2P4P/b6K b KQkq - 0 1", "e4e5"),
    ("bug_17", "离线复现(=Python复现, 干净帧)", "r1r3k1/pp3pp1/7p/4Rb2/3n1P2/P7/2P3PP/7K b KQkq - 0 1", "c8c5"),
    ("bug_18", "实机遥测(带悬浮层污染帧)", "r3k2r/pp1q2pp/2n2p2/3Qp3/1b2P3/2N1B2P/PPPNRKP1/R6B w KQkq - 0 1", "f2c2"),
    # bug_13/14 的实机 FEN 未落盘, 用离线污染帧复现值仅做结构校验
    ("bug_13(离线污染帧)", "离线复现", "2n5/3qp3/3P4/5N2/PPP2PPP/N1BQR1K1/2brrpk1/4R1bB b KQkq - 0 1", "e8c8"),
    ("bug_14(离线污染帧)", "离线复现", "2RBQ3/1krrbb2/1KR4B/PPP1N2P/5P2/7q/3n3P/8 w KQkq - 0 1", "g8c8"),
]


def fen_sanity(fen: str):
    """返回问题清单; 空列表 = 结构合法"""
    problems = []
    board = fen.split(" ")[0]
    rows = board.split("/")
    wk = sum(row.count("K") for row in rows)
    bk = sum(row.count("k") for row in rows)
    if wk != 1:
        problems.append(f"白王数量={wk}")
    if bk != 1:
        problems.append(f"黑王数量={bk}")
    if rows[0].find("P") != -1 or rows[7].find("P") != -1 or rows[0].find("p") != -1 or rows[7].find("p") != -1:
        problems.append("兵出现在 1/8 横线")
    try:
        b = chess.Board(board + " w - - 0 1")
        kw, kb = b.king(chess.WHITE), b.king(chess.BLACK)
        if kw is not None and kb is not None and chess.square_distance(kw, kb) < 2:
            problems.append("双王相邻(非法局面)")
    except Exception:
        pass
    # 走子方王不应被对方将军 (FEN 第二字段)
    try:
        b = chess.Board(fen)
        side = chess.WHITE if b.turn == chess.WHITE else chess.BLACK
        if b.is_attacked_by(not side, b.king(side)):
            problems.append("走子方王正被将军(说明上一手非法或识别错色)")
    except ValueError as e:
        problems.append(f"FEN解析失败: {e}")
    return problems


def move_check(fen: str, uci: str):
    """检查着法合法性, 返回 (结论, 说明)"""
    try:
        b = chess.Board(fen)
        mv = chess.Move.from_uci(uci)
        if mv in b.legal_moves:
            return "合法", ""
        if mv in b.pseudo_legal_moves:
            return "伪合法", "走后会送王(被将军/王移动进攻击线)"
        piece = b.piece_at(mv.from_square)
        if piece is None:
            return "非法", f"起点 {chess.square_name(mv.from_square)} 无子"
        return "非法", f"{piece.symbol()} 从 {chess.square_name(mv.from_square)} 到 {chess.square_name(mv.to_square)} 不合走法几何"
    except Exception as e:
        return "异常", str(e)


def main():
    print("=" * 78)
    print("基础验证层: 识别 FEN 棋规合法性 + 建议着法合法性 (python-chess 权威规则库)")
    print("=" * 78)
    for bug, src, fen, move in CASES:
        print(f"\n[{bug}] 来源: {src}")
        print(f"  FEN: {fen}")
        problems = fen_sanity(fen)
        if problems:
            print(f"  FEN 棋规问题: {'; '.join(problems)}")
        else:
            print("  FEN 棋规: 通过 (双方各一王, 无相邻王, 兵线正常, 走子方王未被将军)")
        if move:
            verdict, note = move_check(fen, move)
            print(f"  建议着法 {move}: {verdict}{(' | ' + note) if note else ''}")
    print("\n[INFO] 基础验证输出完毕 (诊断用途, 非 CI 门禁)")


if __name__ == "__main__":
    main()
