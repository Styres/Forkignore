package com.chess.copilot

import com.chess.copilot.core.ChessLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 ChessLocator 等差拟合在不同分辨率（400px, 1080p, 1260p, 1440p）下的尺度不变性测试用例
 */
class ChessLocatorScaleTest {

    @Test
    fun testTwoPassFit_400pxScale_passesGate() {
        // 400px 尺度：单格宽 50px，模拟网格线带有 1.5px 轻微噪声
        val stepEst = 50.0f
        val x0Est = 0.0f
        val peaks = listOf(
            51.0f,  // idx 1: 50 + 1.0
            99.0f,  // idx 2: 100 - 1.0
            151.5f, // idx 3: 150 + 1.5
            198.5f, // idx 4: 200 - 1.5
            251.0f, // idx 5: 250 + 1.0
            299.0f, // idx 6: 300 - 1.0
            351.0f  // idx 7: 350 + 1.0
        )

        val result = ChessLocator.twoPassFit(peaks, x0Est, stepEst)
        assertTrue("400px 尺度微小扰动应通过拟合", result.isOk)
        assertTrue("残差应在单格 5% 容忍度内 (2.5px)", result.residual <= stepEst * 0.05f)
        assertEquals(7, result.lineCount)
    }

    @Test
    fun testTwoPassFit_1260pScale_passesRelativeGate() {
        // 1260p 尺度（如 bug_16/bug_17）：单格宽 157.5px，拟合残差 3.5px
        // 旧版硬编码 2.5px 门禁会直接暴毙 (99f)，新版 5% 门禁 (7.875px) 应顺利通过
        val stepEst = 157.5f
        val x0Est = 0.0f
        val peaks = listOf(
            157.0f + 3.0f,  // idx 1: 160.0
            315.0f - 3.5f,  // idx 2: 311.5
            472.5f + 3.0f,  // idx 3: 475.5
            630.0f - 3.5f,  // idx 4: 626.5
            787.5f + 3.0f,  // idx 5: 790.5
            945.0f - 3.5f,  // idx 6: 941.5
            1102.5f + 3.0f  // idx 7: 1105.5
        )

        val result = ChessLocator.twoPassFit(peaks, x0Est, stepEst)
        assertTrue("1260p 高清屏幕下的等差网格应通过 5% 相对门禁", result.isOk)
        assertTrue("残差 3.2px 真实反映网格精度，且 <= 7.875px", result.residual <= stepEst * 0.05f)
        assertTrue("残差不应为退化的 99f", result.residual < 10.0f)
        assertEquals(7, result.lineCount)
    }

    @Test
    fun testTwoPassFit_RandomUiPeaks_rejected() {
        // 杂乱 UI 边缘伪峰：非等差分布，残差巨大
        val stepEst = 157.5f
        val x0Est = 0.0f
        val peaks = listOf(
            100.0f,
            280.0f,
            420.0f,
            750.0f,
            1100.0f
        )

        val result = ChessLocator.twoPassFit(peaks, x0Est, stepEst)
        assertFalse("非等差杂乱伪峰集应被门禁拦截", result.isOk)
    }
}
