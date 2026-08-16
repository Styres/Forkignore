package com.chess.copilot.core

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 自研高精度 2D 棋盘自适应定位器 (Fast Integral-SAT Checkerboard Locator)
 * 核心机制：
 * 1. 积分图 (Summed-Area Table) 实现 O(1) 任意网格线与角落区域能量计算
 * 2. 8x8 格子 4 角采样（18% 边角）完全规避中心棋子遮挡
 * 3. 融合多邻国垂直布局合理性先验 (70%~98%)，彻底抑制顶部卡通立绘与底部按钮干扰
 * 4. 粗扫 (step=4) + 精修 (step=1) 两阶段毫秒级像素对齐
 */
object ChessLocator {

    /**
     * 定位结果带置信分数 (bug_18 教训): 定位器原本只返回 argmax 框且静默回退默认框，
     * 定位失败时分类器在错误区域上产出低 MedianSim，症状表现为"相似度过低"而非"定位失败"。
     * score 为降采样坐标系下的棋盘模式响应分，实测真棋盘 649~1505；暂只遥测不设硬门禁，待真机失败帧数据标定阈值
     */
    data class LocateResult(val rect: Rect, val score: Float)

    fun locateBoard(bitmap: Bitmap): LocateResult {
        val width = bitmap.width
        val height = bitmap.height

        // 1. 降采样到统一尺度 (宽度 400px)，保证跨设备尺度不变性与毫秒级计算
        val scale = 400.0f / width
        val sW = 400
        val sH = (height * scale).toInt()

        val scaledBmp = if (width == sW && height == sH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sW, sH, true)
        }

        val pixels = IntArray(sW * sH)
        scaledBmp.getPixels(pixels, 0, sW, 0, 0, sW, sH)
        if (scaledBmp !== bitmap) {
            scaledBmp.recycle()
        }

        // 2. 提取灰度与 Sobel 水平+垂直梯度 (|gx| + |gy|)
        val gray = FloatArray(sW * sH)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val mag = FloatArray(sW * sH)
        for (y in 1 until sH - 1) {
            val yOffset = y * sW
            val yPrev = (y - 1) * sW
            val yNext = (y + 1) * sW
            for (x in 1 until sW - 1) {
                val v00 = gray[yPrev + x - 1]
                val v02 = gray[yPrev + x + 1]
                val v10 = gray[yOffset + x - 1]
                val v12 = gray[yOffset + x + 1]
                val v20 = gray[yNext + x - 1]
                val v22 = gray[yNext + x + 1]
                val v01 = gray[yPrev + x]
                val v21 = gray[yNext + x]

                val gx = (v02 + 2f * v12 + v22) - (v00 + 2f * v10 + v20)
                val gy = (v20 + 2f * v21 + v22) - (v00 + 2f * v01 + v02)
                mag[yOffset + x] = abs(gx) + abs(gy)
            }
        }

        // 3. 构建灰度与梯度的双积分图 (SAT)
        val satGray = DoubleArray((sW + 1) * (sH + 1))
        val satMag = DoubleArray((sW + 1) * (sH + 1))
        val satStride = sW + 1

        for (y in 1..sH) {
            var rowSumG = 0.0
            var rowSumM = 0.0
            val srcOffset = (y - 1) * sW
            val satRowOffset = y * satStride
            val satPrevRowOffset = (y - 1) * satStride

            for (x in 1..sW) {
                rowSumG += gray[srcOffset + x - 1]
                rowSumM += mag[srcOffset + x - 1]
                satGray[satRowOffset + x] = satGray[satPrevRowOffset + x] + rowSumG
                satMag[satRowOffset + x] = satMag[satPrevRowOffset + x] + rowSumM
            }
        }

        fun rectSum(sat: DoubleArray, x1: Int, y1: Int, x2: Int, y2: Int): Double {
            val cX1 = max(0, min(sW, x1))
            val cY1 = max(0, min(sH, y1))
            val cX2 = max(0, min(sW, x2))
            val cY2 = max(0, min(sH, y2))
            if (cX2 <= cX1 || cY2 <= cY1) return 0.0
            return sat[cY2 * satStride + cX2] - sat[cY1 * satStride + cX2] -
                    sat[cY2 * satStride + cX1] + sat[cY1 * satStride + cX1]
        }

