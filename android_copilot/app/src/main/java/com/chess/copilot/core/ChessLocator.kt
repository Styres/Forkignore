package com.chess.copilot.core

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 自研高精度 2D 棋盘自适应定位器 (Fast Integral-SAT + GridLine Direct Calibration Locator)
 *
 * 架构设计 (重构方案: 格线直测定位重构):
 * 1. 粗搜索阶段 (Coarse Locator): 放宽 maxSize 上界至 sW (400px)，x 自由搜索 (不加居中偏置)，提取 top-N 候选近似框
 * 2. 精标定阶段 (Fine Grid Calibrate):
 *    契约: 粗扫在 400 空间提速, 精标定在原图全分辨率空间运行 (与 Python 对偶
 *    原型 grid_calibrate.py 同空间), 保证 RESIDUAL_GATE/SNAP_PX 等 px 级门禁语义一致。
 *    - 横向: 6行带跨带中值剖面 + 梳状波长高斯先验 + 2遍等差拟合 (x_i = x0 + i*step) + 满宽 <=2px 吸附
 *    - 纵向: 上下框行均值剖面强边 + 双侧低方差双重门禁主锚 (方约束配对 + 贴近粗框平局裁决 + 内部横线交叉检验)
 *    - 纵向退化: 内部 7 条横分割线行剖面等差拟合 + 3点平滑75分位峰检 + 相位多档试探 + 外边界强边一致性校验与边缘吸附
 *    - 横纵交替: 纵向收敛后以精标定窗口重跑横向精修，彻底剥离带外立绘/按钮杂波
 * 3. 候选选优与置信度契约: RefineResult(rect, confidence, residual, isCropped)，支持 top-N 独立精标定与候选救援
 */
object ChessLocator {

    private const val RELATIVE_RESIDUAL_GATE_RATIO = 0.05f // 5% 单格宽度相对拟合残差门禁 (全分辨率尺度自适应)
    private const val RELATIVE_SQUARE_GATE_RATIO = 0.015f   // 1.5% 棋盘尺寸方约束门禁 (最低 4.0px)
    private const val RELATIVE_SNAP_RATIO = 0.005f          // 0.5% 屏幕宽度满宽吸附门禁 (最低 2.0px)
    private const val MIN_LINES = 5
    private const val OUTLIER_FRAC = 0.25f
    private const val WAVE_GATE = 0.015f
    private const val WAVE_GATE_V = 0.025f
    private const val BAR_STD_GATE = 16.0f
    private const val BAR_GRAD_MIN = 3.0f

    /**
     * 定位结果带置信等级、拟合残差与裁剪越界识别:
     * @param rect 棋盘在原图坐标系下的矩形区域 (可能含越界负坐标)
     * @param score 粗定位响应分 (用于同置信度下的细分排序)
     * @param confidence 精标定置信等级: "high" (双轴均过门禁且未越界), "medium" (单轴精标定/退化通过/裁剪识别), "low" (退化失败保持粗框)
     * @param residual 拟合残差 (像素)
     * @param isCropped 是否为负边距裁剪帧 (rect 越出图像边界)
     */
    data class LocateResult(
        val rect: Rect,
        val score: Float,
        val confidence: String = "high",
        val residual: Float = 0f,
        val isCropped: Boolean = false
    )

    fun locateBoard(bitmap: Bitmap): LocateResult {
        return locateTopCandidates(bitmap, 1).first()
    }

    /**
     * 返回按综合置信度与响应分降序的 top-N 候选框 (支持候选救援链路)
     */
    fun locateTopCandidates(bitmap: Bitmap, maxCount: Int): List<LocateResult> {
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

            // 【重构核心：引入网格能量平衡验证，杜绝单向条纹(墓地UI等)误报】
            var hEdgeScore = 0.0f
            var vEdgeScore = 0.0f
            for (i in 1..7) {
                val ly = (y + i * step).toInt()
                val lx = (x + i * step).toInt()
                hEdgeScore += rectMean(satMag, x, ly - 1, x + size, ly + 2)
                vEdgeScore += rectMean(satMag, lx - 1, y, lx + 2, y + size)
            }
            
            // 墓地 UI 有强烈的水平边缘，但缺乏等距垂直交叉边缘
            val minEdge = min(hEdgeScore, vEdgeScore)
            val maxEdge = max(hEdgeScore, vEdgeScore)
            val edgeBalance = minEdge / max(1e-5f, maxEdge) // 真实棋盘此值应接近 1.0
            
            // 能量乘上平衡系数，单向强边的假框得分将崩塌至十分之一
            val balancedEdgeScore = (hEdgeScore + vEdgeScore) * edgeBalance

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

            // (3) 多邻国垂直合理性先验 (底部比例在 60%~99% 之间)
            val bottomRatio = (y + size).toFloat() / sH.toFloat()
            // 【修改】稍稍放宽先验限制，让十字网格算法自己去战斗
            val posPrior = if (bottomRatio in 0.55f..0.99f) 1.0f else 0.35f

            // 【修改】增大 8x8 交替模式特征权重，结合平衡边缘得分
            return (corr * 2.5f + balancedEdgeScore * 0.5f) * posPrior
        }

