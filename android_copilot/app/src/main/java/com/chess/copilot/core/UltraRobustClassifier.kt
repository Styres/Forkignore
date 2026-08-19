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
 * 纯 Kotlin 多模态多邻国 2D 棋子识别器 (V7 语义匹配质量门禁版)
 * 核心机制：
 * 1. 棋子语义质量绝对门禁 (MedianSim >= 0.52f && occupied >= 4)，100% 拦截非棋盘画面 (主页、大厅)
 * 2. 占用门控后执行自适应垂直前景质心探测与滑动窗口原点钳制 (x0=clamp, y0=clamp)，彻底杜绝 Row 0 顶部切头与维度坍缩
 * 3. 分数级双区域余弦融合：0.65 * cos(f_body, t_body) + 0.35 * cos(f_head, t_head)，高精度区分车/后/主教/兵/马/王
 * 4. 自适应 2-Means 阵营自适应聚类与单色极差保护 (极差 < 35)
 * 5. 严格遵守 Lichess 规则规范与子力数量上限约束
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

    sealed class ClassificationResponse {
        data class Success(
            val result: DetectionResult,
            val medianSim: Float,
            val occupiedCount: Int,
            val detectedPerspective: Boolean,
            // 逐格取证遥测 (bug_11~14 定案用): 低置信占位格与门控截断候选，供后续标定单格拒绝阈值
            val lowConfidenceCells: List<String> = emptyList(),
            val gateRejectedCells: List<String> = emptyList()
        ) : ClassificationResponse()

        data class Rejected(
            val reason: String,
            val medianSim: Float,
            val occupiedCount: Int,
            // 拦截时的逐格取证 (bug_18 与低 Sim 误拦定案用): 屏幕坐标 r{r}c{c}=类别(sim)
            val lowConfidenceCells: List<String> = emptyList(),
            val gateRejectedCells: List<String> = emptyList()
        ) : ClassificationResponse()
    }

    data class DetectionResult(
        val boardFen: String,
        val fullFen: String,
        val activeColor: String,
        val isWhitePerspective: Boolean,
        val rawBoard: Array<CharArray>,
        val standardBoard: Array<CharArray>,
        val boardRect: Rect,
        val medianSim: Float = 1.0f,
        val occupiedCount: Int = 0
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

    /**
     * 详尽棋盘分类决策管道：返回带有底层置信度指标 (medianSim, occupiedCount) 与拦截原因的响应
     * 支持传入会话锁定的 overridePerspective 防止残局视角误判
     */
    fun classifyBoardDetailed(
        bitmap: Bitmap,
        boardRect: Rect,
        overridePerspective: Boolean? = null
    ): ClassificationResponse {
        // 防御性钳位 (与定位器 isCropped 提示契约双保险): 裁剪帧/救援候选的 rect 可能越出图像边界，
        // 直接 createBitmap 会抛 IllegalArgumentException; 与整幅求交后若框体缩小即判不完整并拦截 (只提示不硬匹配)
        val safeRect = Rect(boardRect)
        val fullyInside = safeRect.intersect(0, 0, bitmap.width, bitmap.height) &&
            safeRect.width() == boardRect.width() && safeRect.height() == boardRect.height()
        if (!fullyInside || safeRect.width() < 8) {
            return ClassificationResponse.Rejected(reason = "CROPPED_RECT", medianSim = 0f, occupiedCount = 0)
        }
        val step = (safeRect.right - safeRect.left) / 8.0f
        val cellsFeats = Array(8) { r ->
            Array(8) { c ->
                val x1 = (safeRect.left + c * step).toInt()
                val y1 = (safeRect.top + r * step).toInt()
                val x2 = (safeRect.left + (c + 1) * step).toInt()
                val y2 = (safeRect.top + (r + 1) * step).toInt()

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
            val bestSimilarity: Float,
            val secondaryClass: String,
            val kingSimilarity: Float
        )
        val occupiedList = mutableListOf<OccupiedCell>()
        // 门控截断候选: 中心方差不足但边缘梯度已越线的格子，是"真实棋子被误判为空"(bug_13/14 阻隔漏检)的头号嫌疑
        val gateRejected = mutableListOf<String>()

        for (r in 0..7) {
            for (c in 0..7) {
                val f = cellsFeats[r][c]
                // 占用门控：空网格中心方差与边缘梯度极低 (gradMean >= 22.0 彻底过滤 2.5D 透视阴影与顶部微重叠)
                if (f.centerStd < 6.0f || f.gradMean < 22.0f) {
                    if (f.gradMean >= 22.0f) {
                        gateRejected.add("r${r}c${c}|std=${String.format("%.1f", f.centerStd)}|grad=${String.format("%.1f", f.gradMean)}")
                    }
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
                occupiedList.add(OccupiedCell(r, c, f, bestCls, bestSim, secondCls, kingSim))
            }
        }

        // 2. 棋盘语义质量门禁 (Semantic Quality Gating)
        // 拦截遥测: 低置信占位格按 sim 升序，供定案"真棋盘被误拦"时看单格分布 (此时视角未定，用屏幕坐标)
        val rejectedLowConf = occupiedList
            .filter { it.bestSimilarity < 0.60f }
            .sortedBy { it.bestSimilarity }
            .map { "r${it.r}c${it.c}=${it.primaryClass}(${String.format("%.2f", it.bestSimilarity)})" }

        if (occupiedList.size < 4) {
            return ClassificationResponse.Rejected(
                reason = "占位棋子数不足 (${occupiedList.size} < 4)",
                medianSim = 0.0f,
                occupiedCount = occupiedList.size,
                lowConfidenceCells = rejectedLowConf,
                gateRejectedCells = gateRejected
            )
        }

        val sortedSims = occupiedList.map { it.bestSimilarity }.sorted()
        val medianSim = if (sortedSims.size % 2 == 1) {
            sortedSims[sortedSims.size / 2]
        } else {
            (sortedSims[sortedSims.size / 2 - 1] + sortedSims[sortedSims.size / 2]) / 2.0f
        }

        // 非棋盘画面 (如路线图、大厅) 的中位数相似度极低 (实测 <= 0.378)，真实棋盘 >= 0.673
        if (medianSim < 0.52f) {
            return ClassificationResponse.Rejected(
                reason = "相似度过低 (MedianSim=${String.format("%.3f", medianSim)} < 0.520)",
                medianSim = medianSim,
                occupiedCount = occupiedList.size,
                lowConfidenceCells = rejectedLowConf,
                gateRejectedCells = gateRejected
            )
        }

        // 3. 自适应 2-Means 聚类区分黑白阵营
        val rawBoard = Array(8) { CharArray(8) { '.' } }
        val means = FloatArray(occupiedList.size) { occupiedList[it].feat.centerMean }
        val splitThreshold = calculateTwoMeansThreshold(means)

        for (cell in occupiedList) {
            val isWhite = cell.feat.centerMean >= splitThreshold
            val sym = if (isWhite) cell.primaryClass[0].uppercaseChar() else cell.primaryClass[0].lowercaseChar()
            rawBoard[cell.r][cell.c] = sym
        }

        // 4. 约束校验（Rank 1/8 禁兵、数量上限容量感知降级）
        val sanitizedBoard = sanitizeBoard(rawBoard)

        // 5. 统计顶底黑白子判定视角
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

        val detectedPerspective = botWhite >= botBlack
        val effectivePerspective = overridePerspective ?: detectedPerspective

        // 5.5 逐格置信度遥测 (bug_11 教训: b4 单格 Sim 仅 0.50 余量 0.06 仍被全局中位数门禁放行，污染 FEN)
        val fileChars = "abcdefgh"
        val lowConfidenceCells = occupiedList
            .filter { it.bestSimilarity < 0.60f }
            .sortedBy { it.bestSimilarity }
            .map { cell ->
                val name = if (effectivePerspective) {
                    "${fileChars[cell.c]}${8 - cell.r}"
                } else {
                    "${fileChars[7 - cell.c]}${cell.r + 1}"
                }
                "$name=${cell.primaryClass}(${String.format("%.2f", cell.bestSimilarity)})"
            }

        val result = buildFenFromBoard(
            rawBoard = sanitizedBoard,
            isWhitePerspective = effectivePerspective,
            boardRect = safeRect,
            medianSim = medianSim,
            occupiedCount = occupiedList.size
        )

        return ClassificationResponse.Success(
            result = result,
            medianSim = medianSim,
            occupiedCount = occupiedList.size,
            detectedPerspective = detectedPerspective,
            lowConfidenceCells = lowConfidenceCells,
            gateRejectedCells = gateRejected
        )
    }

    fun classifyBoard(bitmap: Bitmap, boardRect: Rect): DetectionResult? {
        return when (val resp = classifyBoardDetailed(bitmap, boardRect)) {
            is ClassificationResponse.Success -> resp.result
            is ClassificationResponse.Rejected -> null
        }
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

        // 限制在 [2..45] 内部区域探测，防止外圈 2px 边界线/邻格边缘侵入拉偏质心
        var sumFgY = 0.0
        var sumFgX = 0.0
        var fgCount = 0
        for (y in 2..45) {
            val yOff = y * 48
            for (x in 2..45) {
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

            // 3. 双王唯一性保证（旧版回填已废除，交由下游严苛校验）
            var whiteKingCount = 0
            var blackKingCount = 0
            for (r in 0..7) {
                for (c in 0..7) {
                    if (result[r][c] == 'K') whiteKingCount++
                    if (result[r][c] == 'k') blackKingCount++
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
            boardRect: Rect = Rect(),
            medianSim: Float = 1.0f,
            occupiedCount: Int = 0
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
                boardRect = boardRect,
                medianSim = medianSim,
                occupiedCount = occupiedCount
            )
        }

        /**
         * 纯函数：会话视角锁定状态机决策
         * @param currentLock 当前会话锁定的视角 (null 表示尚未锁定)
         * @param detectedPerspective 当前帧算法检测出的视角
         * @param occupiedCount 当前棋盘占位棋子数
         * @param medianSim 当前帧模板匹配相似度中位数
         * @return 更新后的锁定视角 (null 表示置信度不足暂不锁定)
         */
        fun resolvePerspectiveLock(
            currentLock: Boolean?,
            detectedPerspective: Boolean,
            occupiedCount: Int,
            medianSim: Float
        ): Boolean? {
            // 1. 开新局场景：占位棋子 >= 26 颗且置信度高，强制重新校准并锁定为新局视角
            if (occupiedCount >= 26 && medianSim >= 0.60f) {
                return detectedPerspective
            }

            // 2. 初始锁定：当前无锁时，需达到高置信度门槛 (occupied >= 16 或 medianSim >= 0.70f) 才可永久上锁
            if (currentLock == null) {
                return if (occupiedCount >= 16 || medianSim >= 0.70f) {
                    detectedPerspective
                } else {
                    null // 低于门槛暂不上锁，本次单帧按 detectedPerspective 消费
                }
            }

            // 3. 中残局场景：沿用已有锁定，防止底线出子/受攻导致视角误判翻转
            return currentLock
        }
    }
}
