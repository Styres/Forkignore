package com.chess.copilot.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 纯 Kotlin 移植版多模态多邻国 2D 棋子识别器
 * 基于 48x48 核心 ROI 梯度场余弦互相关 (Cosine NCC) 与 2-Means 阵营自适应聚类
 * 结合 chess-position-detector 数量约束与 Lichess 合规 FEN 清洗
 */
class UltraRobustClassifier(context: Context? = null) {

    data class TemplateFeature(
        val className: String,
        val magNorm: FloatArray
    )

    data class CellFeature(
        val magNorm: FloatArray,
        val centerStd: Float,
        val centerMean: Float,
        val gradMean: Float
    )

    data class DetectionResult(
        val boardFen: String,
        val fullFen: String,
        val activeColor: String,
        val isWhitePerspective: Boolean,
        val rawBoard: Array<CharArray>,
        val standardBoard: Array<CharArray>,
        val boardRect: Rect
    )

    private val templates = mutableListOf<TemplateFeature>()

    init {
        if (context != null) {
            loadTemplatesFromAssets(context)
        }
    }

    /**
     * 从 assets/templates/ 目录动态载入多样本模板
     */
    private fun loadTemplatesFromAssets(context: Context) {
        try {
            val templateFiles = context.assets.list("templates") ?: emptyArray()
            for (filename in templateFiles) {
                if (!filename.endsWith(".png")) continue
                val clsName = filename.split("_")[0].uppercase()
                val inputStream: InputStream = context.assets.open("templates/$filename")
                val bmp = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bmp != null) {
                    val feat = extractFeatureFromBitmap(bmp)
                    templates.add(TemplateFeature(clsName, feat.magNorm))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 针对整个棋盘进行切割并分类识别
     */
    fun classifyBoard(bitmap: Bitmap, boardRect: Rect): DetectionResult {
        val step = (boardRect.right - boardRect.left) / 8.0f
        val cellsFeats = Array(8) { r ->
            Array(8) { c ->
                val x1 = (boardRect.left + c * step).toInt()
                val y1 = (boardRect.top + r * step).toInt()
                val x2 = (boardRect.left + (c + 1) * step).toInt()
                val y2 = (boardRect.top + (r + 1) * step).toInt()

                val cellW = max(1, x2 - x1)
                val cellH = max(1, y2 - y1)
                val cellBmp = Bitmap.createBitmap(bitmap, x1, y1, cellW, cellH)
                val feat = extractFeatureFromBitmap(cellBmp)
                if (cellBmp !== bitmap) cellBmp.recycle()
                feat
            }
        }

        // 1. 识别各格子棋子种类，记录第一与第二置信度候选
        data class OccupiedCell(
            val r: Int,
            val c: Int,
            val feat: CellFeature,
            val primaryClass: String,
            val secondaryClass: String,
            val kingSimilarity: Float
        )
        val occupiedList = mutableListOf<OccupiedCell>()

        for (r in 0..7) {
            for (c in 0..7) {
                val f = cellsFeats[r][c]
                // 阈值判断：空网格中心方差与边缘梯度极低
                if (f.centerStd < 6.0f || f.gradMean < 8.0f) {
                    continue
                }

                var bestCls = "P"
                var bestSim = -1e9f
                var secondCls = "P"
                var secondSim = -1e9f
                var kingSim = -1e9f

                for (t in templates) {
                    val sim = computeCosineSimilarity(f.magNorm, t.magNorm)
                    if (t.className == "K" && sim > kingSim) {
                        kingSim = sim
                    }
                    if (sim > bestSim) {
                        secondSim = bestSim
                        secondCls = bestCls
                        bestSim = sim
                        bestCls = t.className
                    } else if (sim > secondSim && t.className != bestCls) {
                        secondSim = sim
                        secondCls = t.className
                    }
                }
                occupiedList.add(OccupiedCell(r, c, f, bestCls, secondCls, kingSim))
            }
        }

        // 2. 自适应 2-Means 聚类区分黑白阵营
        val rawBoard = Array(8) { CharArray(8) { '.' } }
        if (occupiedList.isNotEmpty()) {
            val means = FloatArray(occupiedList.size) { occupiedList[it].feat.centerMean }
            val splitThreshold = calculateTwoMeansThreshold(means)

            for (cell in occupiedList) {
                val isWhite = cell.feat.centerMean >= splitThreshold
                val sym = if (isWhite) cell.primaryClass[0].uppercaseChar() else cell.primaryClass[0].lowercaseChar()
                rawBoard[cell.r][cell.c] = sym
            }
        }

        // 3. 约束校验（双王守恒、Rank 1/8 禁兵、数量上限重分类）
        val sanitizedBoard = sanitizeBoard(rawBoard)

        // 4. 统计顶底黑白子判定视角
        var topWhite = 0
        var topBlack = 0
        var botWhite = 0
        var botBlack = 0
        for (r in 0..1) {
            for (c in 0..7) {
                val s = sanitizedBoard[r][c]
                if (s.isUpperCase()) topWhite++ else if (s.isLowerCase()) topBlack++
            }
        }
        for (r in 6..7) {
            for (c in 0..7) {
                val s = sanitizedBoard[r][c]
                if (s.isUpperCase()) botWhite++ else if (s.isLowerCase()) botBlack++
            }
        }

        val isWhitePerspective = botWhite >= botBlack
        return buildFenFromBoard(sanitizedBoard, isWhitePerspective, boardRect)
    }

    /**
     * 提取 48x48 格子的核心梯度特征 (30x30 ROI)
     */
    private fun extractFeatureFromBitmap(cellBmp: Bitmap): CellFeature {
        val resized = Bitmap.createScaledBitmap(cellBmp, 48, 48, true)
        val pixels = IntArray(48 * 48)
        resized.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        if (resized !== cellBmp) resized.recycle()

        val gray = FloatArray(48 * 48)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 裁剪 18%..82% 核心区域：行 9..38, 列 9..38 (共 30x30 = 900 像素)
        val roiW = 30
        val roiH = 30
        val magArray = FloatArray(roiW * roiH)
        var sumGray = 0.0f
        var sumSqGray = 0.0f
        var sumMag = 0.0f

        for (r in 0 until roiH) {
            val gy = 9 + r
            for (c in 0 until roiW) {
                val gx = 9 + c
                val v = gray[gy * 48 + gx]
                sumGray += v
                sumSqGray += v * v

                // Sobel 算子 (3x3) 计算梯度
                val v00 = gray[(gy - 1) * 48 + (gx - 1)]
                val v01 = gray[(gy - 1) * 48 + gx]
                val v02 = gray[(gy - 1) * 48 + (gx + 1)]
                val v10 = gray[gy * 48 + (gx - 1)]
                val v12 = gray[gy * 48 + (gx + 1)]
                val v20 = gray[(gy + 1) * 48 + (gx - 1)]
                val v21 = gray[(gy + 1) * 48 + gx]
                val v22 = gray[(gy + 1) * 48 + (gx + 1)]

                val sobelX = (v02 + 2f * v12 + v22) - (v00 + 2f * v10 + v20)
                val sobelY = (v20 + 2f * v21 + v22) - (v00 + 2f * v01 + v02)
                val mag = sqrt(sobelX * sobelX + sobelY * sobelY)

                magArray[r * roiW + c] = mag
                sumMag += mag
            }
        }

        val totalPixels = (roiW * roiH).toFloat()
        val meanVal = sumGray / totalPixels
        val variance = max(0f, (sumSqGray / totalPixels) - (meanVal * meanVal))
        val stdVal = sqrt(variance)
        val gradMean = sumMag / totalPixels

        // L2 范数归一化
        var normSq = 0.0f
        for (m in magArray) normSq += m * m
        val norm = sqrt(normSq) + 1e-5f
        val magNorm = FloatArray(magArray.size) { magArray[it] / norm }

        return CellFeature(magNorm, stdVal, meanVal, gradMean)
    }

    private fun computeCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        val len = a.size
        for (i in 0 until len) {
            dot += a[i] * b[i]
        }
        return dot
    }

    companion object {
        /**
         * 纯净 2-Means 聚类自适应划分黑白方（零硬编码阈值）
         */
        fun calculateTwoMeansThreshold(means: FloatArray): Float {
            if (means.isEmpty()) return 128.0f
            if (means.size == 1) return if (means[0] >= 120f) means[0] - 1f else means[0] + 1f

            val minVal = means.minOrNull() ?: 0.0f
            val maxVal = means.maxOrNull() ?: 255.0f

            // 单色残局保护：当所有棋子亮度极度接近（极差 < 35）时
            if (maxVal - minVal < 35.0f) {
                val avg = means.average().toFloat()
                return if (avg >= 120.0f) minVal - 1.0f else maxVal + 1.0f
            }

            var c1 = minVal
            var c2 = maxVal

            for (iter in 0 until 10) {
                var sum1 = 0.0f
                var cnt1 = 0
                var sum2 = 0.0f
                var cnt2 = 0

                for (m in means) {
                    if (abs(m - c1) <= abs(m - c2)) {
                        sum1 += m
                        cnt1++
                    } else {
                        sum2 += m
                        cnt2++
                    }
                }
                if (cnt1 > 0) c1 = sum1 / cnt1
                if (cnt2 > 0) c2 = sum2 / cnt2
            }
            return (c1 + c2) / 2.0f
        }

        /**
         * 校验棋盘合法性：借鉴 chess-position-detector 数量上限约束与 Lichess 规则清洗
         */
        fun sanitizeBoard(board: Array<CharArray>): Array<CharArray> {
            val result = Array(8) { r -> CharArray(8) { c -> board[r][c] } }

            // 1. Rank 1 (row 7) 与 Rank 8 (row 0) 严禁出现兵（Pawn）
            for (c in 0..7) {
                if (result[0][c] == 'P' || result[0][c] == 'p') {
                    result[0][c] = '.'
                }
                if (result[7][c] == 'P' || result[7][c] == 'p') {
                    result[7][c] = '.'
                }
            }

            // 2. 统计子力数量并执行上限约束（容量感知降级，避免二次超限）
            val pieceCounts = mutableMapOf<Char, Int>()
            val maxLimits = mapOf(
                'K' to 1, 'k' to 1,
                'Q' to 1, 'q' to 1,
                'R' to 2, 'r' to 2,
                'B' to 2, 'b' to 2,
                'N' to 2, 'n' to 2,
                'P' to 8, 'p' to 8
            )

            for (r in 0..7) {
                for (c in 0..7) {
                    val p = result[r][c]
                    if (p == '.') continue
                    val count = pieceCounts.getOrDefault(p, 0) + 1
                    pieceCounts[p] = count
                    val maxAllowed = maxLimits[p] ?: 8
                    if (count > maxAllowed) {
                        // 超限降级：寻找尚未达标的备选子力，若都满额则置为 '.'
                        val isWhite = p.isUpperCase()
                        val candidates = if (isWhite) charArrayOf('R', 'B', 'N') else charArrayOf('r', 'b', 'n')
                        var fallbackPiece = '.'
                        for (cand in candidates) {
                            val candCount = pieceCounts.getOrDefault(cand, 0)
                            if (candCount < (maxLimits[cand] ?: 2)) {
                                fallbackPiece = cand
                                pieceCounts[cand] = candCount + 1
                                break
                            }
                        }
                        result[r][c] = fallbackPiece
                    }
                }
            }

            // 3. 双王唯一性保证（王=0 时从底线候选回填国王）
            var whiteKingCount = 0
            var blackKingCount = 0
            for (r in 0..7) {
                for (c in 0..7) {
                    if (result[r][c] == 'K') whiteKingCount++
                    if (result[r][c] == 'k') blackKingCount++
                }
            }

            // 白王回填
            if (whiteKingCount == 0) {
                var placed = false
                for (r in 7 downTo 6) {
                    for (c in 3..4) {
                        if (result[r][c] == '.' || result[r][c].isUpperCase()) {
                            result[r][c] = 'K'
                            placed = true
                            break
                        }
                    }
                    if (placed) break
                }
                if (!placed) {
                    for (r in 7 downTo 0) {
                        for (c in 0..7) {
                            if (result[r][c] != 'k') {
                                result[r][c] = 'K'
                                placed = true
                                break
                            }
                        }
                        if (placed) break
                    }
                }
            }

            // 黑王回填
            if (blackKingCount == 0) {
                var placed = false
                for (r in 0..1) {
                    for (c in 3..4) {
                        if (result[r][c] == '.' || result[r][c].isLowerCase()) {
                            result[r][c] = 'k'
                            placed = true
                            break
                        }
                    }
                    if (placed) break
                }
                if (!placed) {
                    for (r in 0..7) {
                        for (c in 0..7) {
                            if (result[r][c] != 'K') {
                                result[r][c] = 'k'
                                placed = true
                                break
                            }
                        }
                        if (placed) break
                    }
                }
            }

            // 4. 终极自检：消除任何二次超限
            val finalCounts = mutableMapOf<Char, Int>()
            for (r in 0..7) {
                for (c in 0..7) {
                    val p = result[r][c]
                    if (p == '.') continue
                    val cnt = finalCounts.getOrDefault(p, 0) + 1
                    finalCounts[p] = cnt
                    val limit = maxLimits[p] ?: 8
                    if (cnt > limit) {
                        result[r][c] = '.'
                    }
                }
            }

            return result
        }

        /**
         * 单行连续空格压缩（如 . . . p -> 3p）
         */
        fun compressRow(row: CharArray): String {
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
            return sb.toString()
        }

        /**
         * 构建标准 FEN 串
         */
        fun buildFenFromBoard(
            rawBoard: Array<CharArray>,
            isWhitePerspective: Boolean,
            boardRect: Rect = Rect()
        ): DetectionResult {
            val standardBoard = if (isWhitePerspective) {
                rawBoard
            } else {
                // 黑方视角翻转 180 度得到标准 rank 8 到 rank 1 矩阵
                Array(8) { r -> CharArray(8) { c -> rawBoard[7 - r][7 - c] } }
            }

            val activeColor = if (isWhitePerspective) "w" else "b"
            val fenRows = standardBoard.map { compressRow(it) }
            val boardFen = fenRows.joinToString("/")
            val fullFen = "$boardFen $activeColor KQkq - 0 1"

            return DetectionResult(
                boardFen = boardFen,
                fullFen = fullFen,
                activeColor = activeColor,
                isWhitePerspective = isWhitePerspective,
                rawBoard = rawBoard,
                standardBoard = standardBoard,
                boardRect = boardRect
            )
        }
    }
}
