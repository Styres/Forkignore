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
            charArrayOf('P', 'P', 'P', '.', 'P', 'P', 'P', 'P'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', 'P', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('r', 'n', 'b', 'k', 'q', 'b', 'n', 'r')
        )

        val result = UltraRobustClassifier.buildFenFromBoard(rawScreenBoard, isWhitePerspective = false)
        assertFalse(result.isWhitePerspective)
        assertEquals("b", result.activeColor)
        assertEquals("rnbqkbnr", result.boardFen.split('/')[0])
        assertEquals("RNBQKBNR", result.boardFen.split('/')[7])
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
        // 关键用例：测试当识别结果中白王数量为 0 时，算法自动在白方底线回填国王
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
        // 关键用例：测试某类子力（如白后超过 1 个、白兵超过 8 个）时的严格超限降级
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
        assertEquals("White queen count should strictly be 1", 1, whiteQueenCount)
    }

    @Test
    fun testRowConservation() {
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
                if (ch.isDigit()) sum += ch - '0'
                else sum += 1
            }
            assertEquals(8, sum)
        }
    }

    @Test
    fun testBuildFenWithTelemetryAndPerspectiveLock() {
        // 残局局面：白方兵子大举挺进中路，底线仅剩白王，黑后与黑车杀入白方底线 (底线黑子比白子多)
        val endgameBoard = arrayOf(
            charArrayOf('r', '.', '.', '.', 'k', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', 'P', '.', '.', 'P', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('q', '.', '.', '.', 'K', '.', '.', 'r')
        )

        // 验证 1: 带有底层 telemetry 参数的 buildFenFromBoard
        val result = UltraRobustClassifier.buildFenFromBoard(
            rawBoard = endgameBoard,
            isWhitePerspective = true, // 会话视角锁定为执白
            medianSim = 0.985f,
            occupiedCount = 6
        )

        assertEquals("w", result.activeColor)
        assertTrue(result.isWhitePerspective)
        assertEquals(0.985f, result.medianSim, 0.001f)
        assertEquals(6, result.occupiedCount)
        assertEquals("r3k3/8/8/2P2P2/8/8/8/q3K2r", result.boardFen)
    }
}
