package com.chess.copilot

import com.chess.copilot.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 8x8 棋盘矩阵压缩为 FEN 串以及黑白视角翻转的单元测试用例
 */
class ChessFenBuilderTest {

    @Test
    fun testInitialPositionWhitePerspective() {
        // 白方视角标准初始局面
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
        // 黑方视角：屏幕顶部是白方底线，屏幕底部是黑方底线
        // 当白方走了 e4 (在屏幕上表现为第 4 行，第 4 列有 'P')
        val rawScreenBoard = arrayOf(
            charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R'), // 屏幕第 0 行对应白方底线
            charArrayOf('P', 'P', 'P', 'P', '.', 'P', 'P', 'P'), // 屏幕第 1 行
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', 'P', '.', '.', '.'), // 白兵到 e4
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'), // 屏幕第 6 行
            charArrayOf('r', 'n', 'b', 'k', 'q', 'b', 'n', 'r')  // 屏幕第 7 行对应黑方底线
        )

        val result = UltraRobustClassifier.buildFenFromBoard(rawScreenBoard, isWhitePerspective = false)
        // 翻转 180 度后，顶部变为黑方 (rank 8)，底部变为白方 (rank 1)
        assertFalse(result.isWhitePerspective)
        assertEquals("b", result.activeColor)
        // 验证标准 FEN 的第 8 阶（黑方底线）和第 1 阶（白方底线）
        assertTrue(result.boardFen.startsWith("rnbqkb"))
        assertTrue(result.boardFen.endsWith("RNBQKBNR") || result.boardFen.endsWith("RNBKQBNR"))
    }

    @Test
    fun testEmptySquareRowCompression() {
        // 测试单行含多个连续空格的数字压缩算法
        val row = charArrayOf('.', '.', '.', 'p', '.', '.', '.', '.')
        val compressed = UltraRobustClassifier.compressRow(row)
        assertEquals("3p4", compressed)

        val fullEmptyRow = charArrayOf('.', '.', '.', '.', '.', '.', '.', '.')
        assertEquals("8", UltraRobustClassifier.compressRow(fullEmptyRow))
    }

    @Test
    fun testKingConstraintValidation() {
        // 校验双方 King 数量限制
        val boardWithTwoWhiteKings = arrayOf(
            charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', 'K', '.', '.', '.', '.', '.'), // 多余的王
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
        )

        val sanitized = UltraRobustClassifier.sanitizeBoard(boardWithTwoWhiteKings)
        var whiteKingCount = 0
        for (r in 0..7) {
            for (c in 0..7) {
                if (sanitized[r][c] == 'K') whiteKingCount++
            }
        }
        assertEquals(1, whiteKingCount)
    }
}
