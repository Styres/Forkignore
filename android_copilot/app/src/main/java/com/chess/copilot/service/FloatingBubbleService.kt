package com.chess.copilot.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.chess.copilot.core.ChessLocator
import com.chess.copilot.core.UltraRobustClassifier
import com.chess.copilot.engine.StockfishBridge
import com.chess.copilot.ui.MainActivity
import com.chess.copilot.ui.TransparentCanvasOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Dienst für die Blase am Bildschirmrand (Floating Bubble)
 * Vordergrunddienst nach den Vorgaben von Android 14
 * Kernmechanik:
 * 1. Keine Selbstverschmutzung durch das Overlay: Blase und transparente Ebene werden vor der Aufnahme kurz ausgeblendet und danach wieder eingeblendet
 * 2. Dauerhaft geöffnetes VirtualDisplay, dessen Übergangsframes fortlaufend verworfen werden - so entfällt das Abmelden der Sitzung pro Klick und der erste Frame ist nie veraltet
 * 3. Geräteunabhängiges Kopieren der Pixel über pixelStride
 * 4. Diagnosedateien direkt auf dem Gerät (filesDir/debug/)
 */
class FloatingBubbleService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        // Der Umschalter in der MainActivity beendet den Dienst über diese Aktion
        const val ACTION_STOP = "com.chess.copilot.action.STOP_BUBBLE"

        // Laufzustand für den Umschalter: der Dienst läuft im selben Prozess wie die Oberfläche,
        // ein Flag genügt hier und kommt ohne Binder oder Broadcast aus.
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "chess_copilot_service"

        // Ab dieser Druckdauer gilt eine Berührung der Blase als langer Druck (Perspektive umschalten)
        private const val LONG_PRESS_MS = 500L
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var transparentOverlay: TransparentCanvasOverlay? = null
    private var classifier: UltraRobustClassifier? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420
    private var isAnalyzing = false
    @Volatile
    private var isCapturingFrame = false
    @Volatile
    private var sessionLockedPerspective: Boolean? = null
    // Wurde die Perspektive per langem Druck auf die Blase von Hand gesetzt, bleibt sie stehen:
    // die automatische Erkennung darf eine ausdrückliche Ansage des Nutzers nicht überschreiben.
    @Volatile
    private var manualPerspectiveLock = false
    // Telemetrie zur Stabilität der Erkennung (Lektion aus bug_19): unterschiedliche FEN bei zwei Klicks auf ein unverändertes Brett belegen ein Flackern der Erkennung und damit die Ursache wechselnder Empfehlungen
    private var lastFen: String? = null
    // Zähler für Konflikte mit der Perspektivsperre (Lektion aus bug_19): widerspricht die Erkennung der Sperre dauerhaft, wird neu gesperrt, damit eine Fehlsperre nicht die ganze Sitzung blockiert
    private var perspectiveConflictStreak = 0

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        transparentOverlay = TransparentCanvasOverlay(this)
        classifier = UltraRobustClassifier(this)
        StockfishBridge.init(this)

        captureThread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        updateScreenMetrics()
        createFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Der Umschalter in der Oberfläche hat "aus" gewählt: Dienst geordnet beenden
        if (intent?.action == ACTION_STOP) {
            Toast.makeText(this, "Overlay-Assistent beendet", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        if (intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

            if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
                setupMediaProjection(resultCode, resultData)
            } else if (mediaProjection == null) {
                // Lautes Scheitern: fehlerhafte Berechtigungsdaten müssen sofort sichtbar und protokolliert sein, sonst wirkt die Blase fälschlich funktionsfähig
                val reason = "Fehlerhafter Berechtigungs-Intent (resultCode=$resultCode, dataPresent=${resultData != null})"
                recordProjectionState("Initialisierung fehlgeschlagen: $reason")
                Toast.makeText(this, "Fehlerhafte Daten der Bildschirmaufnahme-Berechtigung: $reason. Bitte auf die Blase tippen und erneut freigeben", Toast.LENGTH_LONG).show()
            }
        } else {
            recordProjectionState("Initialisierung fehlgeschlagen: Dienst vom System neu gestartet (intent=null), das Token ist ungültig")
            Toast.makeText(this, "Der Dienst wurde vom System neu gestartet, die Aufnahmeberechtigung ist abgelaufen. Bitte auf die Blase tippen und erneut freigeben", Toast.LENGTH_LONG).show()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Duolingo-Schachassistent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Overlay-Dienst am Bildschirmrand"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Der Duolingo-Schachassistent läuft")
            .setContentText("Auf die Blase tippen, um den besten Zug von Stockfish zu erhalten")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                recordProjectionState("Initialisierung fehlgeschlagen: getMediaProjection lieferte null (Token ungültig oder bereits verbraucht)")
                Toast.makeText(this, "MediaProjection konnte nicht initialisiert werden", Toast.LENGTH_SHORT).show()
                return
            }

            // Lebenszyklus der Sitzung beobachten: beendet das System die Freigabe, werden die Referenzen sofort freigegeben und der Nutzer informiert, damit kein Zombie-Zustand entsteht (Referenz vorhanden, Sitzung tot)
            mediaProjection?.registerCallback(projectionCallback, captureHandler)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ChessScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                captureHandler
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isCapturingFrame) {
                    val img = reader.acquireLatestImage()
                    img?.close()
                }
            }, captureHandler)

            recordProjectionState("Aufnahmesitzung erfolgreich aufgebaut (${screenWidth}x${screenHeight}, dpi=$screenDensity)")
            Toast.makeText(this, "Der Overlay-Assistent ist bereit", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            // Rücknahme einer halben Initialisierung: scheitert ein Schritt, werden alle bereits angelegten Ressourcen verworfen, damit die drei Referenzen konsistent bleiben
            recordProjectionState("Ausnahme beim Start der Aufnahmesitzung: ${e.javaClass.simpleName}: ${e.message}")
            rollbackProjection()
            Toast.makeText(this, "Ausnahme beim Start der Bildschirmaufnahme: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            recordProjectionState("Das System hat die Bildschirmfreigabe beendet (MediaProjection.Callback#onStop)")
            rollbackProjection()
            serviceScope.launch(Dispatchers.Main) {
                Toast.makeText(
                    this@FloatingBubbleService,
                    "Die Bildschirmfreigabe wurde vom System beendet. Bitte auf die Blase tippen und erneut freigeben",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Projektionsressourcen zurücknehmen, ohne selbst stop() aufzurufen (im onStop-Callback hat das System die Sitzung bereits beendet)
     */
    private fun rollbackProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    /**
     * Zustand der Aufnahmesitzung zur Selbstforensik speichern (filesDir/debug/projection_state.txt), wird auf der Diagnoseseite der MainActivity angezeigt
     */
    private fun recordProjectionState(desc: String) {
        try {
            val debugDir = File(filesDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            File(debugDir, "projection_state.txt").writeText("$desc\nZeitpunkt: $timeStr\n")
        } catch (_: Exception) {}
    }

    private fun createFloatingBubble() {
        bubbleView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(Color.argb(210, 88, 204, 2))
            setPadding(20, 20, 20, 20)
            elevation = 25f
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 180
            y = screenHeight / 3
        }

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false
            private var touchDownTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        touchDownTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (dx * dx + dy * dy > 25) {
                            isClick = false
                        }
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(bubbleView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            if (System.currentTimeMillis() - touchDownTime >= LONG_PRESS_MS) {
                                // Langer Druck: eigene Farbe von Hand umschalten
                                togglePerspectiveManually()
                            } else {
                                onBubbleClicked()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleView, params)
    }

    /**
     * Langer Druck auf die Blase: die eigene Farbe von Hand umschalten.
     * Notausstieg für den Fall, dass die automatische Erkennung die Seiten vertauscht - der Pfeil
     * zeigte dann Züge für die Figuren des Gegners. Ab hier gilt die gesetzte Perspektive fest,
     * bis erneut lang gedrückt wird oder der Dienst neu startet.
     */
    private fun togglePerspectiveManually() {
        val current = sessionLockedPerspective ?: true
        val flipped = !current
        sessionLockedPerspective = flipped
        manualPerspectiveLock = true
        perspectiveConflictStreak = 0
        lastFen = null
        Toast.makeText(
            this,
            "Eigene Farbe von Hand auf ${if (flipped) "Weiß" else "Schwarz"} gesetzt (erneut lange drücken zum Zurücksetzen)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun onBubbleClicked() {
        if (isAnalyzing) return

        if (mediaProjection == null || imageReader == null || virtualDisplay == null) {
            Toast.makeText(this, "Die Aufnahmeberechtigung ist abgelaufen, bitte den Overlay-Assistenten neu starten", Toast.LENGTH_LONG).show()
            val reAuthIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(reAuthIntent)
            return
        }

        isAnalyzing = true

        serviceScope.launch {
            var screenBitmap: Bitmap? = null
            try {
                transparentOverlay?.hide()
                bubbleView?.visibility = View.INVISIBLE
                // 150 ms statt 60 ms (Verdacht aus bug_13/14): zwischen dem Entfernen der Fenster und dem Eintreffen eines sauberen Frames im Puffer liegt eine Kompositionsverzögerung.
                // Wartet man zu kurz, enthält die Aufnahme noch den alten Frame mit Rahmen und Pfeil; verdeckt der die 8. Linie, sieht es nach einer übersehenen Figur aus
                delay(150)

                screenBitmap = captureCurrentScreenBitmap()

                bubbleView?.visibility = View.VISIBLE

                if (screenBitmap == null) {
                    Toast.makeText(this@FloatingBubbleService, "Bild wird noch aufgenommen, bitte kurz danach erneut versuchen", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                var locateResult = withContext(Dispatchers.Default) {
                    ChessLocator.locateBoard(screenBitmap)
                }
                var boardRect = locateResult.rect

                // Umgang mit zugeschnittenen Frames (negative Ränder bzw. Überlauf): nur melden, nicht erzwingen - ein Rect außerhalb des Bildes würde beim Zuschneiden eine Ausnahme werfen,
                // und ein unvollständiges Bild ist ohnehin nicht zuverlässig erkennbar; stattdessen erscheinen die Fehlertafel und der rote Rahmen
                if (locateResult.isCropped) {
                    val croppedCopy = try {
                        screenBitmap.copy(screenBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    } catch (_: Exception) { null }
                    saveDebugArtifactsAsync(croppedCopy, boardRect, locateResult, "CROPPED_FRAME_NO_FEN")
                    withContext(Dispatchers.Main) {
                        transparentOverlay?.showError("Außerhalb des Bildes: das Brett ist unvollständig, bitte mittig ausrichten", boardRect)
                    }
                    return@launch
                }

                var detailedResp = withContext(Dispatchers.Default) {
                    classifier?.classifyBoardDetailed(
                        bitmap = screenBitmap,
                        boardRect = boardRect,
                        overridePerspective = sessionLockedPerspective
                    )
                }

                // Rettung über den Zweitkandidaten (Befund aus bug_19/superbug): liefert der Hauptrahmen eine unmögliche Stellung oder wird er komplett abgewiesen,
                // ist das ein sicheres Zeichen für einen falsch gewählten Rahmen des Locators (die Energie einer UI-Kante kann den echten Rahmen überbieten, 710 gegen 670, ein einzelnes Maximum ist also nicht verlässlich)
                // Zusätzlich darf die Confidence des Locators nicht "low" sein, damit unvollständige Rahmen wie Randbereiche der Oberfläche ausscheiden
                val primaryNeedsRescue = locateResult.confidence == "low" ||
                    (detailedResp is UltraRobustClassifier.ClassificationResponse.Success &&
                    StockfishBridge.validateFenSanity(detailedResp.result.fullFen) != null) ||
                    detailedResp is UltraRobustClassifier.ClassificationResponse.Rejected

                if (primaryNeedsRescue) {
                    val candidates = withContext(Dispatchers.Default) {
                        ChessLocator.locateTopCandidates(screenBitmap, 2)
                    }
                    if (candidates.size >= 2) {
                        val rescueRect = candidates[1].rect
                        val rescueResp = withContext(Dispatchers.Default) {
                            classifier?.classifyBoardDetailed(
                                bitmap = screenBitmap,
                                boardRect = rescueRect,
                                overridePerspective = sessionLockedPerspective
                            )
                        }
                        // Harte Bedingung: das Residuum des Zweitkandidaten muss klein genug sein (höchstens 5 % eines Feldes) und darf nicht aus einem entarteten Fit stammen, sonst ersetzt ein Fehler nur den nächsten
                        val rescueMaxResid = (rescueRect.width() / 8.0f) * 0.05f
                        if (rescueResp is UltraRobustClassifier.ClassificationResponse.Success &&
                            StockfishBridge.validateFenSanity(rescueResp.result.fullFen) == null &&
                            candidates[1].residual <= rescueMaxResid && 
                            candidates[1].confidence != "low" 
                        ) {
                            locateResult = candidates[1]
                            boardRect = rescueRect
                            detailedResp = rescueResp
                        }
                    }
                }

                val copyForDebug = try {
                    screenBitmap.copy(screenBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) { null }

                when (detailedResp) {
                    is UltraRobustClassifier.ClassificationResponse.Success -> {
                        var res = detailedResp.result

                        // Perspektivsperre der Sitzung über den reinen Zustandsautomaten aktualisieren (neue Partie ab 26 Figuren kalibriert zwangsweise auf detectedPerspective, im Mittel- und Endspiel bleibt die Sperre stehen).
                        // Eine per langem Druck auf die Blase gesetzte Sperre gilt als Ansage des Nutzers und wird nicht automatisch überschrieben.
                        if (!manualPerspectiveLock) {
                            sessionLockedPerspective = UltraRobustClassifier.resolvePerspectiveLock(
                                currentLock = sessionLockedPerspective,
                                detectedPerspective = detailedResp.detectedPerspective,
                                occupiedCount = detailedResp.occupiedCount,
                                medianSim = detailedResp.medianSim,
                                perspectiveConfidence = detailedResp.perspectiveConfidence
                            )

                            // Selbstheilung bei Fehlsperre (Lektion aus bug_19): früher war die Sperre unwiderruflich, eine Fehlsperre lieferte danach in jedem Frame ein gespiegeltes FEN.
                            // Widerspricht die Erkennung in 2 aufeinanderfolgenden verlässlichen Frames der Sperre, wird neu gesperrt (in einer normalen Partie wechselt die Perspektive nicht mitten im Spiel).
                            // Zusätzlich muss die Perspektive selbst belastbar sein, sonst hebt ein einzelner Zufallstreffer eine korrekte Sperre auf.
                            if (sessionLockedPerspective != null && sessionLockedPerspective != detailedResp.detectedPerspective &&
                                detailedResp.medianSim >= 0.70f && detailedResp.occupiedCount >= 16 &&
                                detailedResp.perspectiveConfidence >= 0.35f
                            ) {
                                perspectiveConflictStreak++
                                if (perspectiveConflictStreak >= 2) {
                                    sessionLockedPerspective = detailedResp.detectedPerspective
                                    perspectiveConflictStreak = 0
                                }
                            } else {
                                perspectiveConflictStreak = 0
                            }
                        }

                        // Fehlerbild "es werden die Figuren des Gegners analysiert": das FEN entstand oben
                        // mit der Sperre, wie sie vor diesem Frame galt. Ändert der Zustandsautomat die Sperre
                        // gerade jetzt (Neukalibrierung, Aufhebung einer Fehlsperre, manuelle Umschaltung), passte
                        // das eben berechnete FEN noch zur alten Perspektive - Pfeil und Bewertung galten dann der
                        // falschen Seite. Deshalb wird das FEN vor der Analyse mit der endgültigen Perspektive neu
                        // aufgebaut; rawBoard ist die Bildschirmansicht, das ist reines Rechnen ohne neue Bildanalyse.
                        val finalPerspective = sessionLockedPerspective ?: detailedResp.detectedPerspective
                        if (finalPerspective != res.isWhitePerspective) {
                            res = UltraRobustClassifier.buildFenFromBoard(
                                rawBoard = res.rawBoard,
                                isWhitePerspective = finalPerspective,
                                boardRect = res.boardRect,
                                medianSim = res.medianSim,
                                occupiedCount = res.occupiedCount
                            )
                        }

                        // Sobald ein gültiges FEN vorliegt, wandert es still in die Zwischenablage - so lässt sich ein falscher Rahmen später belegen
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("FEN", res.fullFen)
                        clipboard.setPrimaryClip(clip)

                        // Zellenweise Forensik speichern (für die Fälle bug_11~14): unsichere Felder und vom Gatter verworfene Kandidaten landen in cells_forensics.txt
                        // LocateScore/Confidence/Residual: Telemetrie des Locators für die Feinkalibrierung
                        val cellForensics = buildString {
                            appendLine("LocateScore: ${String.format("%.1f", locateResult.score)}")
                            appendLine("Confidence: ${locateResult.confidence}")
                            appendLine("Residual: ${String.format("%.2f", locateResult.residual)}")
                            appendLine("IsCropped: ${locateResult.isCropped}")
                            appendLine("LowConf: ${detailedResp.lowConfidenceCells.joinToString(" ")}")
                            appendLine("GateRejected: ${detailedResp.gateRejectedCells.joinToString(" ")}")
                        }
                        saveDebugArtifactsAsync(copyForDebug, boardRect, locateResult, res.fullFen, cellForensics)
                        
                        // Vorgegebene Bedenkzeit: go movetime 2000
                        val eval = StockfishBridge.evaluateFen(res.fullFen, moveTimeMs = StockfishBridge.DEFAULT_MOVE_TIME_MS)

                        // Sentinel der Erkennung (Befund aus bug_19/superbug): eine von der FEN-Vorprüfung abgefangene unmögliche Stellung ist weder ein Engine-Fehler noch ein Fall für den Fallback,
                        // deshalb wird kein Pfeil gezeichnet, sondern die rote Fehlertafel mit dem fehlerhaften FEN angezeigt
                        if (eval.bestMove == "(invalid)") {
                            withContext(Dispatchers.Main) {
                                transparentOverlay?.showError("Unmögliche Stellung erkannt (Könige nebeneinander oder Figuren fehlerhaft)", boardRect, res.fullFen)
                            }
                            lastFen = res.fullFen
                            return@launch
                        }

                        // Figurentyp des Startfeldes voranstellen, ergibt eine Anzeige wie R-d4d5 (UCI -> SAN)
                        var displayMoveStr = eval.bestMove
                        if (eval.bestMove.length >= 4 && eval.bestMove[0] in 'a'..'h') {
                            val fileIdx = eval.bestMove[0] - 'a'
                            val rankIdx = 8 - (eval.bestMove[1] - '0') // in standardBoard entspricht r=0 der Reihe 8
                            val pieceChar = res.standardBoard[rankIdx][fileIdx]
                            
                            // Regel: Bauern ohne Präfix, alle anderen Figuren mit ihrem Großbuchstaben
                            if (!pieceChar.equals('p', ignoreCase = true) && pieceChar != '.') {
                                displayMoveStr = "${pieceChar.uppercaseChar()}-${eval.bestMove}"
                            }
                        }

                        // Warnung vor flackernder Erkennung: unverändertes Brett bei geändertem FEN belegt ein Flackern in Klassifikation oder Perspektive und damit wechselnde Empfehlungen
                        val fenFlickerWarn = if (lastFen != null && lastFen != res.fullFen) " | Erkennung flackert" else ""
                        lastFen = res.fullFen

                        // Fallback deutlich anzeigen (Lektion aus bug_15): erscheint im Overlay der Fallback-Hinweis, steht die erste Diagnosezeile sofort daneben, alle Details liegen in engine_fallback_log.txt
                        // Ein von der Engine bestätigtes Partieende (Matt/Patt) hat zwar depth=0, ist aber kein Fallback und darf nicht so gemeldet werden (Lektion aus bug_19)
                        val isTrueFallback = eval.depth <= 0 &&
                            eval.bestMove != "(checkmate)" && eval.bestMove != "(stalemate)"
                        val engineWarn = if (isTrueFallback) {
                            " | [Engine-Fallback]"
                        } else ""
                        
                        // Kennzeichnung unsicherer Felder (Lektion aus bug_11): ein Verdacht auf Fehlklassifikation ist sofort sichtbar, alle Details stehen in cells_forensics.txt
                        val lowConfWarn = if (detailedResp.lowConfidenceCells.isNotEmpty()) {
                            " | unsichere Felder: ${detailedResp.lowConfidenceCells.take(3).joinToString(",")}"
                        } else ""

                        // Übergabe an das Overlay inklusive displayMoveStr und fenString
                        transparentOverlay?.showSuggestion(
                            boardRect = res.boardRect,
                            moveInfo = eval,
                            isWhitePerspective = res.isWhitePerspective,
                            displayMoveStr = displayMoveStr,
                            fenString = res.fullFen,
                            medianSim = detailedResp.medianSim,
                            occupiedCount = detailedResp.occupiedCount,
                            detectedPerspective = detailedResp.detectedPerspective
                        )

                        val conflictDesc = if (sessionLockedPerspective != null && detailedResp.detectedPerspective != res.isWhitePerspective) {
                            ", erkannt: ${if (detailedResp.detectedPerspective) "Weiß" else "Schwarz"}"
                        } else ""
                        val lockedStateDesc = when {
                            manualPerspectiveLock -> "(von Hand$conflictDesc)"
                            sessionLockedPerspective != null -> "(gesperrt$conflictDesc)"
                            else -> ""
                        }
                        val perspectiveName = if (res.isWhitePerspective) "Weiß$lockedStateDesc" else "Schwarz$lockedStateDesc"

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@FloatingBubbleService,
                                "[FEN kopiert] Perspektive: $perspectiveName (${detailedResp.perspectiveReason}, ${String.format("%.0f", detailedResp.perspectiveConfidence * 100)}%) | Sim: ${String.format("%.3f", detailedResp.medianSim)}$engineWarn$lowConfWarn$fenFlickerWarn",
                                if (isTrueFallback) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    is UltraRobustClassifier.ClassificationResponse.Rejected -> {
                        val rejectedForensics = buildString {
                            appendLine("LocateScore: ${String.format("%.1f", locateResult.score)}")
                            appendLine("Confidence: ${locateResult.confidence}")
                            appendLine("Residual: ${String.format("%.2f", locateResult.residual)}")
                            appendLine("IsCropped: ${locateResult.isCropped}")
                            appendLine("LowConf: ${detailedResp.lowConfidenceCells.joinToString(" ")}")
                            appendLine("GateRejected: ${detailedResp.gateRejectedCells.joinToString(" ")}")
                        }
                        saveDebugArtifactsAsync(copyForDebug, boardRect, locateResult, "REJECTED_${detailedResp.reason}", rejectedForensics)
                        
                        // Rote Fehlertafel mit dem Grund anzeigen, damit ein falscher Rahmen sichtbar wird
                        withContext(Dispatchers.Main) {
                            transparentOverlay?.showError(detailedResp.reason, boardRect)
                            Toast.makeText(
                                this@FloatingBubbleService,
                                "[Vom Gatter abgewiesen] Grund: ${detailedResp.reason} (Sim=${String.format("%.3f", detailedResp.medianSim)}, belegt=${detailedResp.occupiedCount}, Locator-Score=${String.format("%.0f", locateResult.score)})",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    null -> {
                        withContext(Dispatchers.Main) {
                            transparentOverlay?.showError("Der Klassifikator ist nicht initialisiert")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                bubbleView?.visibility = View.VISIBLE
                screenBitmap?.recycle()
                isAnalyzing = false
            }
        }
    }

    /**
     * Holt den aktuellsten Frame als Bitmap aus dem dauerhaft geöffneten ImageReader
     * Sauberes Leeren: das letzte erreichbare gültige Image bleibt erhalten, sonst wird auf einen neuen Frame gewartet - so entsteht bei stehendem Bild kein Nullwert
     */
    private suspend fun captureCurrentScreenBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null
        isCapturingFrame = true
        try {
            var latestImage: android.media.Image? = null
            while (true) {
                val next = reader.acquireLatestImage() ?: break
                latestImage?.close()
                latestImage = next
            }

            if (latestImage == null) {
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 350L) {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        latestImage = image
                        break
                    }
                    delay(15)
                }
            }

            val image = latestImage ?: return@withContext null
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                return@withContext if (pixelStride == 4 && rowPadding == 0) {
                    val b = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
                    b.copyPixelsFromBuffer(buffer)
                    b
                } else {
                    val paddedW = screenWidth + rowPadding / pixelStride
                    val temp = Bitmap.createBitmap(paddedW, screenHeight, Bitmap.Config.ARGB_8888)
                    temp.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(temp, 0, 0, screenWidth, screenHeight)
                    temp.recycle()
                    cropped
                }
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            isCapturingFrame = false
        }
    }

    private fun saveDebugArtifactsAsync(
        bitmap: Bitmap?,
        rect: android.graphics.Rect,
        locateResult: ChessLocator.LocateResult,
        fen: String,
        cellForensics: String = ""
    ) {
        val b = bitmap ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val debugDir = File(filesDir, "debug")
                if (!debugDir.exists()) debugDir.mkdirs()

                val imgFile = File(debugDir, "last_capture.png")
                val fos = FileOutputStream(imgFile)
                b.compress(Bitmap.CompressFormat.PNG, 90, fos)
                fos.flush()
                fos.close()

                val txtFile = File(debugDir, "last_diagnostic.txt")
                val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                txtFile.writeText(
                    "Quelle: [Overlay auf dem Gerät]\n" +
                    "BoardRect: $rect\n" +
                    "LocateScore: ${String.format("%.1f", locateResult.score)}\n" +
                    "Confidence: ${locateResult.confidence}\n" +
                    "Residual: ${String.format("%.2f", locateResult.residual)}\n" +
                    "IsCropped: ${locateResult.isCropped}\n" +
                    "FEN: $fen\n" +
                    "${cellForensics}Zeitpunkt: $timeStr\n"
                )
            } catch (_: Exception) {
            } finally {
                b.recycle()
            }
        }
    }

    private fun cleanupProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        bubbleView?.let { windowManager.removeView(it) }
        transparentOverlay?.hide()
        cleanupProjection()
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        captureHandler = null
        StockfishBridge.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
