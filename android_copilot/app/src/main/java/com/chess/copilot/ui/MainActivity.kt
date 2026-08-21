package com.chess.copilot.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.ToggleButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chess.copilot.R
import com.chess.copilot.core.ChessLocator
import com.chess.copilot.core.UltraRobustClassifier
import com.chess.copilot.engine.StockfishBridge
import com.chess.copilot.service.FloatingBubbleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Rect
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Hauptbildschirm mit Steuerung und Diagnose
 * Enthält die MediaProjection-Freigabe nach den Vorgaben von Android 14 und die Diagnose einzelner Screenshots
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvResult: TextView
    private lateinit var btnToggleFloating: ToggleButton
    private lateinit var tvToggleStatus: TextView
    private var classifier: UltraRobustClassifier? = null

    // Rückgabe der Galerie-Auswahl (Diagnose einzelner Screenshots)
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { runOfflineDiagnostic(it) }
    }

    // Rückgabe der Bildschirmaufnahme-Freigabe (Android 14)
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startBubbleServiceWithProjection(result.resultCode, result.data!!)
        } else {
            // Ohne Freigabe läuft nichts: der Umschalter darf dann nicht auf "an" stehen bleiben
            btnToggleFloating.isChecked = false
            updateToggleState()
            Toast.makeText(this, "Ohne Aufnahmeberechtigung startet der Overlay-Assistent nicht", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvResult = findViewById(R.id.tvDiagnosticResult)
        classifier = UltraRobustClassifier(this)
        StockfishBridge.init(this)

        // Umschalter: dieselbe Schaltfläche startet und beendet den Overlay-Assistenten.
        // Ausgewertet wird der Zustand nach dem Antippen, den ToggleButton selbst umschaltet.
        btnToggleFloating = findViewById(R.id.btnToggleFloating)
        tvToggleStatus = findViewById(R.id.tvToggleStatus)
        updateToggleState()
        btnToggleFloating.setOnClickListener {
            // Start und Stopp laufen beide asynchron: hier wird nur die Statuszeile gesetzt.
            // Den tatsächlichen Zustand gleicht updateToggleState() in onResume wieder ab.
            if (btnToggleFloating.isChecked) {
                tvToggleStatus.text = "DuLo wird gestartet ..."
                checkOverlayPermissionAndRequestCapture()
            } else {
                tvToggleStatus.text = "Assistent aus – auf DuLo tippen zum Starten"
                stopBubbleService()
            }
        }

        findViewById<Button>(R.id.btnSelectImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Zustand der letzten Aufnahmesitzung und die letzte gespeicherte Diagnose anzeigen, als Verlauf gekennzeichnet und mit lesbarer Zeitangabe
        try {
            val sb = StringBuilder()
            val stateFile = File(filesDir, "debug/projection_state.txt")
            if (stateFile.exists() && stateFile.length() > 0) {
                sb.append("[Verlauf - Zustand der letzten Aufnahmesitzung]\n${formatHistoricalLog(stateFile.readText())}\n\n")
            }
            // Zellenweise Forensik der letzten Diagnose anzeigen (für die Fälle bug_13/14)
            val diagFile = File(filesDir, "debug/last_diagnostic.txt")
            if (diagFile.exists() && diagFile.length() > 0) {
                sb.append("[Verlauf - letzte Diagnose]\n${formatHistoricalLog(diagFile.readText())}")
            }
            if (sb.isNotEmpty()) {
                tvResult.text = sb.toString().trimEnd()
            }
        } catch (_: Exception) {
        }
    }

    private fun formatHistoricalLog(rawText: String): String {
        val timePattern = Pattern.compile("Time:\\s*(\\d{10,13})")
        val matcher = timePattern.matcher(rawText)
        val sb = StringBuffer()
        while (matcher.find()) {
            val millis = matcher.group(1)?.toLongOrNull()
            if (millis != null) {
                val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
                matcher.appendReplacement(sb, "Zeitpunkt: $formatted")
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    /**
     * Der Umschalter steht beim Zurückkehren in die App immer auf dem tatsächlichen Zustand des Dienstes,
     * auch wenn dieser zwischenzeitlich vom System oder über die Benachrichtigung beendet wurde.
     */
    override fun onResume() {
        super.onResume()
        updateToggleState()
    }

    /** Umschalter und Statuszeile auf den tatsächlichen Zustand des Dienstes bringen */
    private fun updateToggleState() {
        val running = FloatingBubbleService.isRunning
        btnToggleFloating.isChecked = running
        tvToggleStatus.text = if (running) {
            "Assistent läuft – auf DuLo tippen zum Stoppen"
        } else {
            "Assistent aus – auf DuLo tippen zum Starten"
        }
    }

    /** Umschalter auf "aus": den Vordergrunddienst über seine Stopp-Aktion beenden */
    private fun stopBubbleService() {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_STOP
        }
        startService(stopIntent)
        Toast.makeText(this, "Der Overlay-Assistent wird beendet", Toast.LENGTH_SHORT).show()
    }

    private fun checkOverlayPermissionAndRequestCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // Ohne Overlay-Berechtigung bleibt der Umschalter aus, bis der Nutzer aus den Einstellungen zurückkommt
            btnToggleFloating.isChecked = false
            updateToggleState()
            Toast.makeText(this, "Bitte zuerst die Overlay-Berechtigung erteilen, damit die Züge über Duolingo angezeigt werden können", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // Freigabe der Bildschirmaufnahme nach Android 14 anfordern
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startBubbleServiceWithProjection(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra(FloatingBubbleService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingBubbleService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "DuLo ist gestartet. Jetzt Duolingo öffnen und spielen", Toast.LENGTH_SHORT).show()
        finish() // Fenster schließen und zurück zum Startbildschirm
    }

    private fun runOfflineDiagnostic(imageUri: Uri) {
        lifecycleScope.launch {
            try {
                tvResult.text = "Bild wird gelesen, Brett wird erkannt und der Zug berechnet ..."
                val inputStream = contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) {
                    tvResult.text = "Das Bild konnte nicht geladen werden"
                    return@launch
                }

                // 1. Brett in zwei Stufen per Kammfilter lokalisieren (mit Confidence-Wert, Telemetrie für bug_18)
                var locateResult = withContext(Dispatchers.Default) {
                    ChessLocator.locateBoard(bitmap)
                }
                var boardRect = locateResult.rect

                // 2. Ausführliche Entscheidungspipeline mit Forensiktafel (MedianSim / belegte Felder / Abweisungsgrund)
                var detailedResp = withContext(Dispatchers.Default) {
                    classifier?.classifyBoardDetailed(bitmap, boardRect)
                }

                // 2.5 Rettung über den Zweitkandidaten (Befund aus bug_19/superbug): liefert der Hauptrahmen eine unmögliche Stellung oder wird er komplett abgewiesen,
                // ist das ein sicheres Zeichen für einen falsch gewählten Rahmen (die Energie einer UI-Kante kann den echten Rahmen überbieten, 710 gegen 670)
                // Zusätzlich darf die Confidence nicht "low" sein und das Residuum muss sehr klein bleiben
                val needRescue = locateResult.confidence == "low" ||
                    (detailedResp is UltraRobustClassifier.ClassificationResponse.Success &&
                    StockfishBridge.validateFenSanity(detailedResp.result.fullFen) != null) ||
                    detailedResp is UltraRobustClassifier.ClassificationResponse.Rejected
                    
                if (needRescue) {
                    val candidates = withContext(Dispatchers.Default) {
                        ChessLocator.locateTopCandidates(bitmap, 2)
                    }
                    if (candidates.size >= 2) {
                        val rescueRect = candidates[1].rect
                        val rescueResp = withContext(Dispatchers.Default) {
                            classifier?.classifyBoardDetailed(bitmap, rescueRect)
                        }
                        // Harte Bedingung: das Residuum des Zweitkandidaten muss innerhalb von 5 % der Feldbreite liegen
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
                        val res = detailedResp.result
                        
                        // Auch die Diagnose einzelner Screenshots legt das FEN in die Zwischenablage
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("FEN", res.fullFen)
                        clipboard.setPrimaryClip(clip)
                        
                        // 3. Stockfish berechnet den besten Zug (die EngineEvaluation enthält die Diagnose genau dieser Berechnung)
                        val eval = StockfishBridge.evaluateFen(res.fullFen)

                        val sb = StringBuilder()
                        sb.append("[Diagnose eines einzelnen Screenshots]\n")
                        val cropTag = if (locateResult.isCropped) " [zugeschnittener Frame]" else ""
                        sb.append("Brettkoordinaten: [L=${boardRect.left}, T=${boardRect.top}, R=${boardRect.right}, B=${boardRect.bottom}] Locator-Score=${String.format("%.0f", locateResult.score)} | Confidence=${locateResult.confidence} | Residuum=${String.format("%.2f", locateResult.residual)}px$cropTag\n")
                        sb.append("Perspektive: ${if (res.isWhitePerspective) "Weiß (White)" else "Schwarz (Black)"} | Signal: ${detailedResp.perspectiveReason} (${String.format("%.0f", detailedResp.perspectiveConfidence * 100)}%)\n")
                        sb.append("Forensiktafel: MedianSim=${String.format("%.3f", detailedResp.medianSim)} | belegte Felder=${detailedResp.occupiedCount}\n")
                        // Zellenweise Forensik (für die Fälle bug_11~14): unsichere Felder deuten auf Fehlklassifikation, vom Gatter verworfene Kandidaten auf übersehene Figuren (std=Zentrumsvarianz, grad=Kantengradient)
                        if (detailedResp.lowConfidenceCells.isNotEmpty()) {
                            sb.append("Unsichere Felder: ${detailedResp.lowConfidenceCells.joinToString(" ")}\n")
                        }
                        if (detailedResp.gateRejectedCells.isNotEmpty()) {
                            sb.append("Vom Gatter verworfen: ${detailedResp.gateRejectedCells.joinToString(" ")}\n")
                        }
                        sb.append("Stellung (FEN): ${res.boardFen}\n")
                        sb.append("Vollständiges FEN: ${res.fullFen}\n")
                        sb.append("(FEN wurde in die Zwischenablage kopiert)\n")
                        sb.append("-----------------------------\n")
                        
                        // Zugtext mit vorangestelltem Figurentyp zusammensetzen
                        var displayMoveStr = eval.bestMove
                        if (eval.bestMove.length >= 4 && eval.bestMove[0] in 'a'..'h') {
                            val fileIdx = eval.bestMove[0] - 'a'
                            val rankIdx = 8 - (eval.bestMove[1] - '0')
                            val pieceChar = res.standardBoard[rankIdx][fileIdx]
                            if (!pieceChar.equals('p', ignoreCase = true) && pieceChar != '.') {
                                displayMoveStr = "${pieceChar.uppercaseChar()}-${eval.bestMove}"
                            }
                        }
                        
                        val displayMove = when (eval.bestMove) {
                            "(checkmate)" -> "Kein legaler Zug (Partie entschieden, Schachmatt)"
                            "(stalemate)" -> "Kein legaler Zug (Remis durch Patt)"
                            "(none)" -> "Kein legaler Zug"
                            "(invalid)" -> "Abgewiesen: unmögliche Stellung erkannt (kein Engine-Fehler, siehe Diagnose unten)"
                            else -> displayMoveStr
                        }
                        sb.append("Empfohlener Zug: $displayMove\n")
                        sb.append("Bewertung: ${if (eval.evalScore >= 0) "+" else ""}${String.format("%.2f", eval.evalScore)}\n")
                        sb.append("Suchtiefe: ${if (eval.depth <= 0) "0 ${if (eval.bestMove == "(invalid)") "[von der FEN-Vorprüfung abgefangen]" else "[Fallback-Generator]"}" else "${eval.depth}"}\n")
                        if (eval.isMate) {
                            sb.append("Mattstatus: Gewinn ist erzwungen\n")
                        }
                        sb.append("-----------------------------\n")
                        sb.append("${eval.diagnosticInfo}\n")

                        tvResult.text = sb.toString()
                        Toast.makeText(this@MainActivity, "FEN in die Zwischenablage kopiert", Toast.LENGTH_SHORT).show()

                        // Diagnose speichern, damit sie beim nächsten Start wieder angezeigt werden kann
                        saveOfflineDiagnosticArtifact(boardRect, locateResult, res.fullFen, detailedResp, eval)
                    }
                    is UltraRobustClassifier.ClassificationResponse.Rejected -> {
                        val sb = StringBuilder()
                        sb.append("[Vom Gatter abgewiesen]\n")
                        sb.append("Grund: ${detailedResp.reason}\n")
                        val cropTag = if (locateResult.isCropped) " [zugeschnittener Frame]" else ""
                        sb.append("Forensiktafel: MedianSim=${String.format("%.3f", detailedResp.medianSim)} | belegte Felder=${detailedResp.occupiedCount} | Locator-Score=${String.format("%.0f", locateResult.score)} | Confidence=${locateResult.confidence} | Residuum=${String.format("%.2f", locateResult.residual)}px$cropTag\n")
                        sb.append("Brettkoordinaten: [L=${boardRect.left}, T=${boardRect.top}, R=${boardRect.right}, B=${boardRect.bottom}]\n")
                        if (detailedResp.lowConfidenceCells.isNotEmpty()) {
                            sb.append("Unsichere Felder: ${detailedResp.lowConfidenceCells.joinToString(" ")}\n")
                        }
                        if (detailedResp.gateRejectedCells.isNotEmpty()) {
                            sb.append("Vom Gatter verworfen: ${detailedResp.gateRejectedCells.joinToString(" ")}\n")
                        }
                        tvResult.text = sb.toString()
                    }
                    null -> {
                        tvResult.text = "Der Klassifikator ist nicht initialisiert"
                    }
                }
            } catch (e: Exception) {
                tvResult.text = "Fehler bei der Diagnose: ${e.message}"
            }
        }
    }

    private fun saveOfflineDiagnosticArtifact(
        boardRect: Rect,
        locateResult: ChessLocator.LocateResult,
        fen: String,
        detailedResp: UltraRobustClassifier.ClassificationResponse.Success,
        eval: StockfishBridge.EngineEvaluation
    ) {
        try {
            val debugDir = File(filesDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            val txtFile = File(debugDir, "last_diagnostic.txt")
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val lowConf = if (detailedResp.lowConfidenceCells.isNotEmpty()) "LowConf: ${detailedResp.lowConfidenceCells.joinToString(" ")}\n" else ""
            val gate = if (detailedResp.gateRejectedCells.isNotEmpty()) "GateRejected: ${detailedResp.gateRejectedCells.joinToString(" ")}\n" else ""
            txtFile.writeText(
                "Quelle: [Diagnose eines einzelnen Screenshots]\n" +
                "BoardRect: $boardRect\n" +
                "LocateScore: ${String.format("%.1f", locateResult.score)}\n" +
                "Confidence: ${locateResult.confidence}\n" +
                "Residual: ${String.format("%.2f", locateResult.residual)}\n" +
                "IsCropped: ${locateResult.isCropped}\n" +
                "FEN: $fen\n" +
                "Move: ${eval.bestMove} (Score: ${eval.evalScore}, Depth: ${eval.depth})\n" +
                "$lowConf$gate" +
                "Zeitpunkt: $timeStr\n"
            )
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // Die Stockfish-Engine bleibt für den FloatingBubbleService bestehen und wird hier nicht freigegeben
    }
}
