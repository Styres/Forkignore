package com.chess.copilot

import com.chess.copilot.engine.StockfishBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 UCI 引擎输出行正则与状态机解析的单元测试用例
 */
class UciProtocolParserTest {

    @Test
    fun testParseBestMoveStandard() {
        val line1 = "bestmove e2e4 ponder e7e5"
        val move1 = StockfishBridge.parseBestMoveLine(line1)
        assertEquals("e2e4", move1)

        val line2 = "bestmove g8f6"
        val move2 = StockfishBridge.parseBestMoveLine(line2)
        assertEquals("g8f6", move2)

        val line3 = "bestmove (none)"
        val move3 = StockfishBridge.parseBestMoveLine(line3)
        assertEquals("(none)", move3)
    }

    @Test
    fun testParseBestMovePromotion() {
        // 关键用例：兵升变 (Promotion)
        val lineQueen = "bestmove e7e8q ponder d8d7"
        assertEquals("e7e8q", StockfishBridge.parseBestMoveLine(lineQueen))

        val lineKnight = "bestmove a2a1n"
        assertEquals("a2a1n", StockfishBridge.parseBestMoveLine(lineKnight))

        val lineRook = "bestmove b7b8r"
        assertEquals("b7b8r", StockfishBridge.parseBestMoveLine(lineRook))

        val lineBishop = "bestmove h2h1b"
        assertEquals("h2h1b", StockfishBridge.parseBestMoveLine(lineBishop))
    }

    @Test
    fun testParseInfoScoreCp() {
        val infoLine = "info depth 14 seldepth 20 multipv 1 score cp 58 nodes 12345 nps 456789 time 27 pv e2e4 e7e5 g1f3"
        val eval = StockfishBridge.parseInfoLine(infoLine)

        assertEquals(14, eval?.depth)
        assertEquals(0.58f, eval?.evalScore ?: 0f, 0.001f)
        assertFalse(eval?.isMate ?: true)
    }

    @Test
    fun testParseInfoScoreNegativeCp() {
        val infoLine = "info depth 12 score cp -120 pv d7d5"
        val eval = StockfishBridge.parseInfoLine(infoLine)

        assertEquals(12, eval?.depth)
        assertEquals(-1.20f, eval?.evalScore ?: 0f, 0.001f)
    }

    @Test
    fun testParseInfoScoreMate() {
        // 白方即将 2 步杀王
        val mateLinePositive = "info depth 16 score mate 2 pv f7f8q"
        val evalPos = StockfishBridge.parseInfoLine(mateLinePositive)

        assertTrue(evalPos?.isMate ?: false)
        assertEquals(100.0f, evalPos?.evalScore ?: 0f, 0.001f)

        // 黑方将被杀棋
        val mateLineNegative = "info depth 16 score mate -3 pv g8h8"
        val evalNeg = StockfishBridge.parseInfoLine(mateLineNegative)

        assertTrue(evalNeg?.isMate ?: false)
        assertEquals(-100.0f, evalNeg?.evalScore ?: 0f, 0.001f)
    }

    @Test
    fun testFallbackEvaluatorFindsExistingPieceMove() {
        // 测试降级评估器在真实盘面上绝不指向空格
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val eval = StockfishBridge.evaluateFallback(fen)
        assertNotNull(eval.bestMove)
        assertTrue(eval.bestMove.length in 4..5)
        
        val fromCol = eval.bestMove[0] - 'a'
        val fromRank = eval.bestMove[1] - '1'
        // 验证移动的起点必须存在己方棋子
        val rows = fen.split(" ")[0].split("/")
        val board = Array(8) { r ->
            val rowStr = rows[r]
            val expanded = StringBuilder()
            for (ch in rowStr) {
                if (ch.isDigit()) repeat(ch - '0') { expanded.append('.') }
                else expanded.append(ch)
            }
            expanded.toString().toCharArray()
        }
        val pieceAtFrom = board[7 - fromRank][fromCol]
        assertTrue("Piece at from square should be white", pieceAtFrom.isUpperCase())
    }

    @Test
    fun testFallbackBishopMovesDiagonally() {
        // 白方只有一个主教在 g5，验证走法必须为斜向 (绝对不能出现 g5g6 等直冲走法)
        val fen = "8/8/8/6B1/8/8/8/4K2k w - - 0 1"
        val eval = StockfishBridge.evaluateFallback(fen)
        assertEquals(0, eval.depth)
        assertEquals(0.0f, eval.evalScore, 0.001f)
        
        val fromSquare = eval.bestMove.substring(0, 2)
        val toSquare = eval.bestMove.substring(2, 4)
        assertEquals("g5", fromSquare)
        
        val dc = Math.abs(toSquare[0] - fromSquare[0])
        val dr = Math.abs(toSquare[1] - fromSquare[1])
        assertTrue("Bishop move must be diagonal (dc == dr)", dc == dr && dc > 0)
    }

    @Test
    fun testInvalidOrIrrelevantLines() {
        val invalidLine = "info currmove e2e4 currmovenumber 1"
        assertEquals(null, StockfishBridge.parseBestMoveLine(invalidLine))
    }
}
