package com.chess.copilot.core

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 基于 8 周期梳状谐振滤波器 (Comb Filter Resonator) 的 Android 棋盘自适应定位器
 * 1:1 移植自已在 6 张全场景多邻国截图中验证通过的算法
 */
object ChessLocator {

    fun locateBoard(bitmap: Bitmap): Rect {
        val width = bitmap.width
        val height = bitmap.height

        // 提取灰度像素数组
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = FloatArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 计算水平和垂直方向梯度
        val gy = FloatArray(width * height)
        for (y in 1 until height - 1) {
            for (x in 0 until width) {
                gy[y * width + x] = abs(gray[(y + 1) * width + x] - gray[(y - 1) * width + x])
            }
        }

        // 搜索尺寸
        val isPortrait = width <= height
        val minSize = if (isPortrait) (width * 0.85f).toInt() else (height * 0.70f).toInt()
        val maxSize = if (isPortrait) min(width - 2, (width * 0.98f).toInt()) else min(height - 2, (height * 0.95f).toInt())

        var bestScore = -1e9f
        var bestRect = Rect(0, 0, width, width)

        val stepS = max(4, (maxSize - minSize) / 25)
        for (size in minSize..maxSize step stepS) {
            val gridStep = size / 8.0f
            val x = (width - size) / 2

            val stepY = max(4, (height - size) / 50)
            for (y in 0..(height - size) step stepY) {
                var hPeak = 0.0f
                var hValley = 0.0f

                for (i in 1..7) {
                    val lineY = (y + i * gridStep).toInt()
                    val midY = (y + (i - 0.5f) * gridStep).toInt()

                    if (lineY in 0 until height) {
                        var sum = 0.0f
                        for (cx in x until (x + size)) {
                            sum += gy[lineY * width + cx]
                        }
                        hPeak += sum / size
                    }

                    if (midY in 0 until height) {
                        var sum = 0.0f
                        for (cx in x until (x + size)) {
                            sum += gy[midY * width + cx]
                        }
                        hValley += sum / size
                    }
                }

                // 8x8 格子角点交替方差
                var corrSum = 0.0f
                val gridMeans = FloatArray(64)
                for (r in 0..7) {
                    val cy1 = (y + r * gridStep).toInt()
                    val cy2 = (y + (r + 1) * gridStep).toInt()
                    for (c in 0..7) {
                        val cx1 = (x + c * gridStep).toInt()
                        val cx2 = (x + (c + 1) * gridStep).toInt()

                        var cellSum = 0.0f
                        var count = 0
                        for (py in cy1 until cy2 step max(1, (cy2 - cy1) / 3)) {
                            for (px in cx1 until cx2 step max(1, (cx2 - cx1) / 3)) {
                                cellSum += gray[py * width + px]
                                count++
                            }
                        }
                        gridMeans[r * 8 + c] = if (count > 0) cellSum / count else 0f
                    }
                }

                var meanVal = 0.0f
                for (v in gridMeans) meanVal += v
                meanVal /= 64.0f

                for (r in 0..7) {
                    for (c in 0..7) {
                        val pattern = if ((r + c) % 2 == 0) 1.0f else -1.0f
                        corrSum += (gridMeans[r * 8 + c] - meanVal) * pattern
                    }
                }

                val score = (hPeak - hValley * 0.7f) * 0.8f + abs(corrSum) * 1.5f
                if (score > bestScore) {
                    bestScore = score
                    bestRect = Rect(x, y, x + size, y + size)
                }
            }
        }

        return bestRect
    }
}
