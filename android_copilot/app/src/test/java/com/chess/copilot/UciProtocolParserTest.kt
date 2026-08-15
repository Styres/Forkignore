package com.chess.copilot

import com.chess.copilot.engine.StockfishBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun testInvalidOrIrrelevantLines() {
        val invalidLine = "info currmove e2e4 currmovenumber 1"
        val eval = StockfishBridge.parseInfoLine(invalidLine)
        // 没有 score 和 depth 时应安全返回 null 或不崩溃
        // 验证容错性
        assertEquals(null, StockfishBridge.parseBestMoveLine(invalidLine))
    }
}
