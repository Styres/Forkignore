package com.chess.copilot

import com.chess.copilot.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 8x8 棋盘矩阵压缩为 FEN 串、视角翻转、双王守恒、Rank 1/8 禁兵与数量约束的单元测试用例
 */
class ChessFenBuilderTest {

    @Test
    fun testInitialPositionWhitePerspective() {
        val board = arrayOf(
            charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
        )

        val result = UltraRobustClassifier.buildFenFromBoard(board, isWhitePerspective = true)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", result.boardFen)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", result.fullFen)
        assertTrue(result.isWhitePerspective)
    }

    @Test
    fun testBlackPerspectiveFlipping() {
        val rawScreenBoard = arrayOf(
            charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R'),
            charArrayOf('P', 'P', 'P', 'P', '.', 'P', 'P', 'P'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', 'P', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('r', 'n', 'b', 'k', 'q', 'b', 'n', 'r')
        )

        val result = UltraRobustClassifier.buildFenFromBoard(rawScreenBoard, isWhitePerspective = false)
        assertFalse(result.isWhitePerspective)
        assertEquals("b", result.activeColor)
        assertTrue(result.boardFen.startsWith("rnb"))
        assertTrue(result.boardFen.endsWith("RNB") || result.boardFen.endsWith("R") || result.boardFen.endsWith("NR"))
    }

    @Test
    fun testEmptySquareRowCompression() {
        val row = charArrayOf('.', '.', '.', 'p', '.', '.', '.', '.')
        val compressed = UltraRobustClassifier.compressRow(row)
        assertEquals("3p4", compressed)

        val fullEmptyRow = charArrayOf('.', '.', '.', '.', '.', '.', '.', '.')
        assertEquals("8", UltraRobustClassifier.compressRow(fullEmptyRow))
    }

    @Test
    fun testZeroKingRecovery() {
        // 关键用例：测试当识别结果中白王数量为 0 时（例如被切掉或误判为空格），算法能否在白方底线自动回填国王
        val boardWithNoWhiteKing = arrayOf(
            charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
            charArrayOf('R', 'N', 'B', 'Q', '.', 'B', 'N', 'R') // 白王位置为空格
        )

        val sanitized = UltraRobustClassifier.sanitizeBoard(boardWithNoWhiteKing)
        var whiteKingCount = 0
        var blackKingCount = 0
        for (r in 0..7) {
            for (c in 0..7) {
                if (sanitized[r][c] == 'K') whiteKingCount++
                if (sanitized[r][c] == 'k') blackKingCount++
            }
        }
        assertEquals(1, whiteKingCount)
        assertEquals(1, blackKingCount)
    }

    @Test
    fun testRank1AndRank8IllegalPawnCleaning() {
        // 关键用例：国际象棋中 Rank 1 和 Rank 8 绝对不能有兵（Lichess 硬性拒绝），测试清洗机制
        val boardWithIllegalPawns = arrayOf(
            charArrayOf('r', 'n', 'b', 'P', 'k', 'b', 'n', 'r'), // Rank 8 出现白兵 P
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'p', 'R')  // Rank 1 出现黑兵 p
        )

        val sanitized = UltraRobustClassifier.sanitizeBoard(boardWithIllegalPawns)
        // 验证 Rank 8 (row 0) 和 Rank 1 (row 7) 无任何兵
        for (c in 0..7) {
            assertFalse("Rank 8 cannot have white pawn", sanitized[0][c] == 'P')
            assertFalse("Rank 8 cannot have black pawn", sanitized[0][c] == 'p')
            assertFalse("Rank 1 cannot have white pawn", sanitized[7][c] == 'P')
            assertFalse("Rank 1 cannot have black pawn", sanitized[7][c] == 'p')
        }
    }

    @Test
    fun testPieceCountMaxLimits() {
        // 关键用例：测试某类子力（如白后超过 1 个、白兵超过 8 个）时的超限降级
        val boardWithThreeQueens = arrayOf(
            charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('.', '.', 'Q', '.', 'Q', '.', '.', '.'), // 多余的后
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
        )

        val sanitized = UltraRobustClassifier.sanitizeBoard(boardWithThreeQueens)
        var whiteQueenCount = 0
        for (r in 0..7) {
            for (c in 0..7) {
                if (sanitized[r][c] == 'Q') whiteQueenCount++
            }
        }
        assertTrue("White queen count should be <= 2", whiteQueenCount <= 2)
    }

    @Test
    fun testRowConservation() {
        // 验证生成 FEN 每一行求和严格守恒等于 8
        val board = arrayOf(
            charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
            charArrayOf('.', 'p', '.', 'p', '.', 'p', '.', 'p'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', 'P', '.', '.', '.'),
            charArrayOf('.', '.', '.', 'p', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', '.', 'P', '.', 'P', '.', 'P', '.'),
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
        )

        val result = UltraRobustClassifier.buildFenFromBoard(board, isWhitePerspective = true)
        val ranks = result.boardFen.split('/')
        assertEquals(8, ranks.size)
        for (rk in ranks) {
            var sum = 0
            for (ch in rk) {
                if (ch.isdigit()) sum += ch - '0'
                else sum += 1
            }
            assertEquals(8, sum)
        }
    }
}