        fun rectMean(sat: DoubleArray, x1: Int, y1: Int, x2: Int, y2: Int): Float {
            val w = max(1, x2 - x1)
            val h = max(1, y2 - y1)
            return (rectSum(sat, x1, y1, x2, y2) / (w * h)).toFloat()
        }

        // 4. 8x8 交替棋盘黑白模式
        val pattern = FloatArray(64) { idx ->
            val r = idx / 8
            val c = idx % 8
            if ((r + c) % 2 == 0) 1.0f else -1.0f
        }

        fun evaluateBox(x: Int, y: Int, size: Int): Float {
            val step = size / 8.0f

            // (1) 7 条横向与 7 条纵向分割线边缘能量
            var edgeScore = 0.0f
            for (i in 1..7) {
                val ly = (y + i * step).toInt()
                val lx = (x + i * step).toInt()
                edgeScore += rectMean(satMag, x, ly - 1, x + size, ly + 2)
                edgeScore += rectMean(satMag, lx - 1, y, lx + 2, y + size)
            }

            // (2) 8x8 格子 4 角采样（18% 边角）避开棋子中心
            val gridMeans = FloatArray(64)
            val cornerW = max(1, (step * 0.18f).toInt())
            var gridSum = 0.0f

            for (r in 0..7) {
                val cy1 = (y + r * step).toInt()
                val cy2 = (cy1 + step).toInt()
                for (c in 0..7) {
                    val cx1 = (x + c * step).toInt()
                    val cx2 = (cx1 + step).toInt()

                    val m1 = rectMean(satGray, cx1, cy1, cx1 + cornerW, cy1 + cornerW)
                    val m2 = rectMean(satGray, cx2 - cornerW, cy1, cx2, cy1 + cornerW)
                    val m3 = rectMean(satGray, cx1, cy2 - cornerW, cx1 + cornerW, cy2)
                    val m4 = rectMean(satGray, cx2 - cornerW, cy2 - cornerW, cx2, cy2)

                    val cellVal = (m1 + m2 + m3 + m4) * 0.25f
                    gridMeans[r * 8 + c] = cellVal
                    gridSum += cellVal
                }
            }

            val gridAvg = gridSum / 64.0f
            var corrSum = 0.0f
            for (i in 0 until 64) {
                corrSum += (gridMeans[i] - gridAvg) * pattern[i]
            }
            val corr = abs(corrSum)

            // (3) 多邻国垂直合理性先验 (底部比例一般在 70%~98%)
            val bottomRatio = (y + size).toFloat() / sH.toFloat()
            val posPrior = if (bottomRatio in 0.70f..0.98f) 1.0f else 0.35f

            return (corr * 2.0f + edgeScore * 0.4f) * posPrior
        }

        // 5. 阶段一：粗扫 (step=4)
        val minSize = (0.85f * sW).toInt()
        val maxSize = min(sW, (0.98f * sW).toInt())
        var bestScore = -1e9f
        var bestX = 0
        var bestY = 0
        var bestSize = minSize

        for (size in minSize..maxSize step 4) {
            val centerX = (sW - size) / 2
            val minX = max(0, centerX - 8)
            val maxX = min(sW - size, centerX + 8)
            val minY = (sH * 0.20f).toInt()
            val maxY = sH - size

            for (x in minX..maxX step 4) {
                for (y in minY..maxY step 4) {
                    val score = evaluateBox(x, y, size)
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                        bestSize = size
                    }
                }
            }
        }

        // 6. 阶段二：精修 (在最佳点周围 ±4 像素做 step=1 搜索)
        val optX = bestX
        val optY = bestY
        val optSize = bestSize

        for (size in max(minSize, optSize - 4)..min(maxSize, optSize + 4) step 1) {
            for (x in max(0, optX - 4)..min(sW - size, optX + 4) step 1) {
                for (y in max(0, optY - 4)..min(sH - size, optY + 4) step 1) {
                    val score = evaluateBox(x, y, size)
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                        bestSize = size
                    }
                }
            }
        }

        // 7. 映射回原图坐标系
        val invScale = 1.0f / scale
        var origX = (bestX * invScale).roundToInt()
        var origY = (bestY * invScale).roundToInt()
        var origSize = (bestSize * invScale).roundToInt()

        origX = max(0, min(width - origSize, origX))
        origY = max(0, min(height - origSize, origY))

        return LocateResult(Rect(origX, origY, origX + origSize, origY + origSize), bestScore)
    }
}
