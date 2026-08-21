package com.chess.copilot.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.chess.copilot.R
import com.chess.copilot.core.ChessLocator
import com.chess.copilot.core.UltraRobustClassifier
import com.chess.copilot.engine.StockfishBridge
import com.chess.copilot.ui.DuloToggleView
import com.chess.copilot.ui.MainActivity
import com.chess.copilot.ui.TransparentCanvasOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        private const val TAG = "FloatingBubbleService"

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

        // Kantenlänge der Blase; die Menükachel ist genauso breit
        const val BUBBLE_SIZE_DP = 64

        // Ab dieser Druckdauer gilt eine Berührung der Blase als langer Druck (Perspektive umschalten)
        private const val LONG_PRESS_MS = 500L

        // Takt der Dauerbeobachtung: so oft wird das Brett auf Veränderung abgeklopft
        private const val POLL_INTERVAL_MS = 700L

        // Ab dieser mittleren Helligkeitsdifferenz je Kachel gilt das Brett als verändert.
        // Darunter liegen Kompressionsrauschen und leichte Animationen der Oberfläche.
        private const val BOARD_CHANGE_THRESHOLD = 1.8f

        // Bewegt sich das Bild so lange ohne Ruhepause, wird trotzdem analysiert.
        // Duolingo animiert nach einem Zug gern weiter (Hervorhebungen, Maskottchen).
        private const val MAX_PENDING_TICKS = 4

        // Sicherheitsnetz: spätestens nach so vielen Takten wird ohnehin nachgesehen,
        // auch wenn der Bildvergleich nichts gemeldet hat. Die Engine läuft dabei nur,
        // wenn der Gegner tatsächlich gezogen hat - der Durchlauf kostet also wenig.
        private const val SWEEP_TICKS = 8

        // Kantenlänge des Rasters, auf das der Brettausschnitt zum Vergleich eingedampft wird
        private const val FINGERPRINT_GRID = 12
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
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

    // Kleines Menü an der Blase: Schalter für die Dauerbeobachtung und Beenden-Knopf
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var analyseToggle: DuloToggleView? = null

    // Dauerbeobachtung: läuft der Schalter, wird das Brett im Takt POLL_INTERVAL_MS abgeklopft
    @Volatile
    private var autoAnalyseEnabled = false
    private var monitorJob: Job? = null

    // Zuletzt erfolgreich lokalisiertes Brett; darauf bezieht sich der Vergleich der Frames
    private var lastBoardRect: Rect? = null
    // Felder der gegnerischen Figuren aus der letzten Analyse. Steht dort später eine gegnerische
    // Figur auf einem neuen Feld, hat der Gegner gezogen und man ist selbst wieder am Zug.
    private var lastOpponentSquares: Set<String>? = null
    /** Helligkeit und Streuung der 64 Felder eines Frames */
    private class BoardCells(val means: FloatArray, val stds: FloatArray)

    /**
     * Stand der Felder zum Zeitpunkt der letzten Analyse. Diese Vergleichsbasis wird ausschließlich
     * nach einer gelaufenen Analyse erneuert - niemals zwischendurch. Genau daran scheiterte eine
     * frühere Fassung: sie zog die Basis bei jedem veränderten Bild nach, wodurch ein Zug des
     * Gegners, der kurz nach dem eigenen Zug kam, in die Basis wanderte und nie erkannt wurde.
     */
    private var referenceCells: BoardCells? = null
    // Felder des vorherigen Taktes, nur um zu erkennen, ob die Figuren gerade still stehen
    private var lastTickCells: BoardCells? = null
    /**
     * Zuletzt erfolgreich aufgenommene Felder.
     *
     * MediaProjection liefert nur dann einen neuen Frame, wenn sich auf dem Bildschirm etwas bewegt.
     * Bei einem stehenden Brett kommt also gar nichts an. Kein neuer Frame bedeutet aber gerade, dass
     * sich nichts geändert hat - die zuletzt gelesenen Felder gelten dann weiter.
     */
    private var lastKnownCells: BoardCells? = null
    // Felder, auf denen der eingezeichnete Pfeil liegt; dort ist ein Helligkeitsvergleich wertlos
    private var arrowCells: Set<Int> = emptySet()
    // Wie viele Takte am Stück weichen die Felder schon von der Vergleichsbasis ab
    private var changePendingTicks = 0
    // Takte seit der letzten Analyse (für das Sicherheitsnetz)
    private var ticksSinceAnalysis = 0

    /**
     * Zuletzt gezeichneter Pfeil. Für jede Aufnahme wird das Overlay kurz ausgeblendet; ergibt die
     * Analyse danach, dass der Gegner noch am Zug ist, wird derselbe Pfeil wieder eingeblendet,
     * statt den Nutzer ohne Hinweis zurückzulassen.
     */
    private class ArrowSnapshot(
        val boardRect: Rect,
        val eval: StockfishBridge.EngineEvaluation,
        val isWhitePerspective: Boolean
    )

    private var lastArrow: ArrowSnapshot? = null

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
            Toast.makeText(this, "DuLo beendet", Toast.LENGTH_SHORT).show()
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
                "DuLo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Overlay-Dienst am Bildschirmrand"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DuLo läuft")
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
            Toast.makeText(this, "DuLo ist bereit", Toast.LENGTH_SHORT).show()
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
        val size = dp(BUBBLE_SIZE_DP)
        val radius = dp(18).toFloat()
        bubbleView = ImageView(this).apply {
            setImageResource(R.drawable.dulo_blase)
            scaleType = ImageView.ScaleType.CENTER_CROP
            elevation = 25f
            // Abgerundete Ecken: das Bild wird an einer abgerundeten Kontur beschnitten
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - size - dp(12)
            y = screenHeight / 3
        }
        bubbleParams = params

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
                        // Das offene Menü hängt an der Blase und wandert mit
                        positionMenu()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            if (System.currentTimeMillis() - touchDownTime >= LONG_PRESS_MS) {
                                // Langer Druck: eigene Farbe von Hand umschalten
                                togglePerspectiveManually()
                            } else {
                                // Kurzer Druck: das Menü öffnen bzw. schließen
                                toggleMenu()
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

    /** dp in Pixel umrechnen */
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    // ================= Menü an der Blase =================

    /** Kurzer Druck auf die Blase: Menü öffnen bzw. wieder schließen */
    private fun toggleMenu() {
        if (menuView == null) showMenu() else hideMenu()
    }

    /**
     * Baut das kleine Menü neben der Blase auf: ein Schalter für die Dauerbeobachtung
     * und ein Knopf, der DuLo samt Bildschirmaufnahme vollständig beendet.
     */
    private fun showMenu() {
        if (menuView != null) return

        val gap = dp(6)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // Schalter im Stil der Systemkacheln: Pille mit weißem Knopf, darunter "Off" bzw. "On"
        val toggle = DuloToggleView(this).apply {
            setOn(autoAnalyseEnabled, animate = false)
            onSwitched = { on -> setAutoAnalyse(on) }
        }
        analyseToggle = toggle

        // Beenden in derselben Kachelform, nur in Rot
        val destroyButton = TextView(this).apply {
            text = "Beenden"
            setTextColor(Color.rgb(255, 138, 138))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(40, 22, 24))
                setStroke(dp(1), Color.rgb(150, 55, 55))
            }
            setOnClickListener { destroyAssistant() }
        }

        container.addView(toggle)
        container.addView(
            destroyButton,
            LinearLayout.LayoutParams(dp(BUBBLE_SIZE_DP), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = gap
            }
        )

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // FLAG_NOT_FOCUSABLE: das Menü nimmt Berührungen an, zieht aber keinen Eingabefokus
        // von der darunterliegenden App ab
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        menuParams = params
        menuView = container
        windowManager.addView(container, params)
        positionMenu()
    }

    /** Menü an der Blase ausrichten: bevorzugt links daneben, sonst rechts */
    private fun positionMenu() {
        val params = menuParams ?: return
        val bubble = bubbleParams ?: return
        val view = menuView ?: return
        val menuWidth = if (view.width > 0) view.width else dp(BUBBLE_SIZE_DP)
        val left = bubble.x - menuWidth - dp(12)
        params.x = if (left >= dp(8)) left else bubble.x + dp(76)
        params.y = bubble.y.coerceIn(dp(8), (screenHeight - dp(200)).coerceAtLeast(dp(8)))
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun hideMenu() {
        val view = menuView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        menuView = null
        analyseToggle = null
    }

    // ================= Dauerbeobachtung =================

    /**
     * Schalter im Menü.
     * An: sofort einmal die Engine fragen und danach das Brett im Takt beobachten - jedes Mal,
     * wenn eine eigene Figur ihr Feld gewechselt hat, wird erneut gerechnet.
     * Aus: Beobachtung stoppen und den Pfeil ausblenden.
     */
    private fun setAutoAnalyse(enabled: Boolean) {
        if (autoAnalyseEnabled == enabled) return
        autoAnalyseEnabled = enabled

        if (!enabled) {
            stopMonitoring()
            arrowCells = emptySet()
            transparentOverlay?.hide()
            Toast.makeText(this, "Analyse aus", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isProjectionAlive()) {
            autoAnalyseEnabled = false
            analyseToggle?.setOn(false, animate = true)
            requestReAuthorization()
            return
        }

        // Beim Einschalten immer rechnen, egal wie die Stellung zur letzten Analyse steht
        lastOpponentSquares = null
        resetMonitorState()
        Toast.makeText(this, "Analyse an", Toast.LENGTH_SHORT).show()
        startAnalysis(force = true)
        startMonitoring()
    }

    /**
     * Beobachtungsschleife: dampft den Brettausschnitt jedes Taktes auf ein grobes Raster ein und
     * vergleicht ihn mit dem letzten ruhigen Bild. Erst wenn sich das Brett verändert hat und
     * danach wieder still steht (der Zug also fertig animiert ist), läuft die volle Analyse an.
     * Das spart Rechenzeit und verhindert, dass mitten in einer Zuganimation erkannt wird.
     */
    private fun startMonitoring() {
        monitorJob?.cancel()
        resetMonitorState()
        monitorJob = serviceScope.launch {
            while (isActive && autoAnalyseEnabled) {
                delay(POLL_INTERVAL_MS)
                if (isAnalyzing || !autoAnalyseEnabled) continue
                if (!isProjectionAlive()) continue

                ticksSinceAnalysis++

                val rect = lastBoardRect
                if (rect == null) {
                    // Noch kein Brett bekannt (die erste Analyse lief ins Leere): erneut versuchen
                    startAnalysis(force = true)
                    continue
                }

                // Kommt kein neuer Frame, hat sich nichts bewegt: die zuletzt gelesenen Felder gelten weiter
                val cells = captureBoardCells(rect) ?: lastKnownCells
                if (cells == null) {
                    if (ticksSinceAnalysis >= SWEEP_TICKS) startAnalysis(force = false)
                    continue
                }
                lastKnownCells = cells

                val reference = referenceCells
                if (reference == null) {
                    referenceCells = cells
                    lastTickCells = cells
                    continue
                }

                // Steht auf einem Feld etwas anderes als bei der letzten Analyse?
                val changedSinceAnalysis = UltraRobustClassifier.boardCellsChanged(
                    reference.means, reference.stds, cells.means, cells.stds, arrowCells
                )
                // Und stehen die Figuren gerade still, ist die Zuganimation also durch?
                val standsStill = lastTickCells?.let {
                    !UltraRobustClassifier.boardCellsChanged(it.means, it.stds, cells.means, cells.stds, arrowCells)
                } ?: false
                lastTickCells = cells

                changePendingTicks = if (changedSinceAnalysis) changePendingTicks + 1 else 0

                val analyseNow = (changedSinceAnalysis && standsStill) ||
                    changePendingTicks >= MAX_PENDING_TICKS ||
                    ticksSinceAnalysis >= SWEEP_TICKS
                if (analyseNow) {
                    Log.i(
                        TAG,
                        "Analyse ausgelöst (verändert=$changedSinceAnalysis, steht still=$standsStill," +
                            " wartende Takte=$changePendingTicks, Takte seit Analyse=$ticksSinceAnalysis)"
                    )
                    startAnalysis(force = false)
                }
            }
        }
    }

    /** Vergleichsbasis und Zähler zurücksetzen; der nächste Takt nimmt das Brett neu auf */
    private fun resetMonitorState() {
        referenceCells = null
        lastTickCells = null
        lastKnownCells = null
        changePendingTicks = 0
        ticksSinceAnalysis = 0
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        resetMonitorState()
    }

    /**
     * Liest für jedes der 64 Felder mittlere Helligkeit und Streuung aus dem aktuellen Frame.
     *
     * Abgetastet wird nur der mittlere Bereich eines Feldes, damit die Gitterlinien nicht mitzählen.
     * Die Streuung verrät, ob eine Figur auf dem Feld steht, die Helligkeit unterscheidet helle von
     * dunklen Figuren - zusammen ergibt das ein Abbild der Figurenstellung, ohne die volle Erkennung
     * zu starten.
     *
     * Bewusst ohne Ausblenden von Blase und Pfeil: die Felder unter dem Pfeil werden beim Vergleich
     * ohnehin übersprungen, und so bleibt die Anzeige ruhig.
     */
    private suspend fun captureBoardCells(rect: Rect): BoardCells? {
        val bitmap = captureCurrentScreenBitmap() ?: return null
        return try {
            withContext(Dispatchers.Default) {
                val safe = Rect(rect)
                if (!safe.intersect(0, 0, bitmap.width, bitmap.height) || safe.width() < 32 || safe.height() < 32) {
                    return@withContext null
                }
                val step = safe.width() / 8.0f
                val stepY = safe.height() / 8.0f
                val means = FloatArray(64)
                val stds = FloatArray(64)
                val samples = 6

                for (r in 0..7) {
                    for (c in 0..7) {
                        var sum = 0.0f
                        var sumSq = 0.0f
                        var count = 0
                        for (sy in 0 until samples) {
                            // Nur die mittleren 60 Prozent des Feldes abtasten
                            val y = (safe.top + (r + 0.2f + 0.6f * (sy + 0.5f) / samples) * stepY).toInt()
                            if (y < 0 || y >= bitmap.height) continue
                            for (sx in 0 until samples) {
                                val x = (safe.left + (c + 0.2f + 0.6f * (sx + 0.5f) / samples) * step).toInt()
                                if (x < 0 || x >= bitmap.width) continue
                                val pixel = bitmap.getPixel(x, y)
                                val red = (pixel shr 16) and 0xFF
                                val green = (pixel shr 8) and 0xFF
                                val blue = pixel and 0xFF
                                val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
                                sum += luminance
                                sumSq += luminance * luminance
                                count++
                            }
                        }
                        val index = r * 8 + c
                        if (count > 0) {
                            val mean = sum / count
                            means[index] = mean
                            stds[index] = kotlin.math.sqrt(kotlin.math.max(0f, sumSq / count - mean * mean))
                        }
                    }
                }
                BoardCells(means, stds)
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureBoardCells fehlgeschlagen: ${e.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Den zuletzt berechneten Pfeil unverändert wieder einblenden */
    private fun restoreLastArrow() {
        val snapshot = lastArrow ?: return
        transparentOverlay?.showSuggestion(
            boardRect = snapshot.boardRect,
            moveInfo = snapshot.eval,
            isWhitePerspective = snapshot.isWhitePerspective,
            autoDismiss = !autoAnalyseEnabled
        )
    }

    /**
     * Rechnet einen UCI-Zug in die Bildschirmfelder um, über die der gezeichnete Pfeil läuft.
     * Leere Menge, wenn es kein normaler Zug ist (Matt, Patt, abgewiesene Stellung).
     */
    private fun arrowCellsFor(uci: String, isWhitePerspective: Boolean): Set<Int> {
        if (uci.length < 4 || uci[0] !in 'a'..'h' || uci[2] !in 'a'..'h') return emptySet()
        val fromRank = uci[1] - '0'
        val toRank = uci[3] - '0'
        if (fromRank !in 1..8 || toRank !in 1..8) return emptySet()
        val fromCol = uci[0] - 'a'
        val toCol = uci[2] - 'a'

        val fromRow = if (isWhitePerspective) 8 - fromRank else fromRank - 1
        val fromScreenCol = if (isWhitePerspective) fromCol else 7 - fromCol
        val toRow = if (isWhitePerspective) 8 - toRank else toRank - 1
        val toScreenCol = if (isWhitePerspective) toCol else 7 - toCol
        return UltraRobustClassifier.cellsCoveredByArrow(fromRow, fromScreenCol, toRow, toScreenCol)
    }

    /** Beendet DuLo vollständig: Beobachtung, Menü, Overlay, Bildschirmaufnahme und Dienst */
    private fun destroyAssistant() {
        autoAnalyseEnabled = false
        stopMonitoring()
        hideMenu()
        transparentOverlay?.hide()
        recordProjectionState("Von Hand über den Beenden-Knopf im Menü gestoppt")
        Toast.makeText(this, "DuLo beendet, Bildschirmaufnahme gestoppt", Toast.LENGTH_SHORT).show()
        cleanupProjection()
        stopSelf()
    }

    private fun isProjectionAlive(): Boolean =
        mediaProjection != null && imageReader != null && virtualDisplay != null

    /** Aufnahmeberechtigung ist weg: den Nutzer zurück in die App schicken */
    private fun requestReAuthorization() {
        Toast.makeText(this, "Die Aufnahmeberechtigung ist abgelaufen, bitte DuLo neu starten", Toast.LENGTH_LONG).show()
        val reAuthIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(reAuthIntent)
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

    /**
     * Eine vollständige Analyse: sauberes Bild aufnehmen, Brett lokalisieren, Figuren erkennen,
     * Engine fragen und den Pfeil zeichnen.
     *
     * @param force true beim Einschalten des Schalters - dann wird gerechnet, ohne vorher zu prüfen,
     *        ob sich eine eigene Figur bewegt hat. false in der Dauerbeobachtung: dort bricht die
     *        Analyse vor dem Engine-Aufruf ab, wenn die eigenen Figuren unverändert stehen.
     */
    private fun startAnalysis(force: Boolean) {
        if (isAnalyzing) return

        if (!isProjectionAlive()) {
            autoAnalyseEnabled = false
            analyseToggle?.setOn(false, animate = true)
            stopMonitoring()
            requestReAuthorization()
            return
        }

        isAnalyzing = true

        serviceScope.launch {
            var screenBitmap: Bitmap? = null
            try {
                transparentOverlay?.hide()
                bubbleView?.visibility = View.INVISIBLE
                menuView?.visibility = View.INVISIBLE
                // 150 ms statt 60 ms (Verdacht aus bug_13/14): zwischen dem Entfernen der Fenster und dem Eintreffen eines sauberen Frames im Puffer liegt eine Kompositionsverzögerung.
                // Wartet man zu kurz, enthält die Aufnahme noch den alten Frame mit Rahmen und Pfeil; verdeckt der die 8. Linie, sieht es nach einer übersehenen Figur aus
                delay(150)

                screenBitmap = captureCurrentScreenBitmap()

                bubbleView?.visibility = View.VISIBLE
                menuView?.visibility = View.VISIBLE

                if (screenBitmap == null) {
                    Log.i(TAG, "Noch kein Frame verfügbar, dieser Durchgang wird übersprungen")
                    return@launch
                }

                var locateResult = withContext(Dispatchers.Default) {
                    ChessLocator.locateBoard(screenBitmap)
                }
                var boardRect = locateResult.rect

                // Umgang mit zugeschnittenen Frames (negative Ränder bzw. Überlauf): nur melden, nicht erzwingen - ein Rect außerhalb des Bildes würde beim Zuschneiden eine Ausnahme werfen,
                // und ein unvollständiges Bild ist ohnehin nicht zuverlässig erkennbar; stattdessen erscheinen die Fehlertafel und der rote Rahmen
                if (locateResult.isCropped) {
                    withContext(Dispatchers.Main) {
                        transparentOverlay?.showError("Brett unvollständig im Bild")
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

                        // Brett merken, damit die Beobachtungsschleife weiß, welchen Ausschnitt sie vergleichen muss
                        lastBoardRect = Rect(res.boardRect)

                        // Kern der Dauerbeobachtung: gerechnet wird, sobald man wieder am Zug ist.
                        // Das ist genau dann der Fall, wenn seit der letzten Analyse eine gegnerische
                        // Figur auf einem Feld steht, das vorher nicht ihr gehörte - also nachdem der
                        // Gegner gezogen hat. Der eigene Zug allein löst nichts aus, denn danach ist
                        // der Gegner am Zug und eine Empfehlung wäre verfrüht.
                        val opponentSquares = UltraRobustClassifier.opponentSquares(res.standardBoard, res.isWhitePerspective)
                        val previousOpponentSquares = lastOpponentSquares
                        val myTurn = force || previousOpponentSquares == null ||
                            UltraRobustClassifier.opponentMovedSince(previousOpponentSquares, opponentSquares)
                        Log.i(
                            TAG,
                            "Am Zug? $myTurn (force=$force, gegnerische Felder vorher=${previousOpponentSquares?.size ?: -1}," +
                                " jetzt=${opponentSquares.size}, neu=${opponentSquares.count { previousOpponentSquares?.contains(it) != true }})"
                        )
                        if (!myTurn) {
                            // Der Gegner ist noch dran: Engine sparen und den bisherigen Pfeil wieder einblenden
                            restoreLastArrow()
                            return@launch
                        }
                        lastOpponentSquares = opponentSquares

                        // Vorgegebene Bedenkzeit: go movetime 2000
                        val eval = StockfishBridge.evaluateFen(res.fullFen, moveTimeMs = StockfishBridge.DEFAULT_MOVE_TIME_MS)

                        // Sentinel der Erkennung (Befund aus bug_19/superbug): eine von der FEN-Vorprüfung abgefangene unmögliche Stellung ist weder ein Engine-Fehler noch ein Fall für den Fallback,
                        // deshalb wird kein Pfeil gezeichnet, sondern die rote Fehlertafel mit dem fehlerhaften FEN angezeigt
                        if (eval.bestMove == "(invalid)") {
                            withContext(Dispatchers.Main) {
                                transparentOverlay?.showError("Unmögliche Stellung erkannt (Könige nebeneinander oder Figuren fehlerhaft)")
                            }
                            lastFen = res.fullFen
                            return@launch
                        }

                        transparentOverlay?.showSuggestion(
                            boardRect = res.boardRect,
                            moveInfo = eval,
                            isWhitePerspective = res.isWhitePerspective,
                            // In der Dauerbeobachtung bleibt der Pfeil stehen, bis der nächste Zug erkannt wird
                            autoDismiss = !autoAnalyseEnabled
                        )
                        lastArrow = ArrowSnapshot(
                            boardRect = Rect(res.boardRect),
                            eval = eval,
                            isWhitePerspective = res.isWhitePerspective
                        )
                        // Felder unter dem Pfeil merken: dort verfälscht die Zeichnung jeden Vergleich
                        arrowCells = arrowCellsFor(eval.bestMove, res.isWhitePerspective)
                        lastFen = res.fullFen

                        // Befunde nur ins Protokoll, der Bildschirm bleibt ruhig
                        val isTrueFallback = eval.depth <= 0 &&
                            eval.bestMove != "(checkmate)" && eval.bestMove != "(stalemate)"
                        Log.i(
                            TAG,
                            "Zug=${eval.bestMove} Tiefe=${eval.depth} Perspektive=${if (res.isWhitePerspective) "Weiß" else "Schwarz"}" +
                                " (${detailedResp.perspectiveReason}, ${String.format("%.2f", detailedResp.perspectiveConfidence)})" +
                                " Sim=${String.format("%.3f", detailedResp.medianSim)}" +
                                (if (isTrueFallback) " [Engine-Fallback]" else "")
                        )
                    }
                    is UltraRobustClassifier.ClassificationResponse.Rejected -> {
                        withContext(Dispatchers.Main) {
                            transparentOverlay?.showError(
                                "Vom Gatter abgewiesen: ${detailedResp.reason} (Sim=${String.format("%.3f", detailedResp.medianSim)}, belegt=${detailedResp.occupiedCount})"
                            )
                        }
                    }
                    null -> {
                        withContext(Dispatchers.Main) {
                            transparentOverlay?.showError("Der Klassifikator ist nicht initialisiert")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Analyse abgebrochen: ${e.javaClass.simpleName}: ${e.message}")
                withContext(Dispatchers.Main) {
                    transparentOverlay?.showError("Ausnahme in der Analyse: ${e.javaClass.simpleName}")
                }
            } finally {
                bubbleView?.visibility = View.VISIBLE
                menuView?.visibility = View.VISIBLE
                screenBitmap?.recycle()
                // Die Vergleichsbasis wird erst jetzt fallengelassen: der nächste Takt nimmt das
                // Brett samt frisch gezeichnetem Pfeil neu auf. So kann zwischen zwei Analysen
                // kein Zug in der Basis verschwinden.
                resetMonitorState()
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
        autoAnalyseEnabled = false
        stopMonitoring()
        hideMenu()
        serviceScope.cancel()
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        transparentOverlay?.hide()
        cleanupProjection()
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        captureHandler = null
        StockfishBridge.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
