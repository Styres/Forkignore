package com.chess.copilot.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * 封装 ONNX Runtime 推理引擎
 * 输入屏幕 Bitmap 与棋盘区域 Rect，直接输出标准 8x8 棋盘矩阵与 FEN 串
 */
class ChessOnnxEngine(context: Context) {

    private val ortEnv = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession

    init {
        val modelBytes = context.assets.open("duolingo_chess.onnx").readBytes()
        ortSession = ortEnv.createSession(modelBytes)
    }

    data class DetectionResult(
        val fen: String,
        val fullFen: String,
        val isWhitePerspective: Boolean,
        val rawBoard: Array<CharArray>,
        val boardRect: Rect
    )

    fun detect(bitmap: Bitmap, boardRect: Rect): DetectionResult {
        val step = (boardRect.right - boardRect.left) / 8.0f

        // 提取 64 个格子的 32x32 灰度 Tensor (64, 1, 32, 32)
        val floatBuffer = FloatBuffer.allocate(64 * 1 * 32 * 32)

        for (r in 0..7) {
            for (c in 0..7) {
                val x1 = (boardRect.left + c * step).toInt()
                val y1 = (boardRect.top + r * step).toInt()
                val x2 = (boardRect.left + (c + 1) * step).toInt()
                val y2 = (boardRect.top + (r + 1) * step).toInt()

                val cellW = max(1, x2 - x1)
                val cellH = max(1, y2 - y1)

                val cellBmp = Bitmap.createBitmap(bitmap, x1, y1, cellW, cellH)
                val resized = Bitmap.createScaledBitmap(cellBmp, 32, 32, true)

                val pixels = IntArray(32 * 32)
                resized.getPixels(pixels, 0, 32, 0, 0, 32, 32)

                for (p in pixels) {
                    val red = (p shr 16) and 0xFF
                    val green = (p shr 8) and 0xFF
                    val blue = p and 0xFF
                    val g = (0.299f * red + 0.587f * green + 0.114f * blue) / 255.0f
                    floatBuffer.put(g)
                }
            }
        }
        floatBuffer.rewind()

        val tensor = OnnxTensor.createTensor(
            ortEnv,
            floatBuffer,
            longArrayOf(64, 1, 32, 32)
        )

        val results = ortSession.run(mapOf("cell_images" to tensor))
        val classSymbols = charArrayOf('P', 'R', 'N', 'B', 'Q', 'K')

        val rawBoard = Array(8) { CharArray(8) { '.' } }
        var topWhite = 0
        var topBlack = 0
        var botWhite = 0
        var botBlack = 0

        for (r in 0..7) {
            for (c in 0..7) {
                val idx = r * 8 + c
                // 结合几何与网络输出构建符号
                val sym = 'P' // 示例占位符
                rawBoard[r][c] = sym

                if (r < 2) {
                    if (sym.isUpperCase()) topWhite++ else if (sym.isLowerCase()) topBlack++
                } else if (r >= 6) {
                    if (sym.isUpperCase()) botWhite++ else if (sym.isLowerCase()) botBlack++
                }
            }
        }

        val isWhitePerspective = botWhite >= botBlack
        val standardBoard = if (isWhitePerspective) {
            rawBoard
        } else {
            Array(8) { r -> CharArray(8) { c -> rawBoard[7 - r][7 - c] } }
        }

        val activeColor = if (isWhitePerspective) "w" else "b"

        val fenRows = mutableListOf<String>()
        for (row in standardBoard) {
            val sb = StringBuilder()
            var empty = 0
            for (sym in row) {
                if (sym == '.') {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(sym)
                }
            }
            if (empty > 0) sb.append(empty)
            fenRows.add(sb.toString())
        }

        val boardFen = fenRows.joinToString("/")
        val fullFen = "$boardFen $activeColor KQkq - 0 1"

        return DetectionResult(
            fen = boardFen,
            fullFen = fullFen,
            isWhitePerspective = isWhitePerspective,
            rawBoard = rawBoard,
            boardRect = boardRect
        )
    }

    fun close() {
        ortSession.close()
        ortEnv.close()
    }
}
