package com.chess.copilot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.chess.copilot.engine.StockfishBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 全透明穿透悬浮 Canvas (FLAG_NOT_TOUCHABLE)
 * 在多邻国棋盘上实时绘制发光走子路径、高亮起点/终点光圈与箭头，4 秒后自动淡出隐藏
 */
class TransparentCanvasOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayDrawView? = null
    private var isShowing = false
    private val scope = CoroutineScope(Dispatchers.Main)
    private var autoDismissJob: Job? = null

    private class OverlayDrawView(context: Context) : View(context) {
        var boardRect: Rect? = null
        var moveInfo: StockfishBridge.EngineEvaluation? = null
        var isWhitePerspective: Boolean = true
        var medianSim: Float = 1.0f
        var occupiedCount: Int = 0
        var detectedPerspective: Boolean? = null
        
        // 【新增】用于接收格式化后的人类可读着法 (如 R-d4d5)
        var displayMoveStr: String = ""
        // 【新增】用于显示大屏故障看板
        var errorMessage: String? = null 
        // 【新增】用于在屏幕显示当前计算出的FEN，固化证据
        var fenString: String = "" 

        // 【新增】棋盘调试红框画笔，让假框和偏移无所遁形
        private val boardDebugPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        // 【新增】故障看板底色画笔
        private val errorBgPaint = Paint().apply {
            color = Color.argb(230, 180, 0, 0) // 半透明深红
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val startPaint = Paint().apply {
            color = Color.argb(130, 0, 230, 115) // 半透明青绿
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val targetPaint = Paint().apply {
            color = Color.argb(140, 255, 215, 0) // 半透明金黄
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val arrowPaint = Paint().apply {
            color = Color.rgb(0, 255, 128) // 荧光亮绿
            strokeWidth = 14f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val arrowHeadPaint = Paint().apply {
            color = Color.rgb(0, 255, 128)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val textBgPaint = Paint().apply {
            color = Color.argb(230, 20, 24, 30)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 34f
            isAntiAlias = true
            isFakeBoldText = true
        }

        private val subTextPaint = Paint().apply {
            color = Color.rgb(180, 215, 255)
            textSize = 26f
            isAntiAlias = true
        }

        private val fenTextPaint = Paint().apply {
            color = Color.rgb(180, 215, 255)
            textSize = 22f
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val screenW = width.toFloat()
            val paddingX = 24f
            val maxAvailableW = screenW - 48f

            // 【新增】优先绘制故障看板
            val errMsg = errorMessage
            if (errMsg != null) {
                val cx = width / 2f
                val cy = height / 2f
                val line1 = "❌ $errMsg"
                val line2 = if (fenString.isNotEmpty()) "FEN: $fenString" else ""
                
                val w1 = textPaint.measureText(line1)
                var fenSize = 22f
                fenTextPaint.textSize = fenSize
                val maxFenAvailableW = maxAvailableW - paddingX * 2
                while (line2.isNotEmpty() && fenTextPaint.measureText(line2) > maxFenAvailableW && fenSize > 14f) {
                    fenSize -= 1f
                    fenTextPaint.textSize = fenSize
                }
                val w2 = if (line2.isNotEmpty()) fenTextPaint.measureText(line2) else 0f
                val pillW = (maxOf(w1, w2) + paddingX * 2).coerceIn(400f, maxAvailableW)
                val pillH = 160f
                val pillX = cx - pillW / 2f
                val pillY = cy - pillH / 2f
                
                canvas.drawRoundRect(RectF(pillX, pillY, pillX + pillW, pillY + pillH), 24f, 24f, errorBgPaint)
                canvas.drawText(line1, pillX + paddingX, cy - 20f, textPaint)
                if (line2.isNotEmpty()) {
                    canvas.drawText(line2, pillX + paddingX, cy + 30f, fenTextPaint)
                }
                
                // 如果有假框坐标，也一并画出来让它暴露
                boardRect?.let { canvas.drawRect(it, boardDebugPaint) }
                return
            }

            val rect = boardRect ?: return
            val move = moveInfo ?: return

            // 【新增】画出 AI 认为的棋盘边界
            canvas.drawRect(rect, boardDebugPaint)

            val step = (rect.right - rect.left) / 8.0f
            val uci = move.bestMove
            val conflictStr = if (detectedPerspective != null && detectedPerspective != isWhitePerspective) {
                "(探测:${if (detectedPerspective == true) "白" else "黑"})"
            } else ""
            val perspectiveStr = "${if (isWhitePerspective) "执白" else "执黑"}$conflictStr"

            if (uci.length < 4 || uci == "(none)" || uci == "(checkmate)" || uci == "(stalemate)" || uci == "(invalid)") {
                val statusStr = when {
                    uci == "(invalid)" -> "识别异常 (局面非法)"
                    move.isMate || uci == "(checkmate)" -> "胜负已分 (将杀)"
                    uci == "(stalemate)" -> "和棋 (逼和)"
                    else -> "无合法走法"
                }
                val line1 = "局面: $statusStr"
                val line2 = "视角: $perspectiveStr | Sim: ${String.format("%.3f", medianSim)} | 占位: $occupiedCount"
                val line3 = if (fenString.isNotEmpty()) "FEN: $fenString" else ""

                // 紧凑包裹内容宽度 (Wrap Content)
                val w1 = textPaint.measureText(line1)
                val w2 = subTextPaint.measureText(line2)
                var fenSize = 22f
                fenTextPaint.textSize = fenSize
                val maxFenAvailableW = maxAvailableW - paddingX * 2
                while (line3.isNotEmpty() && fenTextPaint.measureText(line3) > maxFenAvailableW && fenSize > 14f) {
                    fenSize -= 1f
                    fenTextPaint.textSize = fenSize
                }
                val w3 = if (line3.isNotEmpty()) fenTextPaint.measureText(line3) else 0f
                val pillW = (maxOf(w1, w2, w3) + paddingX * 2).coerceIn(400f, maxAvailableW)
                val pillH = 150f
                val idealPillX = rect.left + (rect.width() - pillW) / 2f
                val pillX = idealPillX.coerceIn(24f, (screenW - pillW - 24f).coerceAtLeast(24f))
                val pillY = (rect.top - pillH - 25f).coerceAtLeast(50f)

                canvas.drawRoundRect(RectF(pillX, pillY, pillX + pillW, pillY + pillH), 24f, 24f, textBgPaint)
                canvas.drawText(line1, pillX + paddingX, pillY + 42f, textPaint)
                canvas.drawText(line2, pillX + paddingX, pillY + 84f, subTextPaint)
                if (line3.isNotEmpty()) {
                    canvas.drawText(line3, pillX + paddingX, pillY + 126f, fenTextPaint)
                }
                return
            }

            val fromCol = uci[0] - 'a'
            val fromRank = uci[1] - '0'
            val toCol = uci[2] - 'a'
            val toRank = uci[3] - '0'

            val r1 = if (isWhitePerspective) 8 - fromRank else fromRank - 1
            val c1 = if (isWhitePerspective) fromCol else 7 - fromCol
            val r2 = if (isWhitePerspective) 8 - toRank else toRank - 1
            val c2 = if (isWhitePerspective) toCol else 7 - toCol

            val x1 = rect.left + (c1 + 0.5f) * step
            val y1 = rect.top + (r1 + 0.5f) * step
            val x2 = rect.left + (c2 + 0.5f) * step
            val y2 = rect.top + (r2 + 0.5f) * step

            // 1. 绘制起点和终点高亮格子
            val startBox = RectF(rect.left + c1 * step, rect.top + r1 * step, rect.left + (c1 + 1) * step, rect.top + (r1 + 1) * step)
            val targetBox = RectF(rect.left + c2 * step, rect.top + r2 * step, rect.left + (c2 + 1) * step, rect.top + (r2 + 1) * step)
            canvas.drawRoundRect(startBox, 16f, 16f, startPaint)
            canvas.drawRoundRect(targetBox, 16f, 16f, targetPaint)

            // 2. 绘制指示箭头干身与箭头三角形头部
            canvas.drawLine(x1, y1, x2, y2, arrowPaint)
            drawArrowHead(canvas, x1, y1, x2, y2)

            // 3. 绘制上方局势胶囊 (招法评估 + 底层取证遥测 + FEN)
            val scoreStr = if (move.isMate) "MATE" else "${if (move.evalScore >= 0) "+" else ""}${String.format("%.2f", move.evalScore)}"
            val depthStr = if (move.depth <= 0) "[兜底]" else "深${move.depth}"
            // 【修改】使用传入的带有棋子类型的 displayMoveStr
            val line1 = "招法: $displayMoveStr | 评估: $scoreStr ($depthStr)"
            val line2 = "视角: $perspectiveStr | Sim: ${String.format("%.3f", medianSim)} | 占位: $occupiedCount"
            val line3 = if (fenString.isNotEmpty()) "FEN: $fenString" else ""

            // 紧凑包裹内容宽度 (Wrap Content)
            val w1 = textPaint.measureText(line1)
            val w2 = subTextPaint.measureText(line2)
            var fenSize = 22f
            fenTextPaint.textSize = fenSize
            val maxFenAvailableW = maxAvailableW - paddingX * 2
            while (line3.isNotEmpty() && fenTextPaint.measureText(line3) > maxFenAvailableW && fenSize > 14f) {
                fenSize -= 1f
                fenTextPaint.textSize = fenSize
            }
            val w3 = if (line3.isNotEmpty()) fenTextPaint.measureText(line3) else 0f
            val pillW = (maxOf(w1, w2, w3) + paddingX * 2).coerceIn(400f, maxAvailableW)
            val pillH = 150f
            val idealPillX = rect.left + (rect.width() - pillW) / 2f
            val pillX = idealPillX.coerceIn(24f, (screenW - pillW - 24f).coerceAtLeast(24f))
            val pillY = (rect.top - pillH - 25f).coerceAtLeast(50f)

            canvas.drawRoundRect(RectF(pillX, pillY, pillX + pillW, pillY + pillH), 24f, 24f, textBgPaint)
            canvas.drawText(line1, pillX + paddingX, pillY + 42f, textPaint)
            canvas.drawText(line2, pillX + paddingX, pillY + 84f, subTextPaint)
            if (line3.isNotEmpty()) {
                canvas.drawText(line3, pillX + paddingX, pillY + 126f, fenTextPaint)
            }
        }

        private fun drawArrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
            val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            val headLen = 32.0
            val headAngle = Math.PI / 6.0

            val xLeft = x2 - headLen * cos(angle - headAngle)
            val yLeft = y2 - headLen * sin(angle - headAngle)
            val xRight = x2 - headLen * cos(angle + headAngle)
            val yRight = y2 - headLen * sin(angle + headAngle)

            val headPath = Path().apply {
                moveTo(x2, y2)
                lineTo(xLeft.toFloat(), yLeft.toFloat())
                lineTo(xRight.toFloat(), yRight.toFloat())
                close()
            }
            canvas.drawPath(headPath, arrowHeadPaint)
        }
    }

    // 【修改】增加 displayMoveStr 和 fenString 参数
    fun showSuggestion(
        boardRect: Rect,
        moveInfo: StockfishBridge.EngineEvaluation,
        isWhitePerspective: Boolean,
        displayMoveStr: String,
        fenString: String,
        medianSim: Float = 1.0f,
        occupiedCount: Int = 0,
        detectedPerspective: Boolean? = null
    ) {
        if (overlayView == null) {
            initOverlayView()
        }

        overlayView?.apply {
            this.errorMessage = null
            this.boardRect = boardRect
            this.moveInfo = moveInfo
            this.isWhitePerspective = isWhitePerspective
            this.displayMoveStr = displayMoveStr
            this.fenString = fenString
            this.medianSim = medianSim
            this.occupiedCount = occupiedCount
            this.detectedPerspective = detectedPerspective
            postInvalidate()
        }

        // 【修改】延长至 5 秒方便用户查看或截图 FEN
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            delay(5000) 
            hide()
        }
    }

    // 【新增】红牌警告弹窗，用于大屏展示各种拦截与非法局面
    fun showError(reason: String, errorRect: Rect? = null, fenString: String = "") {
        if (overlayView == null) {
            initOverlayView()
        }
        overlayView?.apply {
            this.errorMessage = reason
            this.boardRect = errorRect
            this.fenString = fenString
            postInvalidate()
        }
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch { 
            delay(5000)
            hide() 
        }
    }

    private fun initOverlayView() {
        overlayView = OverlayDrawView(context)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(overlayView, params)
        isShowing = true
    }

    fun hide() {
        autoDismissJob?.cancel()
        autoDismissJob = null
        overlayView?.let {
            if (isShowing) {
                windowManager.removeView(it)
                isShowing = false
            }
        }
        overlayView = null
    }
}