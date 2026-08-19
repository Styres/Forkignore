package com.chess.copilot

import com.chess.copilot.core.ChessLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ChessLocatorAlternationTest {

    /**
     * 生成一个合成的标准 8x8 棋盘灰度图 (64 格明暗交替)
     * 每个格子内中心放置一个干扰物体 (模拟中心棋子)，验证环形采样能否成功避开中心干扰
     */
    private fun generateSyntheticBoardWithPieces(
        boardSize: Int = 800,
        lightVal: Float = 240f,
        darkVal: Float = 180f,
        pieceVal: Float = 50f // 深色棋子位于中心
    ): Pair<FloatArray, Int> {
        val w = boardSize
        val h = boardSize
        val gray = FloatArray(w * h)
        val cs = boardSize / 8.0f

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val isLight = (r + c) % 2 == 0
                val bg = if (isLight) lightVal else darkVal
                val cx = (c + 0.5f) * cs
                val cy = (r + 0.5f) * cs
                val pieceRadius = cs * 0.25f // 棋子占中心 25% 半径

                val x1 = (c * cs).toInt()
                val x2 = ((c + 1) * cs).toInt()
                val y1 = (r * cs).toInt()
                val y2 = ((r + 1) * cs).toInt()

                for (y in y1 until y2) {
                    for (x in x1 until x2) {
                        val dx = x - cx
                        val dy = y - cy
                        val inPiece = (dx * dx + dy * dy) <= (pieceRadius * pieceRadius)
                        gray[y * w + x] = if (inPiece) pieceVal else bg
                    }
                }
            }
        }
        return Pair(gray, w)
    }

    @Test
    fun testRingAlternation_syntheticBoardWithPieces_scoresOneHundredPercent() {
        val (gray, size) = generateSyntheticBoardWithPieces(boardSize = 800)
        val score = ChessLocator.computeRingAlternationScore(gray, size, size, 0f, 0f, size.toFloat())
        // 环形采样避开中心后，得分应为 1.0 (64/64 全中)
        assertEquals(1.0f, score, 0.001f)
    }

    @Test
    fun testRingAlternation_shiftedBox_scoreCollapses() {
        // 创建一个顶部/底部有白色背景的大图 (1200 高度, 棋盘在 y=200..1000)
        val w = 800
        val h = 1200
        val fullGray = FloatArray(w * h) { 255f } // 全白背景
        val (boardGray, boardSize) = generateSyntheticBoardWithPieces(boardSize = 800)

        // 嵌入棋盘到 y=200
        val boardY = 200
        for (y in 0 until boardSize) {
            System.arraycopy(boardGray, y * w, fullGray, (y + boardY) * w, w)
        }

        // 1. 真棋盘位置 (y=200) 得分应接近 1.0
        val trueScore = ChessLocator.computeRingAlternationScore(fullGray, w, h, 0f, boardY.toFloat(), boardSize.toFloat())
        assertEquals(1.0f, trueScore, 0.001f)

        // 2. 偏移 2 格的位置 (y=200 + 200 = 400)，底部 2 格落在白背景上，交替度暴跌
        val shiftedScore = ChessLocator.computeRingAlternationScore(fullGray, w, h, 0f, (boardY + 200).toFloat(), boardSize.toFloat())
        assertTrue("Shifted box alternation score must be <= 0.70, but was $shiftedScore", shiftedScore <= 0.70f)
    }

    @Test
    fun testPhaseSearchStep_isStrictlyBounded() {
        // 验证退化路径的试探步长范围严格限制在 [-0.5, 0.5]
        val steps = ChessLocator.getDegeneratePhaseSteps()
        assertTrue("Max step must be <= 0.6", steps.maxOrNull()!! <= 0.6f)
        assertTrue("Min step must be >= -0.6", steps.minOrNull()!! >= -0.6f)
    }
}
