package com.chess.copilot

import com.chess.copilot.core.UltraRobustClassifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 2-Means 自适应黑白阵营聚类算法的单元测试用例
 */
class TwoMeansClusterTest {

    @Test
    fun testBimodalDistributionConvergence() {
        // 模拟明显黑白棋子的中心平均亮度
        val pieceMeans = floatArrayOf(22.5f, 25.0f, 31.2f, 28.4f, 185.0f, 192.3f, 210.0f, 205.5f)

        val threshold = UltraRobustClassifier.calculateTwoMeansThreshold(pieceMeans)

        // 阈值应落在 31.2 和 185.0 之间（通常在 100~115 附近）
        assertTrue("Threshold $threshold should be > 35.0", threshold > 35.0f)
        assertTrue("Threshold $threshold should be < 180.0", threshold < 180.0f)

        // 验证黑白分类判定
        for (i in 0..3) {
            assertTrue("Index $i should be black", pieceMeans[i] < threshold)
        }
        for (i in 4..7) {
            assertTrue("Index $i should be white", pieceMeans[i] >= threshold)
        }
    }

    @Test
    fun testLowContrastDarkTheme() {
        // 模拟深色主题下的相对亮度分布
        val darkThemeMeans = floatArrayOf(50f, 55f, 60f, 110f, 115f, 120f)
        val threshold = UltraRobustClassifier.calculateTwoMeansThreshold(darkThemeMeans)

        assertTrue(threshold > 60f)
        assertTrue(threshold < 110f)
    }

    @Test
    fun testSingleColorWhiteEndgame() {
        // 棋盘上只有白棋的单色残局 (如白王白后残局)
        val allWhiteMeans = floatArrayOf(190f, 195f, 205f)
        val threshold = UltraRobustClassifier.calculateTwoMeansThreshold(allWhiteMeans)
        for (m in allWhiteMeans) {
            assertTrue("All pieces should be classified as white", m >= threshold)
        }
    }

    @Test
    fun testSingleColorBlackEndgame() {
        // 棋盘上只有黑棋的单色残局
        val allBlackMeans = floatArrayOf(25f, 30f, 35f)
        val threshold = UltraRobustClassifier.calculateTwoMeansThreshold(allBlackMeans)
        for (m in allBlackMeans) {
            assertTrue("All pieces should be classified as black", m < threshold)
        }
    }
}
