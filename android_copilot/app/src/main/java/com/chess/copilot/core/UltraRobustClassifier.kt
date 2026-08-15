package com.chess.copilot.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 纯 Kotlin 多模态多邻国 2D 棋子识别器 (V6 双区域解剖轮廓增强版)
 * 核心机制：
 * 1. 占用门控后执行自适应垂直前景质心探测与滑动窗口原点钳制 (x0=clamp, y0=clamp)，彻底杜绝 Row 0 顶部切头与维度坍缩
 * 2. 分数级双区域余弦融合：0.65 * cos(f_body, t_body) + 0.35 * cos(f_head, t_head)，高精度区分车/后/主教/兵/马/王
 * 3. 自适应 2-Means 阵营自适应聚类
 * 4. 严格遵守 Lichess 规则规范与子力数量上限约束
 */
class UltraRobustClassifier(context: Context? = null) {

    data class TemplateFeature(
        val className: String,
        val bodyNorm: FloatArray,
        val headNorm: FloatArray
    )

    data class CellFeature(
        val bodyNorm: FloatArray,
        val headNorm: FloatArray,
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
                    templates.add(TemplateFeature(clsName, feat.bodyNorm, feat.headNorm))
                    bmp.recycle()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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

        // 1. 占用门控与模板余弦匹配
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
                // 占用门控：空网格中心方差与边缘梯度极低
                if (f.centerStd < 6.0f || f.gradMean < 8.0f) {
                    continue
                }

                var bestCls = "P"
                var bestSim = -1e9f
                var secondCls = "P"
                var secondSim = -1e9f
                var kingSim = -1e9f

                for (t in templates) {
                    val bodyCos = computeCosineSimilarity(f.bodyNorm, t.bodyNorm)
                    val headCos = computeCosineSimilarity(f.headNorm, t.headNorm)
                    val score = 0.65f * bodyCos + 0.35f * headCos

                    if (t.className == "K" && score > kingSim) {
                        kingSim = score
                    }
                    if (score > bestSim) {
                        secondSim = bestSim
                        secondCls = bestCls
                        bestSim = score
                        bestCls = t.className
                    } else if (score > secondSim && t.className != bestCls) {
                        secondSim = score
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

        // 3. 约束校验（双王守恒、Rank 1/8 禁兵、数量上限容量感知降级）
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
     * 提取 48x48 格子的双区域解剖特征 (30x30 身体 + 10x30 头部)
     */
    fun extractFeatureFromBitmap(cellBmp: Bitmap): CellFeature {
        val resized = if (cellBmp.width == 48 && cellBmp.height == 48) {
            cellBmp
        } else {
            Bitmap.createScaledBitmap(cellBmp, 48, 48, true)
        }
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

        // 1. 占用门控统计 (基于 30x30 中心区: 行 9..38, 列 9..38)
        var sumGray = 0.0f
        var sumSqGray = 0.0f
        for (r in 9..38) {
            for (c in 9..38) {
                val v = gray[r * 48 + c]
                sumGray += v
                sumSqGray += v * v
            }
        }
        val centerMean = sumGray / 900.0f
        val centerVariance = max(0f, (sumSqGray / 900.0f) - (centerMean * centerMean))
        val centerStd = sqrt(centerVariance)

        // 2. 全图 Sobel 梯度
        val mag = FloatArray(48 * 48)
        var sumCenterMag = 0.0f
        for (gy in 1..46) {
            val yOffset = gy * 48
            val yPrev = (gy - 1) * 48
            val yNext = (gy + 1) * 48
            for (gx in 1..46) {
                val v00 = gray[yPrev + gx - 1]
                val v02 = gray[yPrev + gx + 1]
                val v10 = gray[yOffset + gx - 1]
                val v12 = gray[yOffset + gx + 1]
                val v20 = gray[yNext + gx - 1]
                val v22 = gray[yNext + gx + 1]
                val v01 = gray[yPrev + gx]
                val v21 = gray[yNext + gx]

                val sobelX = (v02 + 2f * v12 + v22) - (v00 + 2f * v10 + v20)
                val sobelY = (v20 + 2f * v21 + v22) - (v00 + 2f * v01 + v02)
                val m = sqrt(sobelX * sobelX + sobelY * sobelY)
                mag[yOffset + gx] = m
                if (gy in 9..38 && gx in 9..38) {
                    sumCenterMag += m
                }
            }
        }
        val gradMean = sumCenterMag / 900.0f

        // 3. 4 角中值背景差分探测前景质心 (3x3 四角采样，共 36 点统一取中值)
        val cornerVals = FloatArray(36)
        var cIdx = 0
        for (r in 0..2) {
            for (c in 0..2) {
                cornerVals[cIdx++] = gray[r * 48 + c]
                cornerVals[cIdx++] = gray[r * 48 + (45 + c)]
                cornerVals[cIdx++] = gray[(45 + r) * 48 + c]
                cornerVals[cIdx++] = gray[(45 + r) * 48 + (45 + c)]
            }
        }
        cornerVals.sort()
        val bgVal = cornerVals[18]

        var sumFgY = 0.0
        var sumFgX = 0.0
        var fgCount = 0
        for (y in 0..47) {
            val yOff = y * 48
            for (x in 0..47) {
                if (abs(gray[yOff + x] - bgVal) > 15.0f) {
                    sumFgY += y
                    sumFgX += x
                    fgCount++
                }
            }
        }

        val cy = if (fgCount > 0) (sumFgY / fgCount).toFloat() else 24.0f
        val cx = if (fgCount > 0) (sumFgX / fgCount).toFloat() else 24.0f

        // 4. 滑动窗口原点钳制 (保证 ROI 恒为 36x36，绝无维度坍缩)
        val x0 = max(0, min(12, (cx - 18f).roundToInt()))
        val y0 = max(0, min(12, (cy - 18f).roundToInt()))

        // 5. 身体特征: 30x30 (从 36x36 居中截取，共 900 维)
        val bodyMag = FloatArray(900)
        var bodySumSq = 0.0f
        for (r in 0..29) {
            val srcY = y0 + 3 + r
            for (c in 0..29) {
                val srcX = x0 + 3 + c
                val v = mag[srcY * 48 + srcX]
                bodyMag[r * 30 + c] = v
                bodySumSq += v * v
            }
        }
        val bodyNormVal = sqrt(bodySumSq) + 1e-5f
        val bodyNorm = FloatArray(900) { bodyMag[it] / bodyNormVal }

        // 6. 头部解剖特征: 10x30 (从 30x30 身体取顶端 10 行，共 300 维)
        val headMag = FloatArray(300)
        var headSumSq = 0.0f
        for (r in 0..9) {
            for (c in 0..29) {
                val v = bodyMag[r * 30 + c]
                headMag[r * 30 + c] = v
                headSumSq += v * v
            }
        }
        val headNormVal = sqrt(headSumSq) + 1e-5f
        val headNorm = FloatArray(300) { headMag[it] / headNormVal }

        return CellFeature(bodyNorm, headNorm, centerStd, centerMean, gradMean)
    }

    private fun computeCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        val len = min(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
        }
        return dot
    }

    companion object {
        fun calculateTwoMeansThreshold(means: FloatArray): Float {
            if (means.isEmpty()) return 128.0f
            if (means.size == 1) return if (means[0] >= 120f) means[0] - 1f else means[0] + 1f

            val minVal = means.minOrNull() ?: 0.0f
            val maxVal = means.maxOrNull() ?: 255.0f

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

            // 4. 终极二次自检
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

        fun buildFenFromBoard(
            rawBoard: Array<CharArray>,
            isWhitePerspective: Boolean,
            boardRect: Rect = Rect()
        ): DetectionResult {
            val standardBoard = if (isWhitePerspective) {
                rawBoard
            } else {
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
