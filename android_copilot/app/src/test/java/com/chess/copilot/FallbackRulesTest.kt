package com.chess.copilot

import com.chess.copilot.engine.StockfishBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 针对 Stockfish 兜底 FIDE 合法规则模拟器、将军防守、绝对牵制 (Pin)、将杀/逼和与纯函数 Zip 提取的单元测试
 */
class FallbackRulesTest {

    @Test
    fun testInCheckDefenseOnlyBug7() {
        // bug_7.jpg 真实对局残局：黑后在 e4 直接抽将白王 (e1)
        // 该局面白方无后且 b1 马无法到达 e2，合法解将仅有：Be2 垫 e2、Be3 垫 e3、Kd1/Kd2 避将
        // 绝对禁止走无关兵 f5f6 等送王走法
        val fenBug7 = "r1b1k1nr/pppp1ppp/2n5/5P2/3bq3/8/PPP3PP/RNB1KB1R w KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(fenBug7)

        val legalDefenseMoves = setOf(
            "f1e2", // Be2 (主教挡将, 垫 e2)
            "c1e3", // Be3 (主教挡将, 垫 e3)
            "e1d1", // Kd1 (王避将)
            "e1d2"  // Kd2 (王避将)
        )

        println("testInCheckDefenseOnlyBug7 bestMove=${eval.bestMove}")
        assertTrue("生成的走法 ${eval.bestMove} 必须属于合法解将走法集合 $legalDefenseMoves", legalDefenseMoves.contains(eval.bestMove))
        assertNotEquals("f5f6", eval.bestMove)
        assertEquals(0, eval.depth)
    }

    @Test
    fun testPinnedPieceCannotLeaveKingLineBug6() {
        // bug_6.jpg 真实对局残局：白主教在 e2 替 e1 白王阻挡 e4 黑后 (绝对牵制 Pin)
        // 白主教不能走 e2f3，因为走后白王将直接暴露在黑后射线上
        val fenBug6 = "r3k1nr/ppp2ppp/2np1p2/8/3bq1b1/8/PPP1B1PP/RNB1K2R w KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(fenBug6)

        println("testPinnedPieceCannotLeaveKingLineBug6 bestMove=${eval.bestMove}")
        assertNotEquals("e2f3", eval.bestMove)
    }

    @Test
    fun testNonCheckLegalMovesNotOverFiltered() {
        // 初始标准局面：白方必须至少有 20 种合法走法 (16 种兵走法 + 4 种马走法)
        val initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(initialFen)

        println("testNonCheckLegalMovesNotOverFiltered bestMove=${eval.bestMove}")
        assertTrue(eval.bestMove.length in 4..5)
        assertFalse(eval.isMate)
    }

    @Test
    fun testCheckmateHandling() {
        // 经典的愚者将杀 (Fool's Mate) 终局：白王在 e1 被黑后在 h4 将死，无任何合法解将走法
        val foolsMateFen = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(foolsMateFen)

        println("testCheckmateHandling bestMove=${eval.bestMove}, isMate=${eval.isMate}, score=${eval.evalScore}")
        assertEquals("(checkmate)", eval.bestMove)
        assertTrue(eval.isMate)
        assertEquals(-100.0f, eval.evalScore, 0.001f)
    }

    @Test
    fun testStalemateHandling() {
        // 经典的逼和局面：黑王在 a8，白王在 c7，白后在 b6。黑方轮走，未被将军但无任何合法走法
        val stalemateFen = "k7/2K5/1Q6/8/8/8/8/8 b - - 0 1"
        val eval = StockfishBridge.evaluateFallback(stalemateFen)

        println("testStalemateHandling bestMove=${eval.bestMove}, isMate=${eval.isMate}, score=${eval.evalScore}")
        assertEquals("(stalemate)", eval.bestMove)
        assertFalse(eval.isMate)
        assertEquals(0.0f, eval.evalScore, 0.001f)
    }

    @Test
    fun testBug8Bug10RealGameNoFalseCheckFiltering() {
        // bug_8.jpg / bug_10.jpg 真机局面 (OCR 视角: 执黑): 黑王 e8 未被将军
        // (Qg4 的 g 线被黑方 g7 兵自挡，斜线被 d7 兵挡)，真机兜底确定性输出 b8a8 (rxa8)
        // 回归目标: 试走过滤器不得误判该局面为被将/误杀合法走法，且非法王走法 (走入 Bd6 射线) 必须被剔除
        val fenBug8 = "Nrb1k2r/pp1p2pp/3B1p2/3Bp3/4P1Q1/8/PPP2P1P/RN1R2K1 b KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(fenBug8)

        val legalMoves = setOf(
            "b8a8", "e8d8", "h8g8", "h8f8",
            "a7a6", "a7a5", "b7b6", "b7b5",
            "g7g6", "g7g5", "h7h6", "h7h5", "f6f5"
        )
        val illegalKingMoves = setOf("e8e7", "e8f8", "e8f7")

        println("testBug8Bug10RealGameNoFalseCheckFiltering bestMove=${eval.bestMove}")
        assertTrue("生成的走法 ${eval.bestMove} 必须属于合法走法集合 $legalMoves", legalMoves.contains(eval.bestMove))
        assertFalse("不得输出走入 Bd6 射线的非法王走法", illegalKingMoves.contains(eval.bestMove))
        assertEquals("真机 bug_8/bug_10 确定性输出应为 b8a8", "b8a8", eval.bestMove)
        assertEquals(0, eval.depth)
        assertFalse(eval.isMate)
    }

    @Test
    fun testExtractBinaryFromZipSynthetic() {
        // 纯内存合成包含 lib/arm64-v8a/libstockfish.so 的测试 Zip 文件
        val tempZipFile = File.createTempFile("test_apk", ".zip")
        val targetBinFile = File.createTempFile("test_libstockfish", ".so")
        tempZipFile.deleteOnExit()
        targetBinFile.deleteOnExit()

        val dummyContent = "Stockfish-Synthetic-Binary-Data-For-JVM-Test".toByteArray()

        ZipOutputStream(tempZipFile.outputStream()).use { zos ->
            val entry = ZipEntry("lib/arm64-v8a/libstockfish.so")
            zos.putNextEntry(entry)
            zos.write(dummyContent)
            zos.closeEntry()
        }

        val zip = ZipFile(tempZipFile)
        val extracted = StockfishBridge.extractBinaryFromZip(
            zip = zip,
            supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a"),
            targetFile = targetBinFile
        )
        zip.close()

        assertTrue("Synthetic zip extraction must succeed", extracted)
        assertEquals(dummyContent.size.toLong(), targetBinFile.length())
        assertEquals(String(dummyContent), targetBinFile.readText())
    }
}
