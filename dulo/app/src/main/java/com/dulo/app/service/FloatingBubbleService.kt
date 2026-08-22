package com.dulo.app.service

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
import com.dulo.app.R
import com.dulo.app.core.ChessLocator
import com.dulo.app.core.UltraRobustClassifier
import com.dulo.app.engine.StockfishBridge
import com.dulo.app.ui.DuloToggleView
import com.dulo.app.ui.MainActivity
import com.dulo.app.ui.TransparentCanvasOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Dienst für die Blase am Bildschirmrand (Floating Bubble)
 * Vordergrunddienst nach den Vorgaben von Android 14
 * Kernmechanik:
 * 1. Keine Selbstverschmutzung durch das Overlay: Blase, Menü und Zeichenebene tragen FLAG_SECURE und
 *    erscheinen deshalb gar nicht erst in der Bildschirmaufnahme - sie bleiben durchgehend sichtbar.
 *    Hält sich ein Gerät nicht daran, wird das einmalig gemessen und auf kurzes Ausblenden zurückgefallen
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
        const val ACTION_STOP = "com.dulo.app.action.STOP_BUBBLE"

        // Laufzustand für den Umschalter: der Dienst läuft im selben Prozess wie die Oberfläche,
        // ein Flag genügt hier und kommt ohne Binder oder Broadcast aus.
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "dulo_service"

        // Kantenlänge der Blase; die Menükachel ist genauso breit
        const val BUBBLE_SIZE_DP = 64

        // Ab dieser Druckdauer gilt eine Berührung der Blase als langer Druck (Perspektive umschalten)
        private const val LONG_PRESS_MS = 500L

        // Takt der Dauerbeobachtung: rund achtmal pro Sekunde wird jede Figurenposition nachgesehen
        private const val POLL_INTERVAL_MS = 120L

        // So viele Takte am Stück müssen die Figuren stillstehen, bevor die volle Erkennung startet.
        // Ein Takt reicht: sobald sich zwischen zwei Aufnahmen nichts mehr rührt, ist die
        // Zuganimation durch. Jeder zusätzliche Takt wäre spürbare Verzögerung bis zum Pfeil.
        private const val STILL_TICKS_REQUIRED = 1


        // Bewegt sich das Bild so lange ohne Ruhepause, wird trotzdem analysiert (rund 2 Sekunden).
        // Duolingo animiert nach einem Zug gern weiter (Hervorhebungen, Maskottchen).
        private const val MAX_PENDING_TICKS = 16

        // So oft darf ein Durchgang ergebnislos bleiben, bevor die Stellung zwangsweise als neue
        // Grundlage angenommen und gerechnet wird. Ohne diese Notbremse konnte die Erkennung in
        // einen Zustand geraten, aus dem sie nie wieder herausfand - nach ein paar Zügen kam dann
        // gar keine Anzeige mehr.
        private const val MAX_UNDECIDED_RUNS = 3

        // Pause nach jeder Berührung des Auto-Zugs
        private const val AUTO_MOVE_DELAY_MS = 300L

        // Beruhigungszeit nach der letzten Berührung: so lange läuft noch die Zuganimation
        private const val AUTO_MOVE_SETTLE_MS = 700L

        // So lange wird auf die Ausführung eines vorgeschlagenen Zuges gewartet, danach gilt er
        // als nicht gespielt und die Stellung wird neu erkannt.
        private const val PENDING_MOVE_TIMEOUT_MS = 8000L

        // Geschieht so lange gar nichts mehr, wird die Buchführung verworfen und frisch erkannt
        private const val STALL_TIMEOUT_MS = 12000L

        // So lange wird höchstens auf einen frischen Frame gewartet
        private const val FRAME_WAIT_MS = 400L

        // Abstand zwischen zwei ergebnislosen vollständigen Erkennungen (wachsend, gedeckelt)
        private const val FULL_SCAN_BACKOFF_MS = 500L
        private const val FULL_SCAN_BACKOFF_MAX_MS = 3000L

        // Sicherheitsnetz: spätestens nach so vielen Takten (rund 2,5 Sekunden) wird ohnehin
        // nachgesehen, auch wenn der Feldvergleich nichts gemeldet hat. Damit bleibt ein Pfeil
        // selbst im schlechtesten Fall nicht länger stehen. Die Engine läuft dabei nur, wenn der
        // Gegner tatsächlich gezogen hat, und das Overlay wird nicht mehr abgerissen - der
        // Durchlauf kostet also kaum etwas.
        private const val SWEEP_TICKS = 12


        // Obergrenze für einen kompletten Analysedurchgang (Aufnahme, Erkennung, Bedenkzeit).
        // Reichlich bemessen: die Bedenkzeit sind 2 Sekunden, alles Übrige liegt im Millisekundenbereich.
        private const val ANALYSIS_TIMEOUT_MS = 20_000L
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
    /**
     * Zuletzt vom System geliefertes Bild, bereitgehalten für den nächsten Zugriff.
     *
     * MediaProjection liefert nur bei Veränderungen auf dem Bildschirm ein neues Bild. Wurden
     * ankommende Bilder - wie zuvor - sofort verworfen, stand bei einem stillstehenden Brett
     * überhaupt kein Bild zur Verfügung: Die vollständige Erkennung lief dann jedes Mal ins Leere,
     * gerade dann, wenn sie zum Aufräumen einer verfahrenen Lage gebraucht wurde.
     *
     * Der ImageReader hält drei Puffer; genau einen davon dauerhaft bereitzuhalten ist unbedenklich.
     */
    private var latestImage: android.media.Image? = null
    private val imageLock = Any()
    @Volatile
    private var sessionLockedPerspective: Boolean? = null
    // Wurde die Perspektive per langem Druck auf die Blase von Hand gesetzt, bleibt sie stehen:
    // die automatische Erkennung darf eine ausdrückliche Ansage des Nutzers nicht überschreiben.
    @Volatile
    private var manualPerspectiveLock = false
    // Zähler für Konflikte mit der Perspektivsperre (Lektion aus bug_19): widerspricht die Erkennung der Sperre dauerhaft, wird neu gesperrt, damit eine Fehlsperre nicht die ganze Sitzung blockiert
    private var perspectiveConflictStreak = 0

    // Kleines Menü an der Blase: Schalter für die Dauerbeobachtung und Beenden-Knopf
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var autoMoveToggle: DuloToggleView? = null

    /**
     * Auto-Zug: DuLo tippt den empfohlenen Zug selbst auf das Brett.
     *
     * Standardmäßig aus. Eingeschaltet wird nach jeder neuen Empfehlung zuerst das Startfeld und
     * dann das Zielfeld angetippt, mit einer kurzen Pause dazwischen.
     */
    @Volatile
    private var autoMoveEnabled = false

    /**
     * Läuft gerade eine Tippfolge des Auto-Zugs?
     *
     * Während der beiden Berührungen ist das Brett in einem Zwischenzustand: Die angetippte Figur
     * wird hervorgehoben, und Duolingo blendet Punkte auf den möglichen Zielfeldern ein. Diese
     * Punkte sehen für die Feldabtastung aus wie Figuren. Wird in diesem Moment ein Zug abgelesen,
     * landet ein erfundener Zug in der gemerkten Stellung - und ab da stimmt nichts mehr.
     *
     * Deshalb ruht die Beobachtung, solange getippt wird, plus einer kurzen Beruhigungszeit für
     * die Zuganimation danach.
     */
    @Volatile
    private var autoMoveBusyUntil = 0L

    /**
     * Nehmen Blase und Menü gerade Berührungen an?
     *
     * Für die Dauer einer Tippfolge werden sie durchlässig geschaltet. Bleibt die Rückmeldung des
     * Bedienungshilfen-Dienstes aus - etwa weil er zwischendurch abgeschaltet wurde -, wäre die
     * Blase sonst dauerhaft nicht mehr zu bedienen, also auch nicht mehr zu beenden. Deshalb wird
     * der Zustand mitgeführt und im Takt notfalls von Hand zurückgesetzt.
     */
    private var overlayTouchable = true

    // Dauerbeobachtung: läuft der Schalter, wird das Brett im Takt POLL_INTERVAL_MS abgeklopft
    @Volatile
    private var autoAnalyseEnabled = false
    private var monitorJob: Job? = null

    // Zuletzt erfolgreich lokalisiertes Brett; darauf bezieht sich der Vergleich der Frames
    private var lastBoardRect: Rect? = null
    /**
     * Brett der letzten angenommenen Erkennung (Standardausrichtung, Weiß unten).
     *
     * Daran wird der nächste Zug abgelesen: die Figur, die auf einem Feld neu auftaucht, benennt
     * den Ziehenden. Erneuert wird dieses Brett ausschließlich, wenn ein Zug eindeutig zugeordnet
     * werden konnte - eine unklare Aufnahme darf die Basis nicht überschreiben, sonst verschwindet
     * der Zug des Gegners darin und die Anzeige bleibt stehen.
     */
    private var lastAcceptedBoard: Array<CharArray>? = null

    /**
     * Mitgeführte Rochaderechte zur gemerkten Stellung.
     *
     * Aus der Figurenstellung allein sind sie nicht ablesbar: Ein König, der nach f1 und zurück
     * gegangen ist, steht wieder zu Hause, darf aber nie wieder rochieren. Solange die Stellung
     * fortgeschrieben wird, ist die Zugfolge bekannt und die Rechte lassen sich mitführen.
     */
    private var castlingRights: String? = null


    /**
     * Bildschirmfelder des gerade gezeigten Zuges (Start und Ziel), sonst -1.
     *
     * So lange hier ein Zug steht, wartet DuLo darauf, dass genau dieser Zug ausgeführt wird:
     * Startfeld wird leer, Zielfeld wird besetzt. Der Pfeil bleibt bis dahin stehen - dieselbe
     * Logik wie in der Zuganzeige auf lichess.
     */
    private var pendingFromCell = -1
    private var pendingToCell = -1
    /** Der gezeigte Zug in UCI-Schreibweise; damit wird das gemerkte Brett fortgeschrieben */
    private var pendingMoveUci: String? = null
    /** Feldabtastung zu dem Zeitpunkt, an dem der Pfeil gezeichnet wurde */
    private var pendingMoveReference: BoardCells? = null
    /**
     * Wann der ausstehende Zug eingetragen wurde.
     *
     * Wird er nicht ausgeführt - etwa weil die Oberfläche ihn ablehnt, weil man doch nicht am Zug
     * war, oder weil eine Berührung danebenging - bliebe die Beobachtung sonst für immer darauf
     * warten. Nach der Frist wird er verworfen und neu erkannt.
     */
    private var pendingSince = 0L

    /**
     * Wann zuletzt etwas vorangegangen ist: ein abgelesener Zug, eine Berechnung, ein
     * ausgeführter Zug.
     *
     * Aufsichtsuhr für alles, was hier sonst noch schiefgehen kann. Die Beobachtung hängt an
     * mehreren Bedingungen (Veränderung erkannt, Figuren stehen still, Zug eindeutig), und jede
     * davon kann in einem unglücklichen Zustand dauerhaft falsch bleiben. Statt jede einzelne
     * Sackgasse zu erraten, wird schlicht gemessen, ob überhaupt noch etwas geschieht - und wenn
     * nicht, die Stellung frisch vom Bildschirm gelesen.
     */
    private var lastProgressAt = 0L

    /**
     * Gesetzt, sobald die Engine die Partie als entschieden meldet (Matt oder Patt).
     *
     * Danach gibt es nichts mehr zu tippen. Ohne diese Sperre liefe die Aufsichtsuhr alle zwölf
     * Sekunden an und rechnete dieselbe beendete Stellung erneut durch. Aufgehoben wird sie,
     * sobald sich auf dem Brett wieder etwas rührt - dann läuft die nächste Partie.
     */
    private var awaitingBoardChange = false
    // Beim Einschalten wird die eigene Farbe aus der Ausgangsstellung bestimmt und für die Sitzung festgehalten
    private var sideEstablished = false
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
    /**
     * Wie viele Durchgänge hintereinander zu keinem Ergebnis kamen.
     *
     * Wiederholt sich das, taugt die Aufnahme nicht: dann wird der Abstand zwischen den Versuchen
     * gestreckt, statt fünfmal pro Sekunde dieselbe unklare Stellung durchzurechnen.
     */
    private var undecidedRuns = 0

    /**
     * Frühester Zeitpunkt für die nächste vollständige Erkennung.
     *
     * Sie kostet ein Vollbild und den Musterabgleich über 64 Felder. Kommt sie zu keinem Ergebnis,
     * bleibt die Vergleichsbasis absichtlich stehen - dadurch gilt das Brett weiterhin als
     * verändert und der nächste Takt liefe sofort wieder an. Ohne Bremse wären das mehrere
     * Durchgänge je Sekunde, ohne dass ein weiterer Versuch etwas brächte.
     */
    private var nextFullScanAt = 0L
    /** Beginn des laufenden Analysedurchgangs; Grundlage für den Wachhund gegen hängende Durchgänge */
    @Volatile
    private var analysisStartedAt = 0L


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
                // Immer nur das neueste Bild behalten; ältere schließt acquireLatestImage selbst.
                val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                if (img != null) {
                    synchronized(imageLock) {
                        try { latestImage?.close() } catch (_: Exception) {}
                        latestImage = img
                    }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Nicht mit aufnehmen: die Blase darf das erkannte Bild nicht verfälschen
                WindowManager.LayoutParams.FLAG_SECURE,
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

        // Auto-Zug: DuLo rechnet fortlaufend und tippt den Zug selbst - ohne Pfeil.
        // Braucht die Freigabe in den Bedienungshilfen, sonst kann keine App Berührungen
        // an eine fremde App schicken.
        val autoMove = DuloToggleView(this).apply {
            title = "Auto"
            setOn(autoMoveEnabled, animate = false)
            onSwitched = { on -> setAutoMove(on) }
        }
        autoMoveToggle = autoMove

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

        container.addView(autoMove)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // Nicht mit aufnehmen: das Menü darf das erkannte Bild nicht verfälschen
                WindowManager.LayoutParams.FLAG_SECURE,
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
        autoMoveToggle = null
    }

    /**
     * Schalter "Auto" im Menü: DuLo führt den empfohlenen Zug selbst aus.
     *
     * Getippt werden kann nur über einen Bedienungshilfen-Dienst - das ist unter Android der
     * einzige vorgesehene Weg, eine Berührung an eine andere App zu schicken. Fehlt die Freigabe,
     * springt der Schalter zurück und die Einstellungen werden geöffnet.
     */
    private fun setAutoMove(enabled: Boolean) {
        if (!enabled) {
            autoMoveEnabled = false
            // Eine noch laufende Tippfolge darf die Blase nicht unbedienbar zurücklassen
            setOverlayTouchable(true)
            // Mit dem Auto-Zug endet auch die Dauerbeobachtung: sie läuft nur für ihn
            setAutoAnalyse(false)
            Toast.makeText(this, "Auto-Zug aus", Toast.LENGTH_SHORT).show()
            return
        }

        if (!DuloAutoMoveService.isAvailable) {
            autoMoveEnabled = false
            autoMoveToggle?.setOn(false, animate = true)
            Toast.makeText(
                this,
                "Für den Auto-Zug muss DuLo unter Einstellungen > Bedienungshilfen freigegeben werden",
                Toast.LENGTH_LONG
            ).show()
            openAccessibilitySettings()
            return
        }

        if (!isProjectionAlive()) {
            autoMoveEnabled = false
            autoMoveToggle?.setOn(false, animate = true)
            requestReAuthorization()
            return
        }

        autoMoveEnabled = true
        Toast.makeText(this, "Auto-Zug an", Toast.LENGTH_SHORT).show()
        // Der Auto-Zug bringt die Dauerbeobachtung selbst mit: erst die Stellung aufnehmen,
        // dann bei jedem Zug des Gegners rechnen und tippen.
        setAutoAnalyse(true)
    }

    /** Systemeinstellungen für die Bedienungshilfen öffnen */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Einstellungen für Bedienungshilfen ließen sich nicht öffnen: ${e.message}")
        }
    }

    /**
     * Hält die Blase vom Brett fern.
     *
     * Zwei Gründe, beide ernst: Liegt die Blase über dem Brett, verdeckt sie Felder in der
     * Aufnahme - deren Inhalt wäre dauerhaft falsch gelesen. Und sie fängt die Berührungen des
     * Auto-Zugs ab, weil sie an dieser Stelle das oberste Fenster ist.
     *
     * Nach jeder Vermessung des Bretts wird deshalb geprüft und bei Überlappung an den nächsten
     * freien Rand über oder unter dem Brett geschoben. Das ist verlässlicher, als zu messen, ob
     * sich das Gerät an FLAG_SECURE hält.
     */
    private fun keepBubbleClearOfBoard(boardRect: Rect) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val size = dp(BUBBLE_SIZE_DP)
        val margin = dp(8)

        val overlaps = params.x < boardRect.right && params.x + size > boardRect.left &&
            params.y < boardRect.bottom && params.y + size > boardRect.top
        if (!overlaps) return

        // Oberhalb oder unterhalb des Bretts ausweichen, je nachdem, wo Platz ist
        val above = boardRect.top - size - margin
        val below = boardRect.bottom + margin
        val target = when {
            above >= 0 -> above
            below + size <= screenHeight -> below
            else -> margin
        }

        Log.i(TAG, "Blase lag über dem Brett und wird nach y=$target verschoben")
        params.y = target
        try {
            windowManager.updateViewLayout(view, params)
            positionMenu()
        } catch (e: Exception) {
            Log.w(TAG, "Blase ließ sich nicht verschieben: ${e.message}")
        }
    }

    /**
     * Blase und Menü für die Dauer des Tippens berührungsdurchlässig schalten.
     *
     * Der Bedienungshilfen-Dienst schickt die Berührung an den Bildschirmpunkt - empfangen wird sie
     * vom obersten Fenster an dieser Stelle. Liegt die Blase gerade über dem Brett, fängt sie den
     * eigenen Zug ab und das Spiel darunter bekommt nichts davon mit. Die Blase lässt sich frei
     * verschieben, also ist das keine Randerscheinung, sondern passiert früher oder später.
     */
    private fun setOverlayTouchable(touchable: Boolean) {
        overlayTouchable = touchable
        fun apply(view: View?, params: WindowManager.LayoutParams?) {
            if (view == null || params == null) return
            val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val updated = if (touchable) params.flags and flag.inv() else params.flags or flag
            if (updated == params.flags) return
            params.flags = updated
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                Log.w(TAG, "Berührungsverhalten ließ sich nicht umstellen: ${e.message}")
            }
        }
        apply(bubbleView, bubbleParams)
        apply(menuView, menuParams)
    }

    /**
     * Tippt den empfohlenen Zug auf dem Brett: erst das Startfeld, dann das Zielfeld.
     *
     * Die Feldmitten ergeben sich aus dem vermessenen Brettrechteck und den beiden
     * Bildschirmfeldern des Pfeils - genau denselben, die auch für das Wegnehmen des Pfeils
     * beobachtet werden. Zwischen den Berührungen liegt eine kurze Pause, sonst wertet die App
     * darunter die zweite womöglich als Doppeltippen.
     */
    private fun performAutoMove(boardRect: Rect, fromCell: Int, toCell: Int) {
        if (!autoMoveEnabled) return
        if (!DuloAutoMoveService.isAvailable) {
            Log.w(TAG, "Auto-Zug angefordert, aber der Bedienungshilfen-Dienst ist nicht freigegeben")
            return
        }
        if (fromCell !in 0..63 || toCell !in 0..63) return

        val stepX = boardRect.width() / 8.0f
        val stepY = boardRect.height() / 8.0f
        if (stepX < 4f || stepY < 4f) return

        fun centreOf(cell: Int): Pair<Float, Float> {
            val row = cell / 8
            val col = cell % 8
            return (boardRect.left + (col + 0.5f) * stepX) to (boardRect.top + (row + 0.5f) * stepY)
        }

        val points = listOf(centreOf(fromCell), centreOf(toCell))
        Log.i(TAG, "Auto-Zug tippt auf ${points.map { "(${it.first.toInt()}, ${it.second.toInt()})" }}")

        // Blase und Menü aus dem Weg: sonst fangen sie die eigene Berührung ab, wenn sie gerade
        // über dem Brett liegen.
        setOverlayTouchable(false)

        // Die Beobachtung ruht, bis beide Berührungen durch sind und die Zuganimation abgelaufen
        // ist. Reichlich bemessen, denn ein zu früh abgelesener Zwischenzustand kostet die ganze
        // Partie: er wandert als erfundener Zug in die gemerkte Stellung.
        // Vorläufige Sperre bis zur Rückmeldung: eine Pause zwischen den Berührungen plus
        // Beruhigungszeit. Die Rückmeldung setzt sie danach genauer.
        autoMoveBusyUntil = System.currentTimeMillis() +
            (points.size - 1) * AUTO_MOVE_DELAY_MS + AUTO_MOVE_SETTLE_MS

        DuloAutoMoveService.tapSequence(points, AUTO_MOVE_DELAY_MS) { ok ->
            setOverlayTouchable(true)
            if (!ok) {
                Log.w(TAG, "Auto-Zug konnte nicht vollständig ausgeführt werden")
                // Nicht weiter blockieren: die gewöhnliche Erkennung soll sofort wieder greifen
                autoMoveBusyUntil = 0L
                return@tapSequence
            }
            // Ab der letzten Berührung noch die Beruhigungszeit abwarten
            autoMoveBusyUntil = System.currentTimeMillis() + AUTO_MOVE_SETTLE_MS
        }
    }

    // ================= Dauerbeobachtung =================

    /**
     * Dauerbeobachtung starten oder stoppen.
     *
     * Wird vom Auto-Zug getragen: Der Auto-Betrieb rechnet fortlaufend selbst mit, eine eigene
     * "Hilfe" muss dafür nicht mehr eingeschaltet werden.
     */
    private fun setAutoAnalyse(enabled: Boolean) {
        if (autoAnalyseEnabled == enabled) return
        autoAnalyseEnabled = enabled

        if (!enabled) {
            stopMonitoring()
            clearPendingMove()
            // Alles wegnehmen, auch eine Meldung, die noch in ihrer Haltezeit steht: nach dem
            // Ausschalten darf nichts mehr auf dem Bildschirm nachklingen.
            transparentOverlay?.dismissAll()
            transparentOverlay?.hide()
            return
        }

        if (!isProjectionAlive()) {
            autoAnalyseEnabled = false
            autoMoveEnabled = false
            autoMoveToggle?.setOn(false, animate = true)
            requestReAuthorization()
            return
        }

        // Beim Einschalten immer rechnen und die eigene Farbe aus der Ausgangsstellung neu bestimmen
        lastAcceptedBoard = null
        castlingRights = null
        sideEstablished = false
        clearPendingMove()
        resetMonitorState()
        markProgress()
        awaitingBoardChange = false
        startAnalysis(force = false)
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
                // Ein einzelner Fehlschlag darf die Schleife nicht beenden. Genau das ist zuvor
                // passiert: eine Ausnahme in einem Takt hat den ganzen Job stillschweigend
                // beendet, und die App wirkte danach eingefroren.
                try {
                    monitorTick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Takt der Beobachtung fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    /** Ein einzelner Takt der Dauerbeobachtung */
    private suspend fun monitorTick() {
        if (isAnalyzing) {
            // Wachhund: bleibt ein Durchgang hängen (Engine, Aufnahme, Systemdienst), wird die
            // Sperre nach der Obergrenze von Hand gelöst. Ohne das stand isAnalyzing für immer
            // und die Beobachtung reagierte auf nichts mehr.
            if (System.currentTimeMillis() - analysisStartedAt > ANALYSIS_TIMEOUT_MS + 10_000L) {
                Log.w(TAG, "Analyse hängt seit über ${(ANALYSIS_TIMEOUT_MS + 10_000L) / 1000} Sekunden, Sperre wird gelöst")
                isAnalyzing = false
            }
            return
        }
        if (!autoAnalyseEnabled) return
        if (!isProjectionAlive()) return

        // Sicherheitsnetz: Blieb die Rückmeldung der Tippfolge aus, wären Blase und Menü dauerhaft
        // nicht mehr zu bedienen. Ist die Sperre längst abgelaufen, werden sie zurückgeholt.
        if (!overlayTouchable && System.currentTimeMillis() > autoMoveBusyUntil + AUTO_MOVE_SETTLE_MS) {
            Log.w(TAG, "Tippfolge hat sich nicht zurückgemeldet, Blase wird wieder bedienbar gemacht")
            setOverlayTouchable(true)
        }

        // Solange der Auto-Zug tippt, wird nichts abgelesen: das Brett zeigt gerade Hervorhebungen
        // und Zielpunkte, keine Stellung.
        if (System.currentTimeMillis() < autoMoveBusyUntil) {
            // Die Vergleichsbasis wird dabei laufend nachgezogen, damit der eigene Zug hinterher
            // nicht als Veränderung des Gegners zählt.
            lastTickCells = null
            stillTicks = 0
            return
        }

        ticksSinceAnalysis++

        // Aufsichtsuhr: geschieht über längere Zeit gar nichts mehr, wird die Buchführung
        // verworfen und die Stellung frisch vom Bildschirm gelesen. Damit ist jede Sackgasse
        // höchstens ein Aussetzer von wenigen Sekunden - egal, wodurch sie entstanden ist.
        val now = System.currentTimeMillis()
        if (lastProgressAt == 0L) lastProgressAt = now
        if (now - lastProgressAt > STALL_TIMEOUT_MS && !awaitingBoardChange) {
            Log.w(TAG, "Seit ${(now - lastProgressAt) / 1000} Sekunden kein Fortschritt, Stellung wird neu erkannt")
            markProgress()
            clearPendingMove()
            lastAcceptedBoard = null
            castlingRights = null
            referenceCells = null
            lastTickCells = null
            undecidedRuns = 0
            nextFullScanAt = 0L
            startAnalysis(force = true)
            return
        }

        val rect = lastBoardRect
        if (rect == null) {
            // Noch kein Brett bekannt (die erste Analyse lief ins Leere): erneut versuchen
            startAnalysis(force = true)
            return
        }

        // Kommt kein neuer Frame, hat sich nichts bewegt: die zuletzt gelesenen Felder gelten weiter
        val cells = captureBoardCells(rect) ?: lastKnownCells
        if (cells == null) {
            if (ticksSinceAnalysis >= SWEEP_TICKS) startAnalysis(force = false)
            return
        }
        lastKnownCells = cells

        // Wird der ausstehende Zug nicht ausgeführt, darf die Beobachtung nicht ewig darauf
        // warten: verwerfen und die Stellung neu erkennen.
        if (pendingSince > 0L && System.currentTimeMillis() - pendingSince > PENDING_MOVE_TIMEOUT_MS) {
            // Hat sich das Brett seither überhaupt nicht gerührt, wurde der Zug nicht angenommen -
            // fast immer, weil man noch gar nicht am Zug war. Ein erneutes Tippen desselben Zuges
            // wäre sinnlos und wiederholte sich alle acht Sekunden. Stattdessen wird der Zug
            // fallengelassen und auf den Gegner gewartet; die gemerkte Stellung stimmt ja weiter.
            val unveraendert = pendingMoveReference?.let { vorher ->
                !UltraRobustClassifier.boardCellsChanged(
                    vorher.means, vorher.stds, cells.means, cells.stds
                )
            } ?: false

            clearPendingMove()
            if (unveraendert) {
                Log.i(TAG, "Getippter Zug kam nicht an, Brett unverändert - es wird auf den Gegner gewartet")
                referenceCells = cells
                lastTickCells = cells
                changePendingTicks = 0
                stillTicks = 0
                ticksSinceAnalysis = 0
                markProgress()
                return
            }

            Log.i(TAG, "Ausstehender Zug wurde nicht ausgeführt, Stellung wird neu erkannt")
            lastAcceptedBoard = null
            castlingRights = null
            startAnalysis(force = true)
            return
        }

        // Der Pfeil steht: warten, bis genau dieser Zug ausgeführt ist. Geprüft werden nur die
        // beiden betroffenen Felder - Startfeld leer, Zielfeld besetzt. Erst dann verschwindet
        // der Pfeil, und erst dann wird auf den Gegner gewartet.
        // Auf dem Brett liegt nichts von DuLo: gezeichnet wird nur die Störungsmeldung in der
        // Bildschirmmitte, und die trägt FLAG_SECURE. Die beiden Felder lassen sich also
        // unabhängig vom Verhalten des Geräts messen.
        val pendingReference = pendingMoveReference
        if (pendingReference != null && pendingFromCell >= 0 && pendingToCell >= 0) {
            val played = UltraRobustClassifier.moveWasPlayed(
                pendingReference.means, pendingReference.stds,
                cells.means, cells.stds,
                pendingFromCell, pendingToCell
            )
            if (played) {
                Log.i(TAG, "Empfohlener Zug wurde ausgeführt, Pfeil wird weggenommen")
                markProgress()
                // Das gemerkte Brett um den eigenen Zug fortschreiben. Ohne das sähe der nächste
                // Vergleich den eigenen und den gegnerischen Zug zusammen und könnte niemanden
                // mehr eindeutig benennen - die Anzeige bliebe stehen.
                val playedMove = pendingMoveUci
                val previous = lastAcceptedBoard
                if (playedMove != null && previous != null) {
                    UltraRobustClassifier.applyUciMove(previous, playedMove)?.let { updated ->
                        val piece = previous[8 - (playedMove[1] - '0')][playedMove[0] - 'a']
                        lastAcceptedBoard = updated
                        castlingRights = UltraRobustClassifier.updateCastlingRights(
                            castlingRights ?: UltraRobustClassifier.computeCastlingRights(previous),
                            playedMove,
                            piece
                        )
                    }
                }
                clearPendingMove()
                // Von hier an gilt der Bildschirm als neue Vergleichsbasis: alles Weitere ist
                // der Zug des Gegners.
                referenceCells = cells
                lastTickCells = cells
                changePendingTicks = 0
                stillTicks = 0
                ticksSinceAnalysis = 0
                return
            }
        }

        val reference = referenceCells
        if (reference == null) {
            referenceCells = cells
            lastTickCells = cells
            return
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

        // Ohne Veränderung auf dem Brett wird nicht analysiert - sonst liefe die Erkennung
        // im Leerlauf und könnte dieselbe Empfehlung erneut auslösen.
        //
        // Steht noch ein Pfeil, ist die Schwelle deutlich höher: der Pfeil soll stehenbleiben, bis
        // der Zug ausgeführt ist, und nicht bei jeder Hervorhebung oder Animation durch eine neue
        // Empfehlung ersetzt werden. Erst wenn sich das Brett über Sekunden anders darstellt, hat
        // der Nutzer offenbar etwas anderes gespielt - dann wird neu erkannt.
        val holdingArrow = pendingMoveReference != null
        // Nach einer entschiedenen Partie wird erst wieder gerechnet, wenn sich etwas rührt
        if (awaitingBoardChange) {
            if (!changedSinceAnalysis) return
            Log.i(TAG, "Brett hat sich wieder verändert, die Beobachtung läuft weiter")
            awaitingBoardChange = false
            markProgress()
        }

        val analyseNow = changedSinceAnalysis && if (holdingArrow) {
            standsStill && changePendingTicks >= MAX_PENDING_TICKS
        } else {
            standsStill || changePendingTicks >= MAX_PENDING_TICKS
        }
        if (!analyseNow) return

        // Steht noch ein Zug aus, entscheidet allein die Prüfung seiner beiden Felder weiter oben.
        // Ein zweiter Ableseversuch daneben würde die Hervorhebungen der Oberfläche als Zug
        // missdeuten. Bleibt der Zug lange aus, greift weiter unten die vollständige Erkennung.
        if (pendingMoveReference == null) {
            // Der billige Weg: lässt sich der gespielte Zug direkt aus den beiden Aufnahmen
            // ablesen, wird die bekannte Stellung einfach fortgeschrieben.
            if (tryIncrementalMove(reference, cells)) return
        }

        // Die vollständige Erkennung bekommt eine Bremse: Nach einem ergebnislosen Durchgang
        // bringt ein sofort folgender zweiter fast nie etwas Neues.
        if (System.currentTimeMillis() < nextFullScanAt) return

        // Sonst die vollständige Erkennung: Bildschirmfoto, Brett vermessen, 64 Felder einordnen.
        Log.i(
            TAG,
            "Vollständige Erkennung ausgelöst (ruhige Takte=$stillTicks, wartende Takte=$changePendingTicks," +
                " Takte seit Analyse=$ticksSinceAnalysis, ergebnislose Durchgänge=$undecidedRuns)"
        )
        startAnalysis(force = false)
    }

    /**
     * Merkt sich die beiden Felder des gerade gezeichneten Pfeils samt Vergleichsbild.
     *
     * Das Vergleichsbild wird frisch aufgenommen, damit der Vergleich später gegen genau den
     * Zustand läuft, der beim Zeichnen des Pfeils auf dem Brett stand.
     */
    private suspend fun registerPendingMove(bestMove: String, isWhitePerspective: Boolean, boardRect: Rect) {
        clearPendingMove()

        // Sonderantworten der Engine sind keine Züge: Partie vorbei oder Stellung unbrauchbar.
        // Ohne diesen Zweig liefe die Beobachtung ins Leere und wartete auf einen Zug, den es
        // gar nicht gibt.
        if (bestMove.length < 4 || bestMove.startsWith("(")) {
            Log.i(TAG, "Kein ausführbarer Zug ($bestMove), es wird nichts beobachtet")
            if (bestMove == "(checkmate)" || bestMove == "(stalemate)" || bestMove == "(none)") {
                // Die Partie ist entschieden. Bis sich auf dem Brett wieder etwas rührt, gibt es
                // nichts zu rechnen - sonst liefe alle zwölf Sekunden dieselbe Suche erneut.
                Log.i(TAG, "Partie ist entschieden, es wird auf eine neue Stellung gewartet")
                awaitingBoardChange = true
                markProgress()
            }
            return
        }
        val from = UltraRobustClassifier.screenCellForSquare(bestMove.substring(0, 2), isWhitePerspective)
        val to = UltraRobustClassifier.screenCellForSquare(bestMove.substring(2, 4), isWhitePerspective)
        if (from == null || to == null) {
            Log.w(TAG, "Zug $bestMove ließ sich keinem Feld zuordnen")
            return
        }

        val cells = captureBoardCells(boardRect) ?: lastKnownCells
        if (cells == null) {
            Log.w(TAG, "Keine Feldabtastung für $bestMove verfügbar, der Zug wird nicht beobachtet")
            return
        }
        pendingFromCell = from
        pendingToCell = to
        pendingMoveUci = bestMove
        pendingMoveReference = cells
        pendingSince = System.currentTimeMillis()
        Log.i(TAG, "Pfeil zeigt $bestMove, beobachtet werden die Felder $from und $to")

        // Auto-Zug: denselben Zug gleich selbst tippen. Der Pfeil verschwindet danach über den
        // gewöhnlichen Weg, sobald die Figur auf dem Zielfeld angekommen ist.
        performAutoMove(boardRect, from, to)
    }

    /**
     * Störungsmeldung anzeigen - aber nur, wenn sie den Nutzer noch etwas angeht.
     *
     * Wurde in der Zwischenzeit abgeschaltet, wird nichts mehr eingeblendet: eine Meldung, die
     * nach dem Ausschalten auf dem Bildschirm auftaucht, ist nur noch störend.
     */
    private suspend fun reportError(reason: String) {
        if (!autoAnalyseEnabled) {
            Log.i(TAG, "Störung nach dem Ausschalten unterdrückt: $reason")
            return
        }
        withContext(Dispatchers.Main) { transparentOverlay?.showError(reason) }
    }

    /**
     * Kern der Erkennung: den gespielten Zug ablesen und die bekannte Stellung fortschreiben.
     *
     * Der Gedanke dahinter: Ein Zug verändert genau zwei Felder. Welche das sind, verraten schon
     * die billigen Feldabtastungen - das Startfeld wird leer, das Zielfeld besetzt. Welche Figur
     * dort stand, steht bereits in der gemerkten Stellung. Damit ist der Zug vollständig bekannt,
     * ganz ohne Bildschirmfoto, Brettvermessung und Musterabgleich.
     *
     * Das ist nicht nur schneller, es beseitigt auch die eigentliche Fehlerquelle: Wird die
     * Stellung bei jedem Zug neu aus dem Bild abgeleitet, sammeln sich Fehleinordnungen an, bis
     * nichts mehr zusammenpasst. Fortgeschrieben wird die Stellung dagegen aus sich selbst heraus
     * und bleibt so lange richtig, wie die Züge stimmen.
     *
     * Alles, was nicht in dieses Muster passt (Rochade mit vier Feldern, en passant, unklare
     * Aufnahme), liefert false - dann übernimmt die vollständige Erkennung.
     *
     * @return true, wenn der Zug abgelesen und verarbeitet wurde
     */
    private suspend fun tryIncrementalMove(reference: BoardCells, cells: BoardCells): Boolean {
        val board = lastAcceptedBoard ?: return false
        val myColourIsWhite = sessionLockedPerspective ?: return false

        // Erst die Rochade: Sie räumt zwei Felder und passt deshalb nicht in das Muster eines
        // gewöhnlichen Zuges. Ohne diesen Zweig fiele sie in die vollständige Erkennung.
        val uci = UltraRobustClassifier.detectCastling(
            reference.stds, cells.stds, board, myColourIsWhite
        ) ?: run {
            val detected = UltraRobustClassifier.detectMove(
                reference.means, reference.stds, cells.means, cells.stds,
                // Die bekannte Stellung löst mehrdeutige Zielfelder auf: die weggenommene
                // Hervorhebung des vorigen Zuges liegt so gut wie nie auf einem erreichbaren Feld
                standardBoard = board,
                isWhitePerspective = myColourIsWhite
            ) ?: return false

            UltraRobustClassifier.uciFromScreenCells(
                detected.fromCell, detected.toCell, myColourIsWhite, board
            ) ?: return false
        }

        val fromSquare = uci.substring(0, 2)
        val movingPiece = board[8 - (fromSquare[1] - '0')][fromSquare[0] - 'a']
        val moverIsWhite = movingPiece.isUpperCase()

        val nextBoard = UltraRobustClassifier.applyUciMove(board, uci) ?: return false
        lastAcceptedBoard = nextBoard
        castlingRights = UltraRobustClassifier.updateCastlingRights(
            castlingRights ?: UltraRobustClassifier.computeCastlingRights(board),
            uci,
            movingPiece
        )
        markProgress()
        Log.i(TAG, "Zug abgelesen: $uci von ${if (moverIsWhite) "Weiß" else "Schwarz"} (ohne Neuerkennung)")

        // Die Stellung hat sich geändert: ab hier gilt der aktuelle Bildschirm als Vergleichsbasis
        referenceCells = cells
        lastTickCells = cells
        changePendingTicks = 0
        stillTicks = 0
        ticksSinceAnalysis = 0
        undecidedRuns = 0

        if (moverIsWhite == myColourIsWhite) {
            // Eigener Zug: die Empfehlung ist erledigt
            clearPendingMove()
            return true
        }

        // Der Gegner hat gezogen: jetzt bin ich am Zug und es wird gerechnet
        val position = UltraRobustClassifier.buildFenFromStandardBoard(
            standardBoard = nextBoard,
            activeIsWhite = myColourIsWhite,
            boardRect = lastBoardRect ?: Rect(),
            castlingRights = castlingRights
        )

        // Für die Dauer der Berechnung als beschäftigt melden. Sonst könnte ein Tippen auf
        // "Bester Zug" mitten hinein eine zweite Analyse starten, die dieselben Felder anfasst.
        isAnalyzing = true
        analysisStartedAt = System.currentTimeMillis()
        try {
            // Ergibt die fortgeschriebene Stellung etwas Unmögliches, ist die Buchführung entgleist.
            // Dann wird sie verworfen und im nächsten Takt frisch vom Bildschirm erkannt - besser
            // ein Durchgang Verzögerung als eine Partie lang auf einer falschen Stellung zu rechnen.
            if (!evaluateAndPresent(position)) {
                Log.w(TAG, "Fortgeschriebene Stellung ist unmöglich, sie wird neu vom Bildschirm erkannt")
                lastAcceptedBoard = null
                clearPendingMove()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Berechnung zum abgelesenen Zug fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
            lastAcceptedBoard = null
            clearPendingMove()
        } finally {
            isAnalyzing = false
        }
        return true
    }

    /**
     * Engine fragen und das Ergebnis anzeigen bzw. ausführen.
     *
     * Gemeinsamer Abschluss beider Wege - der fortgeschriebenen Stellung und der vollständigen
     * Erkennung. Ob ein Pfeil gezeichnet wird, hängt allein davon ab, ob die Empfehlung von Hand
     * angefordert wurde: Im Auto-Betrieb wird der Zug getippt statt gezeigt.
     */
    private suspend fun evaluateAndPresent(res: UltraRobustClassifier.DetectionResult): Boolean {
        // Vorgegebene Bedenkzeit: go depth 30 movetime 2000
        val eval = withTimeout(ANALYSIS_TIMEOUT_MS) {
            StockfishBridge.evaluateFen(res.fullFen, moveTimeMs = StockfishBridge.DEFAULT_MOVE_TIME_MS)
        }

        if (eval.bestMove == "(invalid)") {
            reportError("Unmögliche Stellung erkannt (Könige nebeneinander oder Figuren fehlerhaft)")
            return false
        }

        val isTrueFallback = eval.depth <= 0 &&
            eval.bestMove != "(checkmate)" && eval.bestMove != "(stalemate)"
        Log.i(
            TAG,
            "Zug=${eval.bestMove} Tiefe=${eval.depth}" + (if (isTrueFallback) " [Engine-Fallback]" else "")
        )

        // Die beiden Felder des Zuges merken: daran wird erkannt, wann er ausgeführt ist,
        // und darauf tippt der Auto-Zug.
        registerPendingMove(eval.bestMove, res.isWhitePerspective, res.boardRect)
        return true
    }

    /** Der gezeigte Zug ist erledigt: die Beobachtung der beiden Felder endet */
    /** Merkt, dass gerade etwas vorangegangen ist - die Aufsichtsuhr beginnt von vorn */
    private fun markProgress() {
        lastProgressAt = System.currentTimeMillis()
    }

    private fun clearPendingMove() {
        autoMoveBusyUntil = 0L
        pendingSince = 0L
        pendingFromCell = -1
        pendingToCell = -1
        pendingMoveUci = null
        pendingMoveReference = null
    }

    /**
     * Rochaderechte nach einer Neuerkennung: aus der Stellung geraten, aber nie großzügiger als
     * das bisher Mitgeführte. Ein einmal verspieltes Recht kommt nicht zurück.
     */
    private fun narrowCastlingRights(known: String?, board: Array<CharArray>): String {
        val fromBoard = UltraRobustClassifier.computeCastlingRights(board)
        if (known == null) return fromBoard
        val allowed = known.filter { it != '-' }.toSet()
        val kept = fromBoard.filter { it in allowed }
        return if (kept.isEmpty()) "-" else kept
    }

    /**
     * Führt eine Auswertung auf dem bereitgehaltenen Bild aus, ohne es zu verbrauchen.
     *
     * Bewusst nicht herausnehmen und schließen: Dann stünde bis zum nächsten gelieferten Bild
     * nichts mehr zur Verfügung, und bei einem stillstehenden Brett kommt gar keines nach. Das
     * Bild bleibt liegen, bis das System ein neueres liefert - der Aufrufer liest nur daraus.
     *
     * Die Sperre umschließt die Auswertung, damit das Bild nicht mitten im Lesen geschlossen wird.
     */
    private fun <T> withLatestImage(block: (android.media.Image) -> T): T? = synchronized(imageLock) {
        val img = latestImage ?: return@synchronized null
        block(img)
    }

    /** Bereitgehaltenes Bild verwerfen (beim Abbau der Aufnahmesitzung) */
    private fun dropLatestImage() = synchronized(imageLock) {
        try { latestImage?.close() } catch (_: Exception) {}
        latestImage = null
    }

    /** Tiefe Kopie eines Bretts: die Erkennung gibt ihre Puffer weiter, die dürfen nicht altern */
    private fun copyBoard(board: Array<CharArray>): Array<CharArray> =
        Array(board.size) { r -> board[r].copyOf() }

    /** Vergleichsbasis und Zähler zurücksetzen; der nächste Takt nimmt das Brett neu auf */
    private fun resetMonitorState() {
        referenceCells = null
        lastTickCells = null
        // lastKnownCells bleibt bewusst stehen: das ist kein Vergleichsmaßstab, sondern nur die
        // zuletzt gelesene Abtastung. Sie wird gebraucht, wenn gerade kein neuer Frame kommt -
        // und genau dann wäre ein Verwerfen fatal, weil dann gar nichts mehr gelesen werden kann.
        changePendingTicks = 0
        ticksSinceAnalysis = 0
        undecidedRuns = 0
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        clearPendingMove()
        resetMonitorState()
    }

    /**
     * Nimmt den ersten Frame auf, der nach dem Ausblenden von Pfeil und Blase entsteht.
     *
     * Alle bereits gepufferten Frames stammen noch aus der Zeit davor und werden verworfen. Das
     * Ausblenden selbst löst eine neue Bildkomposition aus, der nächste Frame ist also sauber.
     */
    private suspend fun captureFullFrame(): Bitmap? = withContext(Dispatchers.IO) {
        if (imageReader == null) return@withContext null
        try {
            // Das bereitgehaltene Bild ist sauber und wird sofort genommen
            withLatestImage { bitmapFromImage(it) }?.let { return@withContext it }

            val deadline = System.currentTimeMillis() + FRAME_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                withLatestImage { bitmapFromImage(it) }?.let { return@withContext it }
                delay(10)
            }
            Log.i(TAG, "Kein Frame verfügbar")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Aufnahme fehlgeschlagen: ${e.message}")
            null
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
     * Blase und Pfeil werden dafür nicht ausgeblendet: ihre Fenster tragen FLAG_SECURE und stehen
     * gar nicht erst im aufgenommenen Bild.
     *
     * @return null, wenn gerade kein neuer Frame vorliegt - dann hat sich auf dem Bildschirm nichts bewegt
     */
    private suspend fun captureBoardCells(rect: Rect): BoardCells? = withContext(Dispatchers.IO) {
        if (imageReader == null) return@withContext null
        try {
            // Aus dem bereitgehaltenen Bild lesen, ohne es zu verbrauchen. Liefert das System kein
            // neues, bleibt dieses liegen und zeigt weiterhin den unveränderten Bildschirm - genau
            // das, was bei einem stillstehenden Brett gebraucht wird.
            return@withContext withLatestImage<BoardCells?> { frame ->
            val plane = frame.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val left = rect.left.coerceAtLeast(0)
            val top = rect.top.coerceAtLeast(0)
            val right = rect.right.coerceAtMost(screenWidth)
            val bottom = rect.bottom.coerceAtMost(screenHeight)
            if (right - left < 32 || bottom - top < 32) return@withLatestImage null

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
            }
        } catch (e: Exception) {
            Log.w(TAG, "captureBoardCells fehlgeschlagen: ${e.message}")
            null
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
        sideEstablished = true
        perspectiveConflictStreak = 0

        // Mit der Blickrichtung dreht sich die Zuordnung von Bildschirmfeldern zu Feldnamen.
        // Alles, was auf der alten Zuordnung beruht, ist damit hinfällig und wird verworfen -
        // sonst rechnete DuLo auf einem gespiegelten Brett weiter.
        lastAcceptedBoard = null
        castlingRights = null
        clearPendingMove()
        resetMonitorState()
        markProgress()
        if (autoAnalyseEnabled) startAnalysis(force = true)

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
            autoMoveEnabled = false
            autoMoveToggle?.setOn(false, animate = true)
            stopMonitoring()
            requestReAuthorization()
            return
        }

        isAnalyzing = true
        analysisStartedAt = System.currentTimeMillis()

        serviceScope.launch {
            var screenBitmap: Bitmap? = null
            // Wurde dieser Durchgang zu einem Ergebnis geführt? Nur dann darf die Vergleichsbasis
            // der Beobachtungsschleife fallengelassen werden. Ohne diese Unterscheidung nahm die
            // Schleife nach einer unklaren Aufnahme den veränderten Bildschirm als neue Basis - der
            // Zug des Gegners war damit verschluckt und die Anzeige rührte sich nicht mehr.
            var analysisDecided = false
            try {
                // Nichts ausblenden: Blase und Menü hält keepBubbleClearOfBoard vom Brett fern,
                // und die Störungsmeldung liegt in der Bildschirmmitte mit FLAG_SECURE. Es gibt
                // also nichts, was die Aufnahme verfälschen könnte.
                screenBitmap = captureFullFrame()

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
                    reportError("Brett unvollständig im Bild")
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
                        // Eine frische Grundstellung heißt: neue Partie. Dann wird die Farbe neu
                        // bestimmt - der Nutzer spielt mal Weiß, mal Schwarz, und eine Sperre aus
                        // der vorigen Partie wäre jetzt falsch herum.
                        val freshGame = UltraRobustClassifier.isFreshStartPosition(res.rawBoard)
                        if ((!sideEstablished || freshGame) && !manualPerspectiveLock) {
                            val sideFromRows = UltraRobustClassifier.sideFromStartingRows(res.rawBoard)
                            if (sideFromRows != null) {
                                if (sideEstablished && sessionLockedPerspective != sideFromRows) {
                                    // Farbwechsel: alles, was sich auf die alte Partie bezog, ist hinfällig
                                    lastAcceptedBoard = null
                                }
                                sessionLockedPerspective = sideFromRows
                                sideEstablished = true
                                Log.i(
                                    TAG,
                                    "Eigene Farbe aus der Ausgangsstellung: ${if (sideFromRows) "Weiß" else "Schwarz"}" +
                                        (if (freshGame) " (neue Partie)" else "")
                                )
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
                        // Die Blase darf weder Felder verdecken noch die eigenen Berührungen abfangen
                        keepBubbleClearOfBoard(res.boardRect)

                        // Kern der Dauerbeobachtung: wer hat gezogen?
                        //
                        // Entschieden wird das über den unmittelbaren Vergleich der beiden letzten
                        // angenommenen Bretter. Die Figur, die auf einem Feld neu aufgetaucht ist,
                        // benennt den Ziehenden - das trägt auch beim Schlagzug, bei dem eine Figur
                        // der Gegenfarbe verschwindet. Die frühere Zählweise über Feldmengen konnte
                        // genau das nicht unterscheiden und lag beim Schlagen regelmäßig daneben.
                        //
                        // Ist der Vergleich nicht eindeutig, passiert nichts: die Vergleichsbasis
                        // bleibt stehen und der nächste Takt sieht erneut nach. Wichtig, denn ein
                        // stillschweigendes Übergehen würde den Zug des Gegners verschlucken - die
                        // App wirkte dann nach ein paar Sekunden wie eingefroren.
                        val previousBoard = lastAcceptedBoard
                        val myColourIsWhite = sessionLockedPerspective

                        if (previousBoard == null || myColourIsWhite == null) {
                            // Erster Durchgang der Sitzung: Stellung annehmen und rechnen
                            lastAcceptedBoard = copyBoard(res.standardBoard)
                            castlingRights = UltraRobustClassifier.computeCastlingRights(res.standardBoard)
                            analysisDecided = true
                        } else {
                            val diff = UltraRobustClassifier.diffBoards(previousBoard, res.standardBoard)
                            Log.i(
                                TAG,
                                "Brettvergleich: ${diff.changedSquares} Felder verändert, Zug von " +
                                    when (diff.moverIsWhite) {
                                        true -> "Weiß"
                                        false -> "Schwarz"
                                        null -> "unklar"
                                    }
                            )

                            if (diff.changedSquares == 0) {
                                // Nichts passiert. Der Pfeil bleibt stehen, wo er steht.
                                analysisDecided = true
                                return@launch
                            }
                            if (diff.moverIsWhite == null && undecidedRuns < MAX_UNDECIDED_RUNS) {
                                // Unklare Aufnahme (Animation, Fehlerkennung): Basis behalten und
                                // im nächsten Takt erneut nachsehen, nichts verwerfen.
                                Log.i(TAG, "Vergleich nicht eindeutig, wird im nächsten Takt wiederholt")
                                return@launch
                            }

                            if (diff.moverIsWhite == null) {
                                // Notbremse: Bleibt der Vergleich mehrfach unklar, wird die aktuelle
                                // Stellung zwangsweise als neue Grundlage angenommen und gerechnet.
                                // Ohne diesen Ausweg blieb die Anzeige nach einigen Zügen dauerhaft
                                // stehen - lieber eine Empfehlung, die einen Halbzug zu früh kommt,
                                // als gar keine mehr.
                                Log.i(TAG, "Vergleich bleibt unklar, Stellung wird neu als Grundlage genommen")
                            }

                            lastAcceptedBoard = copyBoard(res.standardBoard)
                            // Nach einer Neuerkennung ist die Zugfolge verloren: die Rechte werden
                            // wieder aus der Stellung geraten - aber nie großzügiger als bisher,
                            // denn ein einmal verspieltes Recht kommt nicht zurück.
                            castlingRights = narrowCastlingRights(castlingRights, res.standardBoard)
                            analysisDecided = true

                            if (diff.moverIsWhite == myColourIsWhite) {
                                // Eigener Zug ausgeführt: die Empfehlung ist erledigt, Pfeil weg,
                                // und es wird gewartet, bis der Gegner gezogen hat.
                                Log.i(TAG, "Eigener Zug erkannt, Pfeil wird ausgeblendet")
                                clearPendingMove()
                                return@launch
                            }

                            // Der Gegner hat gezogen: jetzt bin ich am Zug und es wird gerechnet.
                        }

                        evaluateAndPresent(res)

                        Log.i(
                            TAG,
                            "Erkennung: Perspektive=${if (res.isWhitePerspective) "Weiß" else "Schwarz"}" +
                                " (${detailedResp.perspectiveReason}, ${String.format("%.2f", detailedResp.perspectiveConfidence)})" +
                                " Sim=${String.format("%.3f", detailedResp.medianSim)}"
                        )
                    }
                    is UltraRobustClassifier.ClassificationResponse.Rejected -> {
                        reportError(
                            "Vom Gatter abgewiesen: ${detailedResp.reason} (Sim=${String.format("%.3f", detailedResp.medianSim)}, belegt=${detailedResp.occupiedCount})"
                        )
                    }
                    null -> {
                        reportError("Der Klassifikator ist nicht initialisiert")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // Sicherheitsnetz: hängt die Engine oder ein Aufnahmeschritt, wird der Durchgang
                // abgeräumt. Ohne das blieb isAnalyzing für immer gesetzt und die Beobachtung
                // rührte sich nicht mehr - von außen sah das aus, als sei die App abgestürzt.
                Log.w(TAG, "Analyse hat zu lange gedauert und wurde abgebrochen")
            } catch (e: Exception) {
                Log.w(TAG, "Analyse abgebrochen: ${e.javaClass.simpleName}: ${e.message}")
                reportError("Ausnahme in der Analyse: ${e.javaClass.simpleName}")
            } finally {
                screenBitmap?.recycle()
                if (analysisDecided) {
                    // Der Durchgang hat etwas entschieden: der nächste Takt nimmt das Brett neu auf.
                    markProgress()
                    resetMonitorState()
                    undecidedRuns = 0
                    nextFullScanAt = 0L
                } else {
                    // Nichts entschieden (kein Frame, unklare Aufnahme, abgewiesene Erkennung):
                    // die Vergleichsbasis bleibt stehen, damit die Veränderung anhängig bleibt.
                    // Nur die Zähler werden zurückgesetzt, sonst liefe sofort der nächste Versuch.
                    changePendingTicks = 0
                    stillTicks = 0
                    ticksSinceAnalysis = 0
                    undecidedRuns++
                    // Wachsender Abstand, gedeckelt: der erste Wiederholversuch kommt schnell,
                    // danach wird es ruhiger, bis die Aufsichtsuhr ohnehin aufräumt.
                    nextFullScanAt = System.currentTimeMillis() +
                        (FULL_SCAN_BACKOFF_MS * undecidedRuns).coerceAtMost(FULL_SCAN_BACKOFF_MAX_MS)
                }
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
        // Erst das bereitgehaltene Bild freigeben, dann den Leser schließen - umgekehrt wirft das
        // Schließen des Bildes hinterher.
        dropLatestImage()
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
        autoMoveEnabled = false
        setOverlayTouchable(true)
        stopMonitoring()
        hideMenu()
        serviceScope.cancel()
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        transparentOverlay?.release()
        transparentOverlay = null
        cleanupProjection()
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        captureHandler = null
        StockfishBridge.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