        // 5. 阶段一：粗扫 (step=4)，尺寸上界放宽至 sW (400px)，横向自由搜索 (全区间 [0, sW-size]，不预设居中)
        val minSize = (0.60f * sW).toInt()
        val maxSize = sW
        val topEntries = ArrayList<Pair<Float, IntArray>>() // (score, [x,y,size])

        fun recordCandidate(score: Float, x: Int, y: Int, size: Int) {
            val minSep = size * 0.12f // 峰间距去重
            val idx = topEntries.indexOfFirst { (_, e) ->
                abs(e[0] - x) < minSep && abs(e[1] - y) < minSep && abs(e[2] - size) < minSep
            }
            if (idx >= 0) {
                if (score > topEntries[idx].first) topEntries[idx] = Pair(score, intArrayOf(x, y, size))
                return
            }
            topEntries.add(Pair(score, intArrayOf(x, y, size)))
            topEntries.sortByDescending { it.first }
            while (topEntries.size > maxCount + 4) topEntries.removeAt(topEntries.size - 1)
        }

        // size 步长 8 对齐 Python 基准 (间隙由精修 ±5 窗覆盖);
        // x/y 保留 step=4 降采样是 Kotlin 逐框 evaluateBox 的性能适配
        // (Python 基准靠 numpy 向量化做全枚举), 真机耗时差异待阶段四遥测确认
        for (size in minSize..maxSize step 8) {
            val minY = (sH * 0.15f).toInt()
            val maxY = sH - size

            // 彻底放开 x 搜索范围，支持带边距与任意偏置布局
            for (x in 0..(sW - size) step 4) {
                // y 上界不含 sH-size (对齐 Python 基准 n_y = s_h-size-y_min 的半开区间)
                for (y in minY until maxY step 4) {
                    val score = evaluateBox(x, y, size)
                    recordCandidate(score, x, y, size)
                }
            }
        }

        // 6. 粗候选精修 (在各候选附近 ±5 像素做 step=1 搜索, size 窗 ±5 对齐 Python 基准)
        val initialCandidates = ArrayList<Pair<Float, IntArray>>()
        for (cand in topEntries.take(maxCount + 2)) {
            val (cScore, box) = cand
            var bestScore = cScore
            var bestX = box[0]
            var bestY = box[1]
            var bestSize = box[2]
            for (size in max(minSize, box[2] - 5)..min(maxSize, box[2] + 5) step 1) {
                for (x in max(0, box[0] - 4)..min(sW - size, box[0] + 4) step 1) {
                    for (y in max(0, box[1] - 4)..min(sH - size, box[1] + 4) step 1) {
                        val sc = evaluateBox(x, y, size)
                        if (sc > bestScore) {
                            bestScore = sc
                            bestX = x
                            bestY = y
                            bestSize = size
                        }
                    }
                }
            }
            initialCandidates.add(Pair(bestScore, intArrayOf(bestX, bestY, bestSize)))
        }

