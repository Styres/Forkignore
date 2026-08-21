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
 * Vollständig transparentes Overlay-Canvas, das Berührungen durchreicht (FLAG_NOT_TOUCHABLE)
 * Zeichnet den empfohlenen Zug direkt auf das Duolingo-Brett: leuchtender Pfad, hervorgehobenes Start- und Zielfeld sowie Pfeilspitze; blendet sich nach wenigen Sekunden selbst aus
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
        
        // Menschenlesbarer Zugtext, z. B. R-d4d5
        var displayMoveStr: String = ""
        // Text der grossflächigen Fehlertafel
        var errorMessage: String? = null 
        // Aktuell berechnetes FEN, damit der Befund auf dem Bildschirm belegt ist
        var fenString: String = "" 

        // Roter Rahmen um das erkannte Brett, damit ein falscher Rahmen oder ein Versatz sofort auffällt
        private val boardDebugPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        // Hintergrund der Fehlertafel
        private val errorBgPaint = Paint().apply {
            color = Color.argb(230, 180, 0, 0) // halbtransparentes Dunkelrot
            style = Paint.Style.FILL
            isAntiAlias = true
        }

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

            // Die Fehlertafel hat Vorrang vor allem anderen
            val errMsg = errorMessage
            if (errMsg != null) {
                val cx = width / 2f
                val cy = height / 2f
                val line1 = "Fehler: $errMsg"
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
                
                // Liegt ein (falscher) Brettrahmen vor, wird er mitgezeichnet, damit er sichtbar wird
                boardRect?.let { canvas.drawRect(it, boardDebugPaint) }
                return
            }

            val rect = boardRect ?: return
            val move = moveInfo ?: return

            // Vom Erkenner angenommene Brettgrenze einzeichnen
            canvas.drawRect(rect, boardDebugPaint)

            val step = (rect.right - rect.left) / 8.0f
            val uci = move.bestMove
            val conflictStr = if (detectedPerspective != null && detectedPerspective != isWhitePerspective) {
                "(erkannt: ${if (detectedPerspective == true) "Weiß" else "Schwarz"})"
            } else ""
            val perspectiveStr = "${if (isWhitePerspective) "Weiß" else "Schwarz"}$conflictStr"

            if (uci.length < 4 || uci == "(none)" || uci == "(checkmate)" || uci == "(stalemate)" || uci == "(invalid)") {
                val statusStr = when {
                    uci == "(invalid)" -> "Erkennungsfehler (unmögliche Stellung)"
                    move.isMate || uci == "(checkmate)" -> "Partie entschieden (Schachmatt)"
                    uci == "(stalemate)" -> "Remis (Patt)"
                    else -> "Kein legaler Zug"
                }
                val line1 = "Stellung: $statusStr"
                val line2 = "Perspektive: $perspectiveStr | Sim: ${String.format("%.3f", medianSim)} | belegt: $occupiedCount"
                val line3 = if (fenString.isNotEmpty()) "FEN: $fenString" else ""

                // Breite eng am Inhalt ausrichten (Wrap Content)
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

            // 1. Start- und Zielfeld hervorheben
            val startBox = RectF(rect.left + c1 * step, rect.top + r1 * step, rect.left + (c1 + 1) * step, rect.top + (r1 + 1) * step)
            val targetBox = RectF(rect.left + c2 * step, rect.top + r2 * step, rect.left + (c2 + 1) * step, rect.top + (r2 + 1) * step)
            canvas.drawRoundRect(startBox, 16f, 16f, startPaint)
            canvas.drawRoundRect(targetBox, 16f, 16f, targetPaint)

            // 2. Pfeilschaft und Pfeilspitze zeichnen
            canvas.drawLine(x1, y1, x2, y2, arrowPaint)
            drawArrowHead(canvas, x1, y1, x2, y2)

            // 3. Infofeld darüber zeichnen (Zug, Bewertung, Telemetrie und FEN)
            val scoreStr = if (move.isMate) "MATE" else "${if (move.evalScore >= 0) "+" else ""}${String.format("%.2f", move.evalScore)}"
            val depthStr = if (move.depth <= 0) "[Fallback]" else "Tiefe ${move.depth}"
            // Zugtext mit vorangestelltem Figurentyp verwenden
            val line1 = "Zug: $displayMoveStr | Bewertung: $scoreStr ($depthStr)"
            val line2 = "Perspektive: $perspectiveStr | Sim: ${String.format("%.3f", medianSim)} | belegt: $occupiedCount"
            val line3 = if (fenString.isNotEmpty()) "FEN: $fenString" else ""

            // Breite eng am Inhalt ausrichten (Wrap Content)
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

    // Anzeige des empfohlenen Zuges inklusive displayMoveStr und fenString
    fun showSuggestion(
        boardRect: Rect,
        moveInfo: StockfishBridge.EngineEvaluation,
        isWhitePerspective: Boolean,
        displayMoveStr: String,
        fenString: String,
        medianSim: Float = 1.0f,
        occupiedCount: Int = 0,
        detectedPerspective: Boolean? = null,
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
            this.displayMoveStr = displayMoveStr
            this.fenString = fenString
            this.medianSim = medianSim
            this.occupiedCount = occupiedCount
            this.detectedPerspective = detectedPerspective
            postInvalidate()
        }

        // 5 Sekunden Standzeit, damit das FEN in Ruhe gelesen oder abfotografiert werden kann.
        // In der Dauerbeobachtung entfällt das Ausblenden: dort ersetzt erst der nächste Zug den Pfeil.
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

    // Rote Warntafel für abgewiesene Rahmen und unmögliche Stellungen
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