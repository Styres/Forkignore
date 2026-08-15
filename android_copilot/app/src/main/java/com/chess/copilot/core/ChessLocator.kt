package com.chess.copilot.core

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 基于 8 周期梳状谐振滤波器 (Comb Filter Resonator) 的两阶段自适应棋盘定位器
 * 第一阶段（粗扫）：在降采样网格中大步长快速定位棋盘区域与底边
 * 第二阶段（精修）：在候选区域邻域以细步长精确锁定像素级包围盒
 */
object ChessLocator {

    fun locateBoard(bitmap: Bitmap): Rect {
        val width = bitmap.width
        val height = bitmap.height

        // 提取灰度像素并计算垂直梯度
        val gray = extractGrayscale(bitmap, width, height)
        val gy = computeVerticalGradient(gray, width, height)

        val isPortrait = width <= height
        val minSize = if (isPortrait) (width * 0.82f).toInt() else (height * 0.65f).toInt()
        val maxSize = if (isPortrait) min(width - 2, (width * 0.98f).toInt()) else min(height - 2, (height * 0.96f).toInt())

        // 阶段一：粗扫 (Coarse Search)
        val coarseStepS = max(6, (maxSize - minSize) / 20)
        val coarseStepY = max(6, (height - minSize) / 40)
        var bestScore = -1e9f
        var bestSize = minSize
        var bestY = 0

        for (size in minSize..maxSize step coarseStepS) {
            val x = (width - size) / 2
            val maxY = height - size
            for (y in 0..maxY step coarseStepY) {
                val score = evaluatePositionScore(gray, gy, width, height, x, y, size)
                if (score > bestScore) {
                    bestScore = score
                    bestSize = size
                    bestY = y
                }
            }
        }

        // 阶段二：精修 (Fine Refinement in ±16px window)
        val fineMinSize = max(minSize, bestSize - 16)
        val fineMaxSize = min(maxSize, bestSize + 16)
        val fineMinY = max(0, bestY - 16)
        val fineMaxY = min(height - bestSize, bestY + 16)

        var exactScore = bestScore
        var exactRect = Rect((width - bestSize) / 2, bestY, (width - bestSize) / 2 + bestSize, bestY + bestSize)

        for (size in fineMinSize..fineMaxSize step 2) {
            val x = (width - size) / 2
            val currentMaxY = min(fineMaxY, height - size)
            for (y in fineMinY..currentMaxY step 2) {
                val score = evaluatePositionScore(gray, gy, width, height, x, y, size)
                if (score > exactScore) {
                    exactScore = score
                    exactRect = Rect(x, y, x + size, y + size)
                }
            }
        }

        return exactRect
    }

    private fun extractGrayscale(bitmap: Bitmap, width: Int, height: Int): FloatArray {
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
        return gray
    }

    private fun computeVerticalGradient(gray: FloatArray, width: Int, height: Int): FloatArray {
        val gy = FloatArray(width * height)
        for (y in 1 until height - 1) {
            val yOffset = y * width
            val yPrevOffset = (y - 1) * width
            val yNextOffset = (y + 1) * width
            for (x in 0 until width) {
                gy[yOffset + x] = abs(gray[yNextOffset + x] - gray[yPrevOffset + x])
            }
        }
        return gy
    }

    private fun evaluatePositionScore(
        gray: FloatArray,
        gy: FloatArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        size: Int
    ): Float {
        val gridStep = size / 8.0f
        var hPeak = 0.0f
        var hValley = 0.0f

        // 8 谐波峰谷边缘能量
        for (i in 1..7) {
            val lineY = (y + i * gridStep).toInt()
            val midY = (y + (i - 0.5f) * gridStep).toInt()

            if (lineY in 0 until height) {
                var sum = 0.0f
                val rowOffset = lineY * width
                for (cx in x until (x + size)) {
                    sum += gy[rowOffset + cx]
                }
                hPeak += sum / size
            }

            if (midY in 0 until height) {
                var sum = 0.0f
                val rowOffset = midY * width
                for (cx in x until (x + size)) {
                    sum += gy[rowOffset + cx]
                }
                hValley += sum / size
            }
        }

        // 8x8 格子角点交替互相关
        val gridMeans = FloatArray(64)
        for (r in 0..7) {
            val cy1 = (y + r * gridStep).toInt()
            val cy2 = (y + (r + 1) * gridStep).toInt()
            for (c in 0..7) {
                val cx1 = (x + c * gridStep).toInt()
                val cx2 = (x + (c + 1) * gridStep).toInt()

                var cellSum = 0.0f
                var count = 0
                val stepY = max(1, (cy2 - cy1) / 3)
                val stepX = max(1, (cx2 - cx1) / 3)
                for (py in cy1 until cy2 step stepY) {
                    val rowOffset = py * width
                    for (px in cx1 until cx2 step stepX) {
                        cellSum += gray[rowOffset + px]
                        count++
                    }
                }
                gridMeans[r * 8 + c] = if (count > 0) cellSum / count else 0f
            }
        }

        var meanVal = 0.0f
        for (v in gridMeans) meanVal += v
        meanVal /= 64.0f

        var corrSum = 0.0f
        for (r in 0..7) {
            for (c in 0..7) {
                val pattern = if ((r + c) % 2 == 0) 1.0f else -1.0f
                corrSum += (gridMeans[r * 8 + c] - meanVal) * pattern
            }
        }

        return (hPeak - hValley * 0.7f) * 0.8f + abs(corrSum) * 1.5f
    }
}