        // 7. 精标定: 对 top-N 候选逐个独立进行格线剖面等差精修。
        // 关键契约: refine 必须在原图全分辨率空间运行 (Python 对偶原型即全分辨率),
        // 粗扫留在 400 空间只为提速。曾在 400 空间跑 refine 导致所有 px 级门禁
        // (RESIDUAL_GATE/SNAP_PX/离群容差) 相对原图放宽 ~3.15 倍 + 峰位量化 3px,
        // 气泡遮挡帧上幻影框低残差通过门禁反超真框 (Screenshot_20260818_225702 事故,
        // 真机错框 L=-56,T=284,size=1266, Python 400 空间模拟同型幻影复现钉死根因)。
        val fullGray = FloatArray(width * height)
        val fullPixels = IntArray(width * height)
        bitmap.getPixels(fullPixels, 0, width, 0, 0, width, height)
        for (i in fullPixels.indices) {
            val p = fullPixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            fullGray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val invScale = 1.0f / scale
        val refinedResults = ArrayList<LocateResult>()

        for ((score, coarseBox) in initialCandidates) {
            // 粗候选映射回原图坐标后做全分辨率精修 (结果已在原图空间, 无需二次换算)
            val ox = (coarseBox[0] * invScale).roundToInt()
            val oy = (coarseBox[1] * invScale).roundToInt()
            val oSize = (coarseBox[2] * invScale).roundToInt()
            val calibrated = refineByGridLines(fullGray, width, height, ox, oy, oSize)

            // 满宽吸附: 与屏宽偏差 <= 0.5% (最低 2px) 时对齐为 0 和 width
            var origX = calibrated.x0.roundToInt()
            var origSize = calibrated.size.roundToInt()
            val origY = calibrated.y0.roundToInt()
            val snapPx = max(2.0f, width * RELATIVE_SNAP_RATIO)
            if (abs(calibrated.x0) <= snapPx && abs(calibrated.size - width) <= snapPx) {
                origX = 0
                origSize = width
            }

            // 裁剪识别契约: 不强制 clamp 破坏越界真值，而是识别并做标志与置信度降级
            val isCropped = origX < 0 || origY < 0 || (origX + origSize) > width || (origY + origSize) > height
            val finalConf = if (isCropped && calibrated.confidence == "high") "medium" else calibrated.confidence

            refinedResults.add(
                LocateResult(
                    rect = Rect(origX, origY, origX + origSize, origY + origSize),
                    score = score,
                    confidence = finalConf,
                    residual = calibrated.residual,
                    isCropped = isCropped
                )
            )
        }

        // 8. 综合排序: 置信度等级 > 残差低 > 满宽优先 > 响应分高
        fun confRank(c: String): Int = when (c) {
            "high" -> 2
            "medium" -> 1
            else -> 0
        }

        refinedResults.sortWith { a, b ->
            val rankDiff = confRank(b.confidence) - confRank(a.confidence)
            if (rankDiff != 0) return@sortWith rankDiff
            val resDiff = (a.residual * 2f).roundToInt() - (b.residual * 2f).roundToInt()
            if (resDiff != 0) return@sortWith resDiff
            val fullA = if (abs(a.rect.width() - width) <= 2) 1 else 0
            val fullB = if (abs(b.rect.width() - width) <= 2) 1 else 0
            val fullDiff = fullB - fullA
            if (fullDiff != 0) return@sortWith fullDiff
            b.score.compareTo(a.score)
        }

        return refinedResults.take(maxCount)
    }

    // =========================================================================
    // 阶段二: 格线直测精标定核心算子 (Kotlin 直译对偶实现)
    // =========================================================================

    internal data class CalibratedBox(
        val x0: Float,
        val y0: Float,
        val size: Float,
        val confidence: String,
        val residual: Float
    )

    internal data class FitResult(
        val p0: Float,
        val step: Float,
        val residual: Float,
        val lineCount: Int,
        val isOk: Boolean
    )

    /**
     * 多遍等差拟合: 索引分配 -> 最小二乘拟合 -> 剔除离群峰 -> 重拟合
     */
    private fun fitArithmetic(points: List<Pair<Int, Float>>): Triple<Float, Float, Float> {
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        val n = points.size.toDouble()

        for ((idx, pos) in points) {
            val x = idx.toDouble()
            val y = pos.toDouble()
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
        }

        val denom = n * sumXX - sumX * sumX
        val step = if (abs(denom) > 1e-6) ((n * sumXY - sumX * sumY) / denom).toFloat() else 0f
        val p0 = ((sumY - step.toDouble() * sumX) / n).toFloat()

        var totalResid = 0.0
        for ((idx, pos) in points) {
            totalResid += abs(pos - (p0 + idx * step))
        }
        val avgResid = (totalResid / n).toFloat()

        return Triple(p0, step, avgResid)
    }

    internal fun twoPassFit(lines: List<Float>, x0Est: Float, stepEst: Float): FitResult {
        fun assign(p0: Float, step: Float): List<Pair<Int, Float>> {
            val res = ArrayList<Pair<Int, Float>>()
            for (p in lines) {
                val idx = ((p - p0) / step).roundToInt()
                if (idx in 1..7 && abs(p - (p0 + idx * step)) <= OUTLIER_FRAC * step + 2.0f) {
                    res.add(Pair(idx, p))
                }
            }
            return res
        }

        val maxAllowedResid = stepEst * RELATIVE_RESIDUAL_GATE_RATIO

        val cand1 = assign(x0Est, stepEst)
        if (cand1.size < MIN_LINES) {
            return FitResult(x0Est, stepEst, 99f, cand1.size, false)
        }
        val (p0_1, step_1, _) = fitArithmetic(cand1)

        val cand2 = assign(p0_1, step_1)
        if (cand2.size < MIN_LINES) {
            return FitResult(x0Est, stepEst, 99f, cand2.size, false)
        }
        var (p0_2, step_2, resid_2) = fitArithmetic(cand2)
        var finalCand = cand2

        // 第三遍: 残差超门禁时剔除残差最大点 (孤立伪线)
        if (resid_2 > maxAllowedResid && cand2.size > MIN_LINES) {
            var worstIdx = 0
            var worstResid = -1f
            for (i in cand2.indices) {
                val r = abs(cand2[i].second - (p0_2 + cand2[i].first * step_2))
                if (r > worstResid) {
                    worstResid = r
                    worstIdx = i
                }
            }
            val cand3 = cand2.filterIndexed { index, _ -> index != worstIdx }
            val (p0_3, step_3, resid_3) = fitArithmetic(cand3)
            if (resid_3 < resid_2) {
                p0_2 = p0_3
                step_2 = step_3
                resid_2 = resid_3
                finalCand = cand3
            }
        }

        val isOk = resid_2 <= maxAllowedResid
        return FitResult(p0_2, step_2, resid_2, finalCand.size, isOk)
    }

