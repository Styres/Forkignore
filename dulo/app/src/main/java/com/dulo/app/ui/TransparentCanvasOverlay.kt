package com.dulo.app.ui

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
import com.dulo.app.engine.StockfishBridge
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

        // Störungsmeldung: einblenden, 5 Sekunden stehen lassen, ausblenden
        const val FADE_IN_MS = 220L
        const val STATUS_HOLD_MS = 5000L
        const val FADE_OUT_MS = 450L

        // Standzeit eines angeforderten Pfeils, bevor er von selbst verschwindet
        const val ARROW_HOLD_MS = 2000L
    }


    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayDrawView? = null
    private var isShowing = false
    private val scope = CoroutineScope(Dispatchers.Main)
    private var autoDismissJob: Job? = null
    /**
     * Eigener Auftrag für die Störungsmeldung.
     *
     * Bewusst getrennt vom Pfeil: früher teilten sich beide einen Auftrag, und dessen Abschluss
     * (ein `hide()`) riss auch einen inzwischen gezeichneten Pfeil wieder ab. Von außen sah es aus,
     * als würden Pfeil und Meldung gleichzeitig erscheinen und verschwinden.
     */
    private var errorJob: Job? = null

    private class OverlayDrawView(context: Context) : View(context) {
        var boardRect: Rect? = null
        var moveInfo: StockfishBridge.EngineEvaluation? = null
        var isWhitePerspective: Boolean = true

        // Gesetzt, solange eine Störung angezeigt wird
        var errorMessage: String? = null

        // Deckkraft der Störungsmeldung zwischen 0 (unsichtbar) und 1 (voll sichtbar)
        var statusAlpha: Float = 1f

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

        /** Kurze Meldung mittig auf dem Bildschirm, sanft ein- und ausgeblendet */
        private fun drawStatusPill(canvas: Canvas, text: String) {
            val alpha = statusAlpha.coerceIn(0f, 1f)
            if (alpha <= 0.01f) return
            val cx = width / 2f
            val cy = height / 2f
            val textWidth = textPaint.measureText(text)
            val pillW = (textWidth + 72f).coerceAtMost(width - 48f)
            val pillH = 108f
            val pillX = cx - pillW / 2f
            val pillY = cy - pillH / 2f

            val bgAlpha = textBgPaint.alpha
            val textAlpha = textPaint.alpha
            textBgPaint.alpha = (bgAlpha * alpha).toInt()
            textPaint.alpha = (textAlpha * alpha).toInt()
            canvas.drawRoundRect(RectF(pillX, pillY, pillX + pillW, pillY + pillH), 28f, 28f, textBgPaint)
            canvas.drawText(text, cx - textWidth / 2f, cy + 12f, textPaint)
            textBgPaint.alpha = bgAlpha
            textPaint.alpha = textAlpha
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
        // false = der Pfeil bleibt stehen, bis er ausdrücklich weggenommen wird
        autoDismiss: Boolean = true,
        // Standzeit des Pfeils, wenn er sich selbst wieder ausblendet
        dismissAfterMs: Long = ARROW_HOLD_MS
    ) {
        if (overlayView == null) {
            initOverlayView()
        }

        errorJob?.cancel()
        errorJob = null
        overlayView?.apply {
            this.errorMessage = null
            this.statusAlpha = 1f
            this.boardRect = boardRect
            this.moveInfo = moveInfo
            this.isWhitePerspective = isWhitePerspective
            postInvalidate()
        }
        setContentVisible(true)

        autoDismissJob?.cancel()
        autoDismissJob = if (autoDismiss) {
            scope.launch {
                delay(dismissAfterMs)
                // Nur den Pfeil wegnehmen, nicht das Fenster abreißen: das erneute Anlegen
                // blitzt sichtbar auf.
                clearSuggestion()
            }
        } else {
            null
        }
    }

    /**
     * Störung anzeigen: die Meldung blendet sich weich ein, steht 5 Sekunden und blendet sich
     * wieder aus. Auf dem Bildschirm steht immer derselbe kurze Satz; der übergebene Grund dient
     * nur dem Protokoll, damit die Anzeige ruhig bleibt.
     */
    fun showError(reason: String) {
        Log.i(TAG, "Overlay meldet Störung: $reason")
        if (overlayView == null) {
            initOverlayView()
        }
        val view = overlayView ?: return
        // Pfeil und Meldung schließen einander aus: die Meldung ersetzt den Pfeil
        autoDismissJob?.cancel()
        autoDismissJob = null
        view.errorMessage = reason
        view.boardRect = null
        view.moveInfo = null
        view.statusAlpha = 0f
        view.postInvalidate()
        setContentVisible(true)

        errorJob?.cancel()
        errorJob = scope.launch {
            animateStatusAlpha(view, from = 0f, to = 1f, durationMs = FADE_IN_MS)
            delay(STATUS_HOLD_MS)
            animateStatusAlpha(view, from = 1f, to = 0f, durationMs = FADE_OUT_MS)
            // Nur aufräumen, solange die Meldung noch das ist, was angezeigt wird. Kam
            // zwischenzeitlich ein Pfeil, bleibt der stehen.
            if (view.errorMessage != null) {
                view.errorMessage = null
                view.statusAlpha = 1f
                view.postInvalidate()
            }
        }
    }

    /**
     * Alles wegnehmen und jede laufende Animation abbrechen.
     *
     * Wird beim Ausschalten und beim Beenden gebraucht: eine Meldung, die noch in ihrer Haltezeit
     * steht, darf nicht weiterlaufen, nachdem der Nutzer abgeschaltet hat.
     */
    fun dismissAll() {
        autoDismissJob?.cancel()
        autoDismissJob = null
        errorJob?.cancel()
        errorJob = null
        overlayView?.apply {
            errorMessage = null
            moveInfo = null
            boardRect = null
            statusAlpha = 1f
            postInvalidate()
        }
    }

    /** Deckkraft der Meldung schrittweise verändern (rund 60 Bilder je Sekunde) */
    private suspend fun animateStatusAlpha(view: OverlayDrawView, from: Float, to: Float, durationMs: Long) {
        val frameMs = 16L
        val steps = (durationMs / frameMs).toInt().coerceAtLeast(1)
        for (step in 0..steps) {
            view.statusAlpha = from + (to - from) * (step / steps.toFloat())
            view.postInvalidate()
            delay(frameMs)
        }
        view.statusAlpha = to
        view.postInvalidate()
    }

    /**
     * Inhalt vorübergehend unsichtbar schalten, ohne das Fenster abzureißen.
     *
     * Für jede Aufnahme muss der Pfeil kurz weg, sonst verfälscht er die Erkennung. Würde dafür das
     * Fenster entfernt und gleich wieder angelegt, blitzt der Bildschirm sichtbar auf - das war die
     * Ursache des Flackerns. Ein Umschalten der Sichtbarkeit bleibt ruhig.
     */
    fun setContentVisible(visible: Boolean) {
        overlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // FLAG_SECURE nimmt dieses Fenster von der Bildschirmaufnahme aus. Damit
                    // landet der eigene Pfeil nicht in dem Bild, das gleich erkannt wird - und
                    // genau deshalb muss er für die Aufnahme nicht mehr ausgeblendet werden.
                    // Ob das Gerät sich daran hält, prüft der Dienst einmalig nach (secureCaptureOk).
                    WindowManager.LayoutParams.FLAG_SECURE,
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

    /**
     * Nimmt den Pfeil weg, lässt das Fenster aber stehen.
     *
     * Bewusst nicht [hide]: das entfernt das Fenster aus dem WindowManager, und das erneute
     * Anlegen beim nächsten Zug blitzt sichtbar auf. Das Fenster ist durchsichtig und für die
     * Bildschirmaufnahme ohnehin unsichtbar - es darf einfach stehenbleiben.
     */
    fun clearSuggestion() {
        autoDismissJob?.cancel()
        autoDismissJob = null
        errorJob?.cancel()
        errorJob = null
        overlayView?.apply {
            moveInfo = null
            boardRect = null
            errorMessage = null
            postInvalidate()
        }
    }

    /**
     * Steht gerade ein Pfeil auf dem Bildschirm? Der Dienst fragt das ab, bevor er dieselbe
     * Empfehlung ein zweites Mal zeichnen würde: ein erneutes Zeichnen sähe wie eine neue
     * Empfehlung aus, obwohl es dieselbe ist.
     */
    fun hasVisibleSuggestion(): Boolean {
        val view = overlayView ?: return false
        return isShowing && view.visibility == View.VISIBLE &&
            view.moveInfo != null && view.errorMessage == null
    }

    fun hide() {
        autoDismissJob?.cancel()
        autoDismissJob = null
        errorJob?.cancel()
        errorJob = null
        overlayView?.let {
            if (isShowing) {
                windowManager.removeView(it)
                isShowing = false
            }
        }
        overlayView = null
    }
}