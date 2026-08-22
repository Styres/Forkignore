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

        // Takt der Dauerbeobachtung: fünfmal pro Sekunde wird jede Figurenposition nachgesehen
        private const val POLL_INTERVAL_MS = 200L

        // So viele Takte am Stück müssen die Figuren stillstehen, bevor die volle Erkennung startet.
        // Zwei Takte sind 0,4 Sekunden - lang genug, damit eine Zuganimation durch ist.
        private const val STILL_TICKS_REQUIRED = 2

        // Ab dieser mittleren Helligkeitsdifferenz je Kachel gilt das Brett als verändert.
        // Darunter liegen Kompressionsrauschen und leichte Animationen der Oberfläche.
        private const val BOARD_CHANGE_THRESHOLD = 1.8f

        // Bewegt sich das Bild so lange ohne Ruhepause, wird trotzdem analysiert (rund 3 Sekunden).
        // Duolingo animiert nach einem Zug gern weiter (Hervorhebungen, Maskottchen).
        private const val MAX_PENDING_TICKS = 15

        // Sicherheitsnetz: spätestens nach so vielen Takten (rund 2,5 Sekunden) wird ohnehin
        // nachgesehen, auch wenn der Feldvergleich nichts gemeldet hat. Damit bleibt ein Pfeil
        // selbst im schlechtesten Fall nicht länger stehen. Die Engine läuft dabei nur, wenn der
        // Gegner tatsächlich gezogen hat, und das Overlay wird nicht mehr abgerissen - der
        // Durchlauf kostet also kaum etwas.
        private const val SWEEP_TICKS = 12

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
    // Felder der eigenen Figuren aus der letzten Analyse: daran wird der eigene Zug erkannt
    private var lastOwnSquares: Set<String>? = null
    // Beim Einschalten wird die eigene Farbe aus der Ausgangsstellung bestimmt und für die Sitzung festgehalten
    private var sideEstablished = false
    /**
     * Brett aus der letzten vollständigen Erkennung, so wie es auf dem Bildschirm steht.
     * Damit lässt sich zu einem erkannten Zug sofort sagen, wessen Figur gezogen ist - ohne dafür
     * das ganze Brett erneut zu erkennen.
     */
    private var trackedScreenBoard: Array<CharArray>? = null
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
    // Wie viele Takte am Stück weichen die Felder schon von der Vergleichsbasis ab
    private var changePendingTicks = 0
    // Wie viele Takte am Stück stehen die Figuren schon still
    private var stillTicks = 0
    // Takte seit der letzten Analyse (für das Sicherheitsnetz)
    private var ticksSinceAnalysis = 0

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

        // Beim Einschalten immer rechnen und die eigene Farbe aus der Ausgangsstellung neu bestimmen
        lastOpponentSquares = null
        lastOwnSquares = null
        sideEstablished = false
        trackedScreenBoard = null
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
                    reference.means, reference.stds, cells.means, cells.stds
                )
                // Und stehen die Figuren gerade still, ist die Zuganimation also durch?
                val sameAsLastTick = lastTickCells?.let {
                    !UltraRobustClassifier.boardCellsChanged(it.means, it.stds, cells.means, cells.stds)
                } ?: false
                lastTickCells = cells

                stillTicks = if (sameAsLastTick) stillTicks + 1 else 0
                changePendingTicks = if (changedSinceAnalysis) changePendingTicks + 1 else 0

                val standsStill = stillTicks >= STILL_TICKS_REQUIRED
                val analyseNow = (changedSinceAnalysis && standsStill) ||
                    changePendingTicks >= MAX_PENDING_TICKS ||
                    ticksSinceAnalysis >= SWEEP_TICKS
                if (!analyseNow) continue

                // Neue Erkennungsmethode: erst nachsehen, ob sich der Zug direkt ablesen lässt.
                // Ein gewöhnlicher Zug verändert genau zwei Felder - Startfeld leer, Zielfeld besetzt.
                // Steht auf dem Startfeld eine eigene Figur, war es der eigene Zug: dann ist die
                // Empfehlung erledigt, der Pfeil kommt weg und die Engine bleibt außen vor.
                // Erst der Zug des Gegners löst die vollständige Erkennung samt Berechnung aus.
                val detected = UltraRobustClassifier.detectMove(
                    reference.means, reference.stds, cells.means, cells.stds
                )
                val board = trackedScreenBoard
                val mover = if (detected != null && board != null) {
                    board[detected.fromCell / 8][detected.fromCell % 8]
                } else {
                    '.'
                }

                if (detected != null && mover != '.' && sessionLockedPerspective != null) {
                    val movedByMe = mover.isUpperCase() == sessionLockedPerspective
                    Log.i(
                        TAG,
                        "Zug abgelesen: Feld ${detected.fromCell} -> ${detected.toCell}, Figur '$mover'," +
                            " eigener Zug=$movedByMe"
                    )
                    if (movedByMe) {
                        // Eigener Zug: Brett fortschreiben, Pfeil ausblenden, keine Berechnung
                        trackedScreenBoard = UltraRobustClassifier.applyMoveToScreenBoard(board!!, detected)
                        transparentOverlay?.hide()
                        // Ohne Pfeil sieht das Brett anders aus: die Vergleichsbasis wird im
                        // nächsten Takt frisch genommen, sonst gälte allein das Ausblenden als Zug.
                        resetMonitorState()
                        continue
                    }
                }

                Log.i(
                    TAG,
                    "Analyse ausgelöst (verändert=$changedSinceAnalysis, ruhige Takte=$stillTicks," +
                        " wartende Takte=$changePendingTicks, Takte seit Analyse=$ticksSinceAnalysis," +
                        " Zug ablesbar=${detected != null})"
                )
                startAnalysis(force = false)
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
     * Nimmt den ersten Frame auf, der nach dem Ausblenden von Pfeil und Blase entsteht.
     *
     * Alle bereits gepufferten Frames stammen noch aus der Zeit davor und werden verworfen. Das
     * Ausblenden selbst löst eine neue Bildkomposition aus, der nächste Frame ist also sauber.
     */
    private suspend fun captureFrameAfterHiding(): Bitmap? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null
        isCapturingFrame = true
        try {
            // Alles verwerfen, was vor dem Ausblenden entstanden ist
            while (true) {
                val stale = reader.acquireLatestImage() ?: break
                stale.close()
            }
            val deadline = System.currentTimeMillis() + 400L
            while (System.currentTimeMillis() < deadline) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        return@withContext bitmapFromImage(image)
                    } finally {
                        image.close()
                    }
                }
                delay(10)
            }
            Log.i(TAG, "Nach dem Ausblenden kam kein neuer Frame")
            null
        } catch (e: Exception) {
            Log.w(TAG, "captureFrameAfterHiding fehlgeschlagen: ${e.message}")
            null
        } finally {
            isCapturingFrame = false
        }
    }

    /**
     * Liest für jedes der 64 Felder mittlere Helligkeit und Streuung direkt aus dem Frame-Puffer.
     *
     * Bewusst ohne den Umweg über ein Vollbild-Bitmap: bei fünf Aufnahmen pro Sekunde wären das
     * zweistellige Megabyte an Zwischenspeicher je Sekunde. Gelesen werden nur die Abtastpunkte
     * selbst, gut zweitausend Bildpunkte je Takt.
     *
     * Abgetastet wird nur der mittlere Bereich eines Feldes, damit die Gitterlinien nicht mitzählen.
     * Die Streuung verrät, ob eine Figur auf dem Feld steht, die Helligkeit unterscheidet helle von
     * dunklen Figuren - zusammen ergibt das ein Abbild der Figurenstellung.
     *
     * Blase und Pfeil werden dafür nicht ausgeblendet: die Felder unter dem Pfeil überspringt der
     * Vergleich ohnehin, und so bleibt die Anzeige ruhig.
     *
     * @return null, wenn gerade kein neuer Frame vorliegt - dann hat sich auf dem Bildschirm nichts bewegt
     */
    private suspend fun captureBoardCells(rect: Rect): BoardCells? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null
        isCapturingFrame = true
        var image: android.media.Image? = null
        try {
            // Nur den neuesten Frame nehmen und ältere verwerfen
            while (true) {
                val next = reader.acquireLatestImage() ?: break
                image?.close()
                image = next
            }
            val frame = image ?: return@withContext null

            val plane = frame.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val left = rect.left.coerceAtLeast(0)
            val top = rect.top.coerceAtLeast(0)
            val right = rect.right.coerceAtMost(screenWidth)
            val bottom = rect.bottom.coerceAtMost(screenHeight)
            if (right - left < 32 || bottom - top < 32) return@withContext null

            val stepX = (right - left) / 8.0f
            val stepY = (bottom - top) / 8.0f
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
                        val y = (top + (r + 0.2f + 0.6f * (sy + 0.5f) / samples) * stepY).toInt()
                        if (y < 0 || y >= screenHeight) continue
                        for (sx in 0 until samples) {
                            val x = (left + (c + 0.2f + 0.6f * (sx + 0.5f) / samples) * stepX).toInt()
                            if (x < 0 || x >= screenWidth) continue
                            val offset = y * rowStride + x * pixelStride
                            if (offset < 0 || offset + 2 >= buffer.capacity()) continue
                            val red = buffer.get(offset).toInt() and 0xFF
                            val green = buffer.get(offset + 1).toInt() and 0xFF
                            val blue = buffer.get(offset + 2).toInt() and 0xFF
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
        } catch (e: Exception) {
            Log.w(TAG, "captureBoardCells fehlgeschlagen: ${e.message}")
            null
        } finally {
            try { image?.close() } catch (_: Exception) {}
            isCapturingFrame = false
        }
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
                // Pfeil und Blase kurz unsichtbar schalten - das Fenster bleibt bestehen, sonst
                // blitzt der Bildschirm bei jedem Anlegen sichtbar auf (Ursache des Flackerns)
                transparentOverlay?.setContentVisible(false)
                bubbleView?.visibility = View.INVISIBLE
                menuView?.visibility = View.INVISIBLE

                // Statt fest zu warten: alle noch gepufferten Frames verwerfen und den ersten Frame
                // nehmen, der nach dem Ausblenden entsteht. Der ist garantiert sauber und kommt in
                // der Regel schon nach wenigen Millisekunden - die Anzeige ist also kürzer weg.
                screenBitmap = captureFrameAfterHiding()

                transparentOverlay?.setContentVisible(true)
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
                        if (!manualPerspectiveLock && !sideEstablished) {
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
                        // Eigene Farbe beim Einschalten aus der Ausgangsstellung festlegen: was unten auf den
                        // beiden Reihen steht, sind die eigenen Figuren, oben stehen die des Gegners. Ob die
                        // eigenen hell oder dunkel sind, hat die Helligkeitsclusterung entschieden.
                        // Danach bleibt die Farbe für die ganze Sitzung stehen und kann nur per langem
                        // Druck auf die Blase von Hand geändert werden.
                        if (!sideEstablished && !manualPerspectiveLock) {
                            val sideFromRows = UltraRobustClassifier.sideFromStartingRows(res.rawBoard)
                            if (sideFromRows != null) {
                                sessionLockedPerspective = sideFromRows
                                sideEstablished = true
                                Log.i(TAG, "Eigene Farbe aus der Ausgangsstellung: ${if (sideFromRows) "Weiß" else "Schwarz"}")
                            }
                        }

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
                        trackedScreenBoard = res.rawBoard

                        // Kern der Dauerbeobachtung: gerechnet wird erst, wenn man wieder am Zug ist.
                        //
                        // Ablauf einer Partie: DuLo zeigt den besten Zug, man führt ihn aus - dabei ändern sich
                        // nur die eigenen Felder, und genau dieser Zwischenstand wird übersprungen. Erst wenn
                        // danach eine gegnerische Figur auf einem Feld auftaucht, das vorher nicht ihr gehörte,
                        // ist der Gegner fertig und die nächste Empfehlung wird berechnet.
                        val ownSquares = UltraRobustClassifier.sideSquares(res.standardBoard, res.isWhitePerspective)
                        val opponentSquares = UltraRobustClassifier.sideSquares(res.standardBoard, !res.isWhitePerspective)
                        val previousOpponentSquares = lastOpponentSquares
                        val previousOwnSquares = lastOwnSquares

                        val opponentMoved = previousOpponentSquares == null ||
                            UltraRobustClassifier.opponentMovedSince(previousOpponentSquares, opponentSquares)
                        val ownMoved = previousOwnSquares != null && previousOwnSquares != ownSquares
                        val myTurn = force || opponentMoved

                        Log.i(
                            TAG,
                            "Am Zug? $myTurn (force=$force, Gegner zog=$opponentMoved, ich zog=$ownMoved," +
                                " gegnerische Felder vorher=${previousOpponentSquares?.size ?: -1}, jetzt=${opponentSquares.size})"
                        )

                        if (!myTurn) {
                            if (ownMoved) {
                                // Der eigene Zug ist ausgeführt: die Empfehlung ist erledigt. Pfeil weg und
                                // warten, bis der Gegner gezogen hat.
                                lastOwnSquares = ownSquares
                                withContext(Dispatchers.Main) { transparentOverlay?.hide() }
                            }
                            // Sonst hat sich nichts Entscheidendes getan: der bisherige Pfeil wird
                            // am Ende dieses Durchlaufs ohnehin wieder sichtbar geschaltet.
                            return@launch
                        }
                        lastOpponentSquares = opponentSquares
                        lastOwnSquares = ownSquares

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
                transparentOverlay?.setContentVisible(true)
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

    /** Wandelt einen Frame in ein Bitmap um (geräteunabhängig über pixelStride und rowStride) */
    private fun bitmapFromImage(image: android.media.Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        return if (pixelStride == 4 && rowPadding == 0) {
            val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } else {
            val paddedWidth = screenWidth + rowPadding / pixelStride
            val temp = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            temp.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(temp, 0, 0, screenWidth, screenHeight)
            temp.recycle()
            cropped
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