    /**
     * 横向梳状谐振定标: 6行带跨带中值剖面 + 2遍等差拟合 + 满宽吸附
     */
    private fun refineHorizontal(
        gray: FloatArray, sW: Int, sH: Int,
        x0c: Int, y0c: Int, sizec: Int
    ): Pair<Boolean, FloatArray> { // Pair(ok, [x0, size, residual])
        val y1 = max(0, (y0c - 0.05f * sizec).toInt())
        val y2 = min(sH, (y0c + sizec + 0.05f * sizec).toInt())
        val bandH = max(1, (y2 - y1) / 8)

        // 提取 6 个中央行带的垂直梯度 Sobel gx 均值剖面
        val bandProfiles = Array(6) { FloatArray(sW) }
        for (b in 0 until 6) {
            val byStart = y1 + (b + 1) * bandH
            val byEnd = min(y2, byStart + bandH)
            val count = max(1, byEnd - byStart)
            for (y in byStart until byEnd) {
                val rowOff = y * sW
                for (x in 1 until sW - 1) {
                    val gx = abs(gray[rowOff + x + 1] - gray[rowOff + x - 1])
                    bandProfiles[b][x] += gx
                }
            }
            for (x in 0 until sW) {
                bandProfiles[b][x] /= count.toFloat()
            }
        }

        fun lineEnergy(xi: Int): Float {
            if (xi < 1 || xi >= sW - 1) return 0f
            val vals = FloatArray(6) { b ->
                max(bandProfiles[b][xi - 1], max(bandProfiles[b][xi], bandProfiles[b][xi + 1]))
            }
            vals.sort()
            return (vals[2] + vals[3]) * 0.5f // 6 带中值
        }

        fun combScore(size: Float, x0: Float): Float {
            var sc = 0f
            val step = size / 8.0f
            for (i in 1..7) {
                sc += lineEnergy((x0 + i * step).roundToInt())
            }
            val dev = (size - sizec) / (0.06f * sizec)
            sc *= exp(-0.5f * dev * dev)
            return sc
        }

        // 尺寸与相位扫描 (尺寸步长 0.25px 对齐 Python 基准 np.arange(s_lo, s_hi+0.25, 0.25);
        // 计数器驱动避免浮点累加漂移)
        val sLo = max(8f, sizec * 0.88f)
        val sHi = min(sW.toFloat(), sizec * 1.14f)
        var bestSc = -1f
        var bestSize = sizec.toFloat()
        var bestX0 = x0c.toFloat()

        var kS = 0
        while (true) {
            val s = sLo + kS * 0.25f
            if (s >= sHi + 0.25f) break
            val xcLo = max(-s * 0.05f, x0c - 0.25f * sizec)
            val xcHi = min(sW - s + s * 0.05f, x0c + 0.25f * sizec)
            var x = xcLo
            while (x <= xcHi + 0.9f) {
                val sc = combScore(s, x)
                if (sc > bestSc) {
                    bestSc = sc
                    bestSize = s
                    bestX0 = x
                }
                x += 1.0f
            }
            kS++
        }

        // 谐振最优相位附近峰检与两遍等差拟合
        val medProf = FloatArray(sW)
        val tmpVals = FloatArray(6)
        for (x in 0 until sW) {
            for (b in 0 until 6) tmpVals[b] = bandProfiles[b][x]
            tmpVals.sort()
            medProf[x] = (tmpVals[2] + tmpVals[3]) * 0.5f
        }

        val rawPeaks = ArrayList<Float>()
        val stepR = bestSize / 8.0f
        for (i in 1..7) {
            val xc = (bestX0 + i * stepR).roundToInt()
            val lo = max(1, xc - 2)
            val hi = min(sW - 2, xc + 2)
            var maxV = -1f
            var maxP = xc
            for (p in lo..hi) {
                if (medProf[p] > maxV) {
                    maxV = medProf[p]
                    maxP = p
                }
            }
            rawPeaks.add(maxP.toFloat())
        }

        var fit = twoPassFit(rawPeaks, bestX0, stepR)
        if (fit.isOk && abs(fit.step - stepR) > WAVE_GATE * stepR) {
            fit = FitResult(bestX0, stepR, 99f, fit.lineCount, false)
        }

        var outX0 = if (fit.isOk) fit.p0 else bestX0
        var outSize = if (fit.isOk) fit.step * 8.0f else bestSize
        val residual = if (fit.isOk) fit.residual else 99f

        // 满宽吸附: 偏差 <= 0.5% (最低 2px)
        val snapPx = max(2.0f, sW * RELATIVE_SNAP_RATIO)
        if (abs(outX0) <= snapPx && abs(outSize - sW) <= snapPx) {
            outX0 = 0f
            outSize = sW.toFloat()
        }

        return Pair(fit.isOk, floatArrayOf(outX0, outSize, residual, bestSize))
    }

