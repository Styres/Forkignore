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
import android.util.Log
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
 * Vollständig transparentes Overlay-Canvas, das Berührungen durchreicht (FLAG_NOT_TOUCHABLE)
 * Zeichnet den empfohlenen Zug direkt auf das Duolingo-Brett: leuchtender Pfad, hervorgehobenes Start- und Zielfeld sowie Pfeilspitze; blendet sich nach wenigen Sekunden selbst aus
 */
class TransparentCanvasOverlay(private val context: Context) {

    private companion object {
        const val TAG = "DuLoOverlay"
        // Einheitlicher Text für alle Störungen: fehlgeschlagene Erkennung, abgewiesene Rahmen, Ausnahmen
        const val ERROR_TEXT = "Something went wrong :("
    }


    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayDrawView? = null
    private var isShowing = false
    private val scope = CoroutineScope(Dispatchers.Main)
    private var autoDismissJob: Job? = null

    private class OverlayDrawView(context: Context) : View(context) {
        var boardRect: Rect? = null
        var moveInfo: StockfishBridge.EngineEvaluation? = null
        var isWhitePerspective: Boolean = true

        // Gesetzt, solange eine Störung angezeigt wird
        var errorMessage: String? = null

        private val startPaint = Paint().apply {
            color = Color.argb(130, 0, 230, 115) // halbtransparentes Blaugrün
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val targetPaint = Paint().apply {
            color = Color.argb(140, 255, 215, 0) // halbtransparentes Goldgelb
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val arrowPaint = Paint().apply {
            color = Color.rgb(0, 255, 128) // leuchtendes Grün
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

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Störungsmeldung: eine ruhige Kachel in der Bildschirmmitte, ohne weitere Angaben
            if (errorMessage != null) {
                drawStatusPill(canvas, ERROR_TEXT)
                return
            }

            val rect = boardRect ?: return
            val move = moveInfo ?: return
            val step = (rect.right - rect.left) / 8.0f
            val uci = move.bestMove

            if (uci.length < 4 || uci == "(none)" || uci == "(checkmate)" || uci == "(stalemate)" || uci == "(invalid)") {
                val statusText = when {
                    move.isMate || uci == "(checkmate)" -> "Schachmatt"
                    uci == "(stalemate)" -> "Patt"
                    else -> ERROR_TEXT
                }
                drawStatusPill(canvas, statusText)
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

            // Start- und Zielfeld hervorheben
            val startBox = RectF(rect.left + c1 * step, rect.top + r1 * step, rect.left + (c1 + 1) * step, rect.top + (r1 + 1) * step)
            val targetBox = RectF(rect.left + c2 * step, rect.top + r2 * step, rect.left + (c2 + 1) * step, rect.top + (r2 + 1) * step)
            canvas.drawRoundRect(startBox, 16f, 16f, startPaint)
            canvas.drawRoundRect(targetBox, 16f, 16f, targetPaint)

            // Pfeilschaft und Pfeilspitze
            canvas.drawLine(x1, y1, x2, y2, arrowPaint)
            drawArrowHead(canvas, x1, y1, x2, y2)
        }

        /** Kurze Meldung mittig auf dem Bildschirm, sonst nichts */
        private fun drawStatusPill(canvas: Canvas, text: String) {
            val cx = width / 2f
            val cy = height / 2f
            val textWidth = textPaint.measureText(text)
            val pillW = (textWidth + 72f).coerceAtMost(width - 48f)
            val pillH = 108f
            val pillX = cx - pillW / 2f
            val pillY = cy - pillH / 2f
            canvas.drawRoundRect(RectF(pillX, pillY, pillX + pillW, pillY + pillH), 28f, 28f, textBgPaint)
            canvas.drawText(text, cx - textWidth / 2f, cy + 12f, textPaint)
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

    /** Empfohlenen Zug anzeigen: hervorgehobene Felder und Pfeil, sonst nichts */
    fun showSuggestion(
        boardRect: Rect,
        moveInfo: StockfishBridge.EngineEvaluation,
        isWhitePerspective: Boolean,
        // false = der Pfeil bleibt stehen (Dauerbeobachtung), bis die nächste Analyse ihn ersetzt
        autoDismiss: Boolean = true
    ) {
        if (overlayView == null) {
            initOverlayView()
        }

        overlayView?.apply {
            this.errorMessage = null
            this.boardRect = boardRect
            this.moveInfo = moveInfo
            this.isWhitePerspective = isWhitePerspective
            postInvalidate()
        }

        autoDismissJob?.cancel()
        autoDismissJob = if (autoDismiss) {
            scope.launch {
                delay(5000)
                hide()
            }
        } else {
            null
        }
    }

    /**
     * Störung anzeigen. Auf dem Bildschirm steht immer derselbe kurze Satz; der übergebene Grund
     * dient nur dem Protokoll, damit die Anzeige ruhig bleibt.
     */
    fun showError(reason: String) {
        Log.i(TAG, "Overlay meldet Störung: $reason")
        if (overlayView == null) {
            initOverlayView()
        }
        overlayView?.apply {
            this.errorMessage = reason
            this.boardRect = null
            this.moveInfo = null
            postInvalidate()
        }
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            delay(4000)
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