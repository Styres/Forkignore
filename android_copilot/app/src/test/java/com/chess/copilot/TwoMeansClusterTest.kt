package com.chess.copilot

import com.chess.copilot.core.ChessLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoMeansClusterTest {

    private fun generateSyntheticBoard(
        boardSize: Int = 800,
        lightVal: Float,
        darkVal: Float
    ): Pair<FloatArray, Int> {
        val w = boardSize
        val h = boardSize
        val gray = FloatArray(w * h)
        val cs = boardSize / 8.0f

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val isLight = (r + c) % 2 == 0
                val bg = if (isLight) lightVal else darkVal
                val x1 = (c * cs).toInt()
                val x2 = ((c + 1) * cs).toInt()
                val y1 = (r * cs).toInt()
                val y2 = ((r + 1) * cs).toInt()

                for (y in y1 until y2) {
                    for (x in x1 until x2) {
                        gray[y * w + x] = bg
                    }
                }
            }
        }
        return Pair(gray, w)
    }

    @Test
    fun testLightMode_syntheticBoard_perfectAlternationAndAllRowsPass() {
        // 浅色模式: 亮格 240, 暗格 180
        val (gray, size) = generateSyntheticBoard(boardSize = 800, lightVal = 240f, darkVal = 180f)
        val res = ChessLocator.computeRingAlternationDetailed(gray, size, size, 0f, 0f, size.toFloat())
        assertEquals(1.0f, res.totalScore, 0.001f)
        assertEquals(1.0f, res.minRowScore, 0.001f)
        assertTrue(res.allRowsPass)
    }

    @Test
    fun testDarkMode_syntheticBoard_perfectAlternationAndAllRowsPass() {
        // 深色模式: 亮格 48, 暗格 28 (量化低对比度)
        val (gray, size) = generateSyntheticBoard(boardSize = 800, lightVal = 48f, darkVal = 28f)
        val res = ChessLocator.computeRingAlternationDetailed(gray, size, size, 0f, 0f, size.toFloat())
        assertEquals(1.0f, res.totalScore, 0.001f)
        assertEquals(1.0f, res.minRowScore, 0.001f)
        assertTrue(res.allRowsPass)
    }

    @Test
    fun testDarkMode_shiftedBoard_minRowScoreRejectsShiftedBox() {
        // 创建深色模式背景 (高度 1200, 背景色 20, 棋盘在 y=200..1000)
        val w = 800
        val h = 1200
        val fullGray = FloatArray(w * h) { 20f } // 深色背景
        val (boardGray, boardSize) = generateSyntheticBoard(boardSize = 800, lightVal = 48f, darkVal = 28f)

        val boardY = 200
        for (y in 0 until boardSize) {
            System.arraycopy(boardGray, y * w, fullGray, (y + boardY) * w, w)
        }

        // 1. 真棋盘位置: 必须全行通过
        val trueRes = ChessLocator.computeRingAlternationDetailed(fullGray, w, h, 0f, boardY.toFloat(), boardSize.toFloat())
        assertTrue("True board in dark mode must pass all rows", trueRes.allRowsPass)
        assertEquals(1.0f, trueRes.totalScore, 0.001f)

        // 2. 向上偏移 1 格 (y = 100): 顶行溢出到背景色，必须被 allRowsPass 拦截
        val shiftedUpRes = ChessLocator.computeRingAlternationDetailed(fullGray, w, h, 0f, (boardY - 100).toFloat(), boardSize.toFloat())
        assertFalse("Shifted-up box must fail allRowsPass", shiftedUpRes.allRowsPass)
        assertTrue("Shifted-up box minRowScore must be <= 0.65", shiftedUpRes.minRowScore <= 0.65f)

        // 3. 向下偏移 1 格 (y = 300): 底行溢出到背景色，必须被 allRowsPass 拦截
        val shiftedDownRes = ChessLocator.computeRingAlternationDetailed(fullGray, w, h, 0f, (boardY + 100).toFloat(), boardSize.toFloat())
        assertFalse("Shifted-down box must fail allRowsPass", shiftedDownRes.allRowsPass)
        assertTrue("Shifted-down box minRowScore must be <= 0.65", shiftedDownRes.minRowScore <= 0.65f)
    }
}