    /**
     * 纵向上下框主锚检测: 行均值剖面强边 + 双侧低方差双重门禁 + 贴近粗框平局裁决
     */
    private fun findVerticalBarAnchors(
        gray: FloatArray, sW: Int, sH: Int,
        x0c: Int, y0c: Int, sizec: Int,
        expectedSize: Float, x0: Float, size: Float
    ): Triple<Int, Int, Float>? {
        val xa = max(0, (x0 - 0.02f * sW).toInt())
        val xb = min(sW, (x0 + size + 0.02f * sW).toInt())
        val spanX = max(1, xb - xa)

        val prof = FloatArray(sH)
        val rowStd = FloatArray(sH)

        for (y in 0 until sH) {
            var sum = 0.0
            var sumSq = 0.0
            val rowOff = y * sW
            for (x in xa until xb) {
                val v = gray[rowOff + x].toDouble()
                sum += v
                sumSq += v * v
            }
            val mean = sum / spanX
            prof[y] = mean.toFloat()
            val variance = max(0.0, (sumSq / spanX) - mean * mean)
            rowStd[y] = kotlin.math.sqrt(variance).toFloat()
        }

        val g = FloatArray(sH)
        for (y in 1 until sH) {
            g[y] = abs(prof[y] - prof[y - 1])
        }

        val spanY = (0.18f * sW).toInt()
        val tLo = max(0, y0c - spanY)
        val tHi = min(sH - 1, y0c + (0.05f * sW).toInt())
        val bLo = max(0, y0c + sizec - (0.05f * sW).toInt())
        val bHi = min(sH - 2, y0c + sizec + spanY)

        fun findCandidates(lo: Int, hi: Int, isTop: Boolean): List<Int> {
            val cands = ArrayList<Int>()
            for (y in lo until hi) {
                if (g[y] < BAR_GRAD_MIN) continue
                if (!(g[y] >= g[y - 1] && g[y] >= g[min(sH - 1, y + 1)])) continue

                // 边界双侧均查 (对齐 Python 基准): 任一侧行数 >= 3 且平均 std < 16.0 即可
                val bandA = if (isTop) max(0, y - 4) until y else max(0, y - 3) until min(sH, y + 1)
                val bandB = (y + 1) until min(sH, y + 5)

                fun bandStdOk(range: IntRange): Boolean {
                    if (range.last - range.first + 1 < 3) return false
                    var stdSum = 0f
                    var count = 0
                    for (sy in range) {
                        stdSum += rowStd[sy]
                        count++
                    }
                    return count >= 3 && (stdSum / count) < BAR_STD_GATE
                }

                if (bandStdOk(bandA) || bandStdOk(bandB)) {
                    cands.add(y)
                }
            }
            return cands
        }

        val tops = findCandidates(tLo, tHi, true)
        val bots = findCandidates(bLo, bHi, false)

        // 收集所有满足方约束的配对并按 (dev, 贴近粗框距离) 选优 (对齐 Python 平局裁决)
        val squareGate = max(4.0f, expectedSize * RELATIVE_SQUARE_GATE_RATIO)
        val ties = ArrayList<Triple<Int, Int, Float>>()
        for (t in tops) {
            for (b in bots) {
                val dev = abs((b - t).toFloat() - expectedSize)
                if (dev <= squareGate) {
                    ties.add(Triple(t, b, dev))
                }
            }
        }

        if (ties.isEmpty()) return null
        return ties.minWithOrNull { a, b ->
            val devDiff = a.third.compareTo(b.third)
            if (abs(a.third - b.third) > 1e-4) return@minWithOrNull devDiff
            val distA = abs(a.first - y0c) + abs(a.second - (y0c + sizec))
            val distB = abs(b.first - y0c) + abs(b.second - (y0c + sizec))
            distA.compareTo(distB)
        }
    }

