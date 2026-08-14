package com.chess.copilot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.WindowManager
import com.chess.copilot.engine.StockfishBridge

/**
 * 全透明穿透悬浮 Canvas (FLAG_NOT_TOUCHABLE)
 * 在多邻国棋盘上实时绘制发光走子路径、高亮起点/终点光圈，完全不影响下棋点击操作
 */
class TransparentCanvasOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayDrawView? = null
    private var isShowing = false

    private class OverlayDrawView(context: Context) : View(context) {
        var boardRect: Rect? = null
        var moveInfo: StockfishBridge.EngineEvaluation? = null
        var isWhitePerspective: Boolean = true

        private val startPaint = Paint().apply {
            color = Color.argb(120, 0, 230, 115) // 半透明青绿
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val targetPaint = Paint().apply {
            color = Color.argb(130, 255, 215, 0) // 半透明金黄
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val arrowPaint = Paint().apply {
            color = Color.rgb(0, 255, 128) // 荧光亮绿
            style = Paint.Style.STROKE
            strokeWidth = 14f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        private val textBgPaint = Paint().apply {
            color = Color.argb(220, 20, 20, 20)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 38f
            isAntiAlias = true
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rect = boardRect ?: return
            val move = moveInfo ?: return

            val step = (rect.right - rect.left) / 8.0f
            val uci = move.bestMove
            if (uci.length < 4) return

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

            // 2. 绘制指示箭头
            canvas.drawLine(x1, y1, x2, y2, arrowPaint)

            // 3. 绘制上方局势胶囊
            val scoreText = "Best: ${move.bestMove} | Eval: ${if (move.evalScore >= 0) "+" else ""}${String.format("%.2f", move.evalScore)}"
            val pillW = 420f
            val pillH = 70f
            val pillX = rect.left + (rect.width() - pillW) / 2f
            val pillY = (rect.top - pillH - 20f).coerceAtLeast(40f)

            canvas.drawRoundRect(RectF(pillX, pillY, pillX + pillW, pillY + pillH), 35f, 35f, textBgPaint)
            canvas.drawText(scoreText, pillX + 28f, pillY + 48f, textPaint)
        }
    }

    fun showSuggestion(boardRect: Rect, moveInfo: StockfishBridge.EngineEvaluation, isWhitePerspective: Boolean) {
        if (overlayView == null) {
            overlayView = OverlayDrawView(context)
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
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
            )
            windowManager.addView(overlayView, params)
            isShowing = true
        }

        overlayView?.apply {
            this.boardRect = boardRect
            this.moveInfo = moveInfo
            this.isWhitePerspective = isWhitePerspective
            postInvalidate()
        }
    }

    fun hide() {
        overlayView?.let {
            if (isShowing) {
                windowManager.removeView(it)
                isShowing = false
            }
        }
        overlayView = null
    }
}