    /**
     * 1D 剖面峰检 (3 点平滑 + 75 分位自适应阈值 + 间距抑制，完全对齐 Python _detect_peaks)
     */
    private fun detectPeaks1D(prof: FloatArray, minSep: Float, thrFloor: Float = 1.5f, pct: Float = 0.75f): List<Float> {
        if (prof.size < 3) return emptyList()
        val n = prof.size - 1
        val rawDiff = FloatArray(n) { i -> abs(prof[i + 1] - prof[i]) }

        // 3 点卷积平滑 np.convolve(g, [1,1,1]/3, mode='same')
        val gSmooth = FloatArray(n)
        for (i in 0 until n) {
            val vPrev = if (i > 0) rawDiff[i - 1] else 0f
            val vCurr = rawDiff[i]
            val vNext = if (i < n - 1) rawDiff[i + 1] else 0f
            val count = (if (i > 0) 1 else 0) + 1 + (if (i < n - 1) 1 else 0)
            gSmooth[i] = (vPrev + vCurr + vNext) / count.toFloat()
        }

        // 75 分位数阈值
        val sortedG = gSmooth.clone().apply { sort() }
        val pIdx = (sortedG.size * pct).toInt().coerceIn(0, sortedG.size - 1)
        val thr = max(thrFloor, sortedG[pIdx])

        // 局部极大值筛选
        val cand = ArrayList<Pair<Float, Int>>() // (amp, idx)
        for (i in 1 until n - 1) {
            if (gSmooth[i] >= thr && gSmooth[i] >= gSmooth[i - 1] && gSmooth[i] > gSmooth[i + 1]) {
                cand.add(Pair(gSmooth[i], i))
            }
        }
        cand.sortByDescending { it.first }

        // 最小间距非极大值抑制
        val keep = ArrayList<Int>()
        for ((_, idx) in cand) {
            if (keep.none { abs(it - idx) < minSep }) {
                keep.add(idx)
            }
        }
        keep.sort()
        return keep.map { it.toFloat() }
    }

    /**
     * 内部横分割线行剖面拟合 (对齐 Python _fit_horizontal_lines)
     */
    private fun fitHorizontalLines(
        gray: FloatArray, sW: Int, sH: Int,
        x0c: Int, y0c: Int, sizec: Int
    ): FitResult {
        val sEst = sizec / 8.0f
        val rawPeaks = ArrayList<Float>()

        for (c in 0 until 8) {
            val x1 = max(0, (x0c + (c + 0.22f) * sEst).toInt())
            val x2 = min(sW, (x0c + (c + 0.78f) * sEst).toInt())
            val width = max(1, x2 - x1)
            if (width < 4) continue

            val yLo = max(0, (y0c - 0.5f * sizec).toInt())
            val yHi = min(sH, (y0c + sizec + 0.5f * sizec).toInt())

            val prof = FloatArray(yHi - yLo)
            for (y in yLo until yHi) {
                var sum = 0f
                val rowOff = y * sW
                for (x in x1 until x2) sum += gray[rowOff + x]
                prof[y - yLo] = sum / width
            }

            // 采用 3 点平滑 + 75 分位自适应峰检
            val peaks = detectPeaks1D(prof, minSep = 0.5f * sEst, thrFloor = 1.5f, pct = 0.75f)
            for (p in peaks) {
                rawPeaks.add(p + yLo)
            }
        }

        // 跨列带聚类 (对齐 Python _cluster_lines)
        rawPeaks.sort()
        val clustered = ArrayList<Float>()
        if (rawPeaks.isNotEmpty()) {
            var curGroup = ArrayList<Float>()
            curGroup.add(rawPeaks[0])
            val tol = 0.25f * sEst
            for (i in 1 until rawPeaks.size) {
                val p = rawPeaks[i]
                if (p - curGroup.last() < tol) {
                    curGroup.add(p)
                } else {
                    curGroup.sort()
                    clustered.add(curGroup[curGroup.size / 2])
                    curGroup = ArrayList()
                    curGroup.add(p)
                }
            }
            curGroup.sort()
            clustered.add(curGroup[curGroup.size / 2])
        }

        return twoPassFit(clustered, y0c.toFloat(), sEst)
    }

    /**
     * 外边界强边能量检测 (返回 (topEdge, bottomEdge, yTopEdge, yBottomEdge))
     */
    private fun outerEdgeScore(
        gray: FloatArray, sW: Int, sH: Int,
        x0: Float, size: Float, yFit: Float
    ): FloatArray { // [te, be, yt, yb]
        val xa = max(0, x0.toInt())
        val xb = min(sW, (x0 + size).toInt())
        val spanX = max(1, xb - xa)

        fun localMax(yTarget: Float): Pair<Float, Int> {
            val yc = yTarget.roundToInt()
            // Sobel gy (ksize=3) 仅在内部行 [1, sH-2] 有效，搜索窗限制在内
            val lo = max(1, yc - 5)
            val hi = min(sH - 1, yc + 6)
            var maxG = 0f
            var bestY = yc
            for (y in lo until hi) {
                // Sobel gy 3x3: |(y+1 行 [1,2,1] 卷积) - (y-1 行 [1,2,1] 卷积)|，
                // 对齐 Python _outer_edge_score 的 cv2.Sobel(gray, 0, 1) 量纲
                // (边界列按反射延拓, 等价 BORDER_DEFAULT)
                var sum = 0f
                val prevOff = (y - 1) * sW
                val nextOff = (y + 1) * sW
                for (x in xa until xb) {
                    val xl = if (x > 0) x - 1 else 1
                    val xr = if (x < sW - 1) x + 1 else sW - 2
                    val up = gray[prevOff + xl] + 2f * gray[prevOff + x] + gray[prevOff + xr]
                    val dn = gray[nextOff + xl] + 2f * gray[nextOff + x] + gray[nextOff + xr]
                    sum += abs(dn - up)
                }
                val avg = sum / spanX
                if (avg > maxG) {
                    maxG = avg
                    bestY = y
                }
            }
            return Pair(maxG, bestY)
        }

        val (te, yt) = localMax(yFit)
        val (be, yb) = localMax(yFit + size)
        return floatArrayOf(te, be, yt.toFloat(), yb.toFloat())
    }

    /**
     * 格线精标定主入口 (400 宽空间)
     */
    internal fun refineByGridLines(
        gray: FloatArray, sW: Int, sH: Int,
        x0c: Int, y0c: Int, sizec: Int
    ): CalibratedBox {
        // 1. 横向精修
        val (hOk, hParams) = refineHorizontal(gray, sW, sH, x0c, y0c, sizec)
        var x0 = hParams[0]
        var size = hParams[1]
        val hResid = hParams[2]
        val sizeV = if (hOk) size else hParams[3]

        // 2. 纵向精修: 上下框主锚 + 环形交替度双重验证 (对齐 duolingo-pgn-export 环形采样算法)
        var y0 = y0c.toFloat()
        var vPath = "coarse"
        var vResid = 99f

        val bars = findVerticalBarAnchors(gray, sW, sH, x0c, y0c, sizec, size, x0, size)
        if (bars != null) {
            val barsY0 = bars.first.toFloat()
            val altScore = computeRingAlternationScore(gray, sW, sH, x0, barsY0, size)
            val devOk = bars.third <= max(4.0f, size * RELATIVE_SQUARE_GATE_RATIO)
            
            // 多证据主路径：物理外框几何闭环 (devOk) + 64 格环形采样交替度 >= 0.85 (真棋盘标志)
            if (devOk && altScore >= 0.85f) {
                y0 = barsY0
                vPath = "bars"
                vResid = bars.third
            } else {
                val cross = fitHorizontalLines(gray, sW, sH, x0c, bars.first, size.roundToInt())
                val sEst = size / 8.0f
                if (cross.isOk || (devOk && cross.residual <= sEst * 0.10f && altScore >= 0.75f)) {
                    y0 = barsY0
                    vPath = "bars"
                    vResid = min(bars.third, cross.residual)
                }
            }
        }

        // 3. 纵向退化路径: 多档严格邻域相位试探 (严格限制在 <= 0.5 格邻域，杜绝上下跨格越狱) + 环形交替度门禁
        if (vPath == "coarse") {
            val sEst = sizeV / 8.0f
            var bestResid = 99f
            var bestY0 = y0c.toFloat()

            for (k in getDegeneratePhaseSteps()) {
                val yTry = (y0c + k * sEst).roundToInt()
                val hfit = fitHorizontalLines(gray, sW, sH, x0c, yTry, sizeV.roundToInt())
                if (hfit.isOk && abs(hfit.step - sEst) <= WAVE_GATE_V * sEst) {
                    val edges = outerEdgeScore(gray, sW, sH, x0, sizeV, hfit.p0)
                    val te = edges[0]
                    val be = edges[1]
                    val yt = edges[2]
                    if (te >= BAR_GRAD_MIN && be >= BAR_GRAD_MIN) {
                        val candAlt = computeRingAlternationScore(gray, sW, sH, x0, yt, sizeV)
                        // 必须满足 8x8 黑白格交替性 >= 0.80，彻底杜绝跳入 UI 纯色/非棋盘区域
                        if (candAlt >= 0.80f) {
                            // 距离惩罚项：优先选靠近粗定位中心的微调，防止远端伪谐振
                            val effectiveResid = hfit.residual + abs(k) * (sEst * 0.02f)
                            if (effectiveResid < bestResid) {
                                bestResid = effectiveResid
                                bestY0 = yt
                            }
                        }
                    }
                }
            }

            val maxAllowedResid = sEst * RELATIVE_RESIDUAL_GATE_RATIO
            if (bestResid <= maxAllowedResid) {
                y0 = bestY0
                vPath = "gridlines"
                vResid = bestResid
            }
        }

        // 4. 横纵交替二次精修: 纵向收敛后重跑横向
        if (vPath != "coarse") {
            val (hOk2, hParams2) = refineHorizontal(gray, sW, sH, x0.roundToInt(), y0.roundToInt(), size.roundToInt())
            if (hOk2) {
                x0 = hParams2[0]
                size = hParams2[1]
            }
        }

        // 5. 综合置信度评定
        val confidence = when {
            hOk && vPath == "bars" -> "high"
            hOk && vPath == "gridlines" -> "medium"
            hOk || vPath != "coarse" -> "medium"
            else -> "low"
        }
        val residual = max(if (hOk) hResid else 0f, if (vPath != "coarse") vResid else 99f)

        return CalibratedBox(x0, y0, size, confidence, residual)
    }

    // 8 sample directions at 0, 45, 90, 135, 180, 225, 270, 315 degrees
    private val RING_ANGLES = floatArrayOf(
        0f,
        (Math.PI / 4.0).toFloat(),
        (Math.PI / 2.0).toFloat(),
        (3.0 * Math.PI / 4.0).toFloat(),
        Math.PI.toFloat(),
        (5.0 * Math.PI / 4.0).toFloat(),
        (3.0 * Math.PI / 2.0).toFloat(),
        (7.0 * Math.PI / 4.0).toFloat()
    )

    /**
     * 纯函数：环形采样 8x8 棋盘黑白格交替度计算 (对齐 duolingo-pgn-export / DuoChess 算法)
     * 针对 64 个格子，在格子中心以 0.42 * cell_size 半径采样 8 个方向的环形点 (避开中心棋子)，
     * 取 8 点中位数作为该格底色亮度，并评估 64 格与国际象棋 (row + col) % 2 交替模式的一致率。
     * 真实棋盘得分接近 1.0 (通常 >= 0.90)；偏离 1~2 格的伪框由于落在纯色/非交替背景，得分崩塌至 0.5~0.6。
     */
    fun computeRingAlternationScore(
        gray: FloatArray,
        width: Int,
        height: Int,
        x0: Float,
        y0: Float,
        size: Float
    ): Float {
        val cs = size / 8.0f
        if (cs < 2f || width <= 0 || height <= 0) return 0f
        val rad = cs * 0.42f
        val cellColors = FloatArray(64)
        val ringSamples = FloatArray(8)

        for (r in 0 until 8) {
            val cy = y0 + (r + 0.5f) * cs
            for (c in 0 until 8) {
                val cx = x0 + (c + 0.5f) * cs
                for (a in 0 until 8) {
                    val theta = RING_ANGLES[a]
                    val px = (cx + rad * kotlin.math.cos(theta)).roundToInt().coerceIn(0, width - 1)
                    val py = (cy + rad * kotlin.math.sin(theta)).roundToInt().coerceIn(0, height - 1)
                    ringSamples[a] = gray[py * width + px]
                }
                ringSamples.sort()
                // 8 点排序取中位数 (第 3 和第 4 点均值)
                val med = (ringSamples[3] + ringSamples[4]) * 0.5f
                cellColors[r * 8 + c] = med
            }
        }

        // 计算 64 格亮度的中位数作为黑白格二值化阈值
        val sortedCols = cellColors.clone().apply { sort() }
        val thr = (sortedCols[31] + sortedCols[32]) * 0.5f

        var p0 = 0
        var p1 = 0
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val isLight = cellColors[r * 8 + c] > thr
                val even = (r + c) % 2 == 0
                if (isLight == even) p0++
                if (isLight != even) p1++
            }
        }
        return max(p0, p1) / 64.0f
    }

    /**
     * 退化路径相位试探步长因子列表 (严格限制在 [-0.5, 0.5] 范围内)
     */
    fun getDegeneratePhaseSteps(): FloatArray {
        return floatArrayOf(0f, -0.15f, 0.15f, -0.30f, 0.30f, -0.45f, 0.45f)
    }
}