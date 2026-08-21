package com.chess.copilot.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Rein in Kotlin geschriebener multimodaler 2D-Figurenerkenner für Duolingo-Schach (V7, semantisches Qualitätsgatter)
 * Kernmechanik:
 * 1. Absolutes Qualitätsgatter für die Figurensemantik (MedianSim >= 0.52f && belegt >= 4) blockt Nicht-Brett-Bilder (Startseite, Lobby) zu 100 %
 * 2. Nach dem Belegungsgatter folgen adaptive vertikale Vordergrund-Schwerpunktsuche und Schiebefenster-Klemmung (x0=clamp, y0=clamp) gegen abgeschnittene Figurenköpfe in Reihe 0 und Dimensionskollaps
 * 3. Score-Fusion zweier Regionen: 0.65 * cos(f_body, t_body) + 0.35 * cos(f_head, t_head) trennt Turm/Dame/Läufer/Bauer/Springer/König präzise
 * 4. Adaptives 2-Means-Clustering der Farbzugehörigkeit inklusive Einfarbigkeitsschutz (Spannweite < 35)
 * 5. Strikte Einhaltung der Lichess-Regelkonventionen und der Obergrenzen für die Figurenanzahl
 */
class UltraRobustClassifier(context: Context? = null) {

    data class TemplateFeature(
        val className: String,
        val bodyNorm: FloatArray,
        val headNorm: FloatArray
    )

    data class CellFeature(
        val bodyNorm: FloatArray,
        val headNorm: FloatArray,
        val centerStd: Float,
        val centerMean: Float,
        val gradMean: Float
    )

    sealed class ClassificationResponse {
        data class Success(
            val result: DetectionResult,
            val medianSim: Float,
            val occupiedCount: Int,
            val detectedPerspective: Boolean,
            // Confidence der Perspektiverkennung in [0..1] und das dabei ausschlaggebende Signal:
            // Der Aufrufer sperrt die Perspektive nur bei ausreichender Confidence und kann sie sonst anzeigen
            val perspectiveConfidence: Float = 0.0f,
            val perspectiveReason: String = "",
            // Zellenweise Telemetrie (für die Fälle bug_11~14): unsichere belegte Felder und vom Gatter verworfene Kandidaten, Grundlage für die Kalibrierung der Einzelfeld-Schwellen
            val lowConfidenceCells: List<String> = emptyList(),
            val gateRejectedCells: List<String> = emptyList()
        ) : ClassificationResponse()

        data class Rejected(
            val reason: String,
            val medianSim: Float,
            val occupiedCount: Int,
            // Zellenweise Telemetrie beim Abweisen (für bug_18 und fälschlich geblockte Bretter mit niedrigem Sim): Bildschirmkoordinaten r{r}c{c}=Klasse(sim)
            val lowConfidenceCells: List<String> = emptyList(),
            val gateRejectedCells: List<String> = emptyList()
        ) : ClassificationResponse()
    }

    /**
     * Ergebnis der Perspektiverkennung
     * @param isWhitePerspective true = Weiß sitzt unten (eigene Farbe Weiß)
     * @param confidence Betrag der gewichteten Signalsumme in [0..1]; 0 = die Signale heben sich auf
     * @param reason das Signal mit dem größten Beitrag, für Anzeige und Forensik
     */
    data class PerspectiveVerdict(
        val isWhitePerspective: Boolean,
        val confidence: Float,
        val reason: String
    )

    data class DetectionResult(
        val boardFen: String,
        val fullFen: String,
        val activeColor: String,
        val isWhitePerspective: Boolean,
        val rawBoard: Array<CharArray>,
        val standardBoard: Array<CharArray>,
        val boardRect: Rect,
        val medianSim: Float = 1.0f,
        val occupiedCount: Int = 0
    )

    private val templates = mutableListOf<TemplateFeature>()

    init {
        if (context != null) {
            loadTemplatesFromAssets(context)
        }
    }

    private fun loadTemplatesFromAssets(context: Context) {
        try {
            val templateFiles = context.assets.list("templates") ?: emptyArray()
            for (filename in templateFiles) {
                if (!filename.endsWith(".png")) continue
                val clsName = filename.split("_")[0].uppercase()
                val inputStream: InputStream = context.assets.open("templates/$filename")
                val bmp = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bmp != null) {
                    val feat = extractFeatureFromBitmap(bmp)
                    templates.add(TemplateFeature(clsName, feat.bodyNorm, feat.headNorm))
                    bmp.recycle()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Ausführliche Klassifikationspipeline: liefert die Confidence-Kennzahlen (medianSim, occupiedCount) und den Abweisungsgrund mit zurück
     * Ein per Sitzung gesperrter overridePerspective kann übergeben werden, um Perspektivfehler im Endspiel zu verhindern
     */
    fun classifyBoardDetailed(
        bitmap: Bitmap,
        boardRect: Rect,
        overridePerspective: Boolean? = null
    ): ClassificationResponse {
        // Defensive Klemmung (zweite Absicherung neben dem isCropped-Hinweis des Locators): Das Rect zugeschnittener Frames bzw. Rettungskandidaten kann über den Bildrand hinausragen,
        // ein direktes createBitmap würde eine IllegalArgumentException werfen; schrumpft das Rect beim Schnitt mit dem Vollbild, gilt der Frame als unvollständig und wird abgewiesen (nur Hinweis, kein hartes Matching)
        val safeRect = Rect(boardRect)
        val fullyInside = safeRect.intersect(0, 0, bitmap.width, bitmap.height) &&
            safeRect.width() == boardRect.width() && safeRect.height() == boardRect.height()
        if (!fullyInside || safeRect.width() < 8) {
            return ClassificationResponse.Rejected(reason = "CROPPED_RECT", medianSim = 0f, occupiedCount = 0)
        }
        val step = (safeRect.right - safeRect.left) / 8.0f
        val cellsFeats = Array(8) { r ->
            Array(8) { c ->
                val x1 = (safeRect.left + c * step).toInt()
                val y1 = (safeRect.top + r * step).toInt()
                val x2 = (safeRect.left + (c + 1) * step).toInt()
                val y2 = (safeRect.top + (r + 1) * step).toInt()

                val cellW = max(1, x2 - x1)
                val cellH = max(1, y2 - y1)
                val cellBmp = Bitmap.createBitmap(bitmap, x1, y1, cellW, cellH)
                val feat = extractFeatureFromBitmap(cellBmp)
                if (cellBmp !== bitmap) cellBmp.recycle()
                feat
            }
        }

        // 1. Belegungsgatter und Kosinus-Abgleich mit den Templates
        data class OccupiedCell(
            val r: Int,
            val c: Int,
            val feat: CellFeature,
            val primaryClass: String,
            val bestSimilarity: Float,
            val secondaryClass: String,
            val kingSimilarity: Float
        )
        val occupiedList = mutableListOf<OccupiedCell>()
        // Vom Gatter verworfene Kandidaten: Felder mit zu geringer Zentrumsvarianz, deren Kantengradient aber bereits über der Schwelle liegt - Hauptverdächtige für "echte Figur fälschlich als leer erkannt" (verdeckte Figuren, bug_13/14)
        val gateRejected = mutableListOf<String>()

        for (r in 0..7) {
            for (c in 0..7) {
                val f = cellsFeats[r][c]
                // Belegungsgatter: leere Felder haben extrem niedrige Zentrumsvarianz und Kantengradienten (gradMean >= 22.0 filtert 2.5D-Perspektivschatten und leichte Überlappungen am oberen Rand zuverlässig heraus)
                if (f.centerStd < 6.0f || f.gradMean < 22.0f) {
                    if (f.gradMean >= 22.0f) {
                        gateRejected.add("r${r}c${c}|std=${String.format("%.1f", f.centerStd)}|grad=${String.format("%.1f", f.gradMean)}")
                    }
                    continue
                }

                var bestCls = "P"
                var bestSim = -1e9f
                var secondCls = "P"
                var secondSim = -1e9f
                var kingSim = -1e9f

                for (t in templates) {
                    val bodyCos = computeCosineSimilarity(f.bodyNorm, t.bodyNorm)
                    val headCos = computeCosineSimilarity(f.headNorm, t.headNorm)
                    val score = 0.65f * bodyCos + 0.35f * headCos

                    if (t.className == "K" && score > kingSim) {
                        kingSim = score
                    }
                    if (score > bestSim) {
                        secondSim = bestSim
                        secondCls = bestCls
                        bestSim = score
                        bestCls = t.className
                    } else if (score > secondSim && t.className != bestCls) {
                        secondSim = score
                        secondCls = t.className
                    }
                }
                occupiedList.add(OccupiedCell(r, c, f, bestCls, bestSim, secondCls, kingSim))
            }
        }

        // 2. Semantisches Qualitätsgatter des Bretts (Semantic Quality Gating)
        // Telemetrie beim Abweisen: unsichere belegte Felder aufsteigend nach sim, um bei "echtes Brett fälschlich geblockt" die Einzelfeldverteilung zu prüfen (die Perspektive steht hier noch nicht fest, daher Bildschirmkoordinaten)
        val rejectedLowConf = occupiedList
            .filter { it.bestSimilarity < 0.60f }
            .sortedBy { it.bestSimilarity }
            .map { "r${it.r}c${it.c}=${it.primaryClass}(${String.format("%.2f", it.bestSimilarity)})" }

        if (occupiedList.size < 4) {
            return ClassificationResponse.Rejected(
                reason = "Zu wenige belegte Felder (${occupiedList.size} < 4)",
                medianSim = 0.0f,
                occupiedCount = occupiedList.size,
                lowConfidenceCells = rejectedLowConf,
                gateRejectedCells = gateRejected
            )
        }

        val sortedSims = occupiedList.map { it.bestSimilarity }.sorted()
        val medianSim = if (sortedSims.size % 2 == 1) {
            sortedSims[sortedSims.size / 2]
        } else {
            (sortedSims[sortedSims.size / 2 - 1] + sortedSims[sortedSims.size / 2]) / 2.0f
        }

        // Nicht-Brett-Bilder (z. B. Lernpfad, Lobby) haben eine extrem niedrige Median-Ähnlichkeit (gemessen <= 0.378), echte Bretter >= 0.673
        if (medianSim < 0.52f) {
            return ClassificationResponse.Rejected(
                reason = "Ähnlichkeit zu niedrig (MedianSim=${String.format("%.3f", medianSim)} < 0.520)",
                medianSim = medianSim,
                occupiedCount = occupiedList.size,
                lowConfidenceCells = rejectedLowConf,
                gateRejectedCells = gateRejected
            )
        }

        // 3. Adaptives 2-Means-Clustering trennt die Farben Schwarz und Weiß
        val rawBoard = Array(8) { CharArray(8) { '.' } }
        val means = FloatArray(occupiedList.size) { occupiedList[it].feat.centerMean }
        val splitThreshold = calculateTwoMeansThreshold(means)

        for (cell in occupiedList) {
            val isWhite = cell.feat.centerMean >= splitThreshold
            val sym = if (isWhite) cell.primaryClass[0].uppercaseChar() else cell.primaryClass[0].lowercaseChar()
            rawBoard[cell.r][cell.c] = sym
        }

        // 4. Regelprüfung (kein Bauer auf Reihe 1/8, kapazitätsbewusste Abwertung bei Überschreitung der Figurenobergrenzen)
        val sanitizedBoard = sanitizeBoard(rawBoard)

        // 5. Perspektive bestimmen: welche Farbe sitzt unten am eigenen Brettrand
        // Früher entschied allein die Figurenmehrheit der beiden untersten Reihen darüber.
        // Genau diese Regel kippte im Mittel- und Endspiel (eingedrungene gegnerische Figuren,
        // geräumte eigene Grundreihe, Bauernumwandlung) und ließ die App die Figuren des
        // Gegners statt der eigenen analysieren. detectPerspective gewichtet stattdessen
        // mehrere unabhängige Signale gegeneinander und meldet eine Confidence mit zurück.
        val perspectiveVerdict = detectPerspective(sanitizedBoard)
        val detectedPerspective = perspectiveVerdict.isWhitePerspective
        val effectivePerspective = overridePerspective ?: detectedPerspective

        // 5.5 Zellenweise Confidence-Telemetrie (Lektion aus bug_11: Feld b4 hatte nur Sim 0.50 bei 0.06 Abstand und passierte trotzdem das globale Median-Gatter und verunreinigte das FEN)
        val fileChars = "abcdefgh"
        val lowConfidenceCells = occupiedList
            .filter { it.bestSimilarity < 0.60f }
            .sortedBy { it.bestSimilarity }
            .map { cell ->
                val name = if (effectivePerspective) {
                    "${fileChars[cell.c]}${8 - cell.r}"
                } else {
                    "${fileChars[7 - cell.c]}${cell.r + 1}"
                }
                "$name=${cell.primaryClass}(${String.format("%.2f", cell.bestSimilarity)})"
            }

        val result = buildFenFromBoard(
            rawBoard = sanitizedBoard,
            isWhitePerspective = effectivePerspective,
            boardRect = safeRect,
            medianSim = medianSim,
            occupiedCount = occupiedList.size
        )

        return ClassificationResponse.Success(
            result = result,
            medianSim = medianSim,
            occupiedCount = occupiedList.size,
            detectedPerspective = detectedPerspective,
            perspectiveConfidence = perspectiveVerdict.confidence,
            perspectiveReason = perspectiveVerdict.reason,
            lowConfidenceCells = lowConfidenceCells,
            gateRejectedCells = gateRejected
        )
    }

    fun classifyBoard(bitmap: Bitmap, boardRect: Rect): DetectionResult? {
        return when (val resp = classifyBoardDetailed(bitmap, boardRect)) {
            is ClassificationResponse.Success -> resp.result
            is ClassificationResponse.Rejected -> null
        }
    }

    /**
     * Extrahiert die anatomischen Merkmale zweier Regionen eines 48x48-Feldes (30x30 Körper + 10x30 Kopf)
     */
    fun extractFeatureFromBitmap(cellBmp: Bitmap): CellFeature {
        val resized = if (cellBmp.width == 48 && cellBmp.height == 48) {
            cellBmp
        } else {
            Bitmap.createScaledBitmap(cellBmp, 48, 48, true)
        }
        val pixels = IntArray(48 * 48)
        resized.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        if (resized !== cellBmp) resized.recycle()

        val gray = FloatArray(48 * 48)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 1. Statistik für das Belegungsgatter (auf Basis der 30x30-Zentrumsregion: Zeilen 9..38, Spalten 9..38)
        var sumGray = 0.0f
        var sumSqGray = 0.0f
        for (r in 9..38) {
            for (c in 9..38) {
                val v = gray[r * 48 + c]
                sumGray += v
                sumSqGray += v * v
            }
        }
        val centerMean = sumGray / 900.0f
        val centerVariance = max(0f, (sumSqGray / 900.0f) - (centerMean * centerMean))
        val centerStd = sqrt(centerVariance)

        // 2. Sobel-Gradient des gesamten Feldes
        val mag = FloatArray(48 * 48)
        var sumCenterMag = 0.0f
        for (gy in 1..46) {
            val yOffset = gy * 48
            val yPrev = (gy - 1) * 48
            val yNext = (gy + 1) * 48
            for (gx in 1..46) {
                val v00 = gray[yPrev + gx - 1]
                val v02 = gray[yPrev + gx + 1]
                val v10 = gray[yOffset + gx - 1]
                val v12 = gray[yOffset + gx + 1]
                val v20 = gray[yNext + gx - 1]
                val v22 = gray[yNext + gx + 1]
                val v01 = gray[yPrev + gx]
                val v21 = gray[yNext + gx]

                val sobelX = (v02 + 2f * v12 + v22) - (v00 + 2f * v10 + v20)
                val sobelY = (v20 + 2f * v21 + v22) - (v00 + 2f * v01 + v02)
                val m = sqrt(sobelX * sobelX + sobelY * sobelY)
                mag[yOffset + gx] = m
                if (gy in 9..38 && gx in 9..38) {
                    sumCenterMag += m
                }
            }
        }
        val gradMean = sumCenterMag / 900.0f

        // 3. Vordergrundschwerpunkt per Hintergrunddifferenz aus dem Median der 4 Ecken (3x3 je Ecke, 36 Punkte, gemeinsamer Median)
        val cornerVals = FloatArray(36)
        var cIdx = 0
        for (r in 0..2) {
            for (c in 0..2) {
                cornerVals[cIdx++] = gray[r * 48 + c]
                cornerVals[cIdx++] = gray[r * 48 + (45 + c)]
                cornerVals[cIdx++] = gray[(45 + r) * 48 + c]
                cornerVals[cIdx++] = gray[(45 + r) * 48 + (45 + c)]
            }
        }
        cornerVals.sort()
        val bgVal = cornerVals[18]

        // Suche auf den Innenbereich [2..45] begrenzen, damit die 2px-Randlinien bzw. Kanten der Nachbarfelder den Schwerpunkt nicht verziehen
        var sumFgY = 0.0
        var sumFgX = 0.0
        var fgCount = 0
        for (y in 2..45) {
            val yOff = y * 48
            for (x in 2..45) {
                if (abs(gray[yOff + x] - bgVal) > 15.0f) {
                    sumFgY += y
                    sumFgX += x
                    fgCount++
                }
            }
        }

        val cy = if (fgCount > 0) (sumFgY / fgCount).toFloat() else 24.0f
        val cx = if (fgCount > 0) (sumFgX / fgCount).toFloat() else 24.0f

        // 4. Schiebefenster-Klemmung des Ursprungs (garantiert eine ROI von exakt 36x36, kein Dimensionskollaps)
        val x0 = max(0, min(12, (cx - 18f).roundToInt()))
        val y0 = max(0, min(12, (cy - 18f).roundToInt()))

        // 5. Körpermerkmal: 30x30 (zentriert aus dem 36x36-Fenster geschnitten, 900 Dimensionen)
        val bodyMag = FloatArray(900)
        var bodySumSq = 0.0f
        for (r in 0..29) {
            val srcY = y0 + 3 + r
            for (c in 0..29) {
                val srcX = x0 + 3 + c
                val v = mag[srcY * 48 + srcX]
                bodyMag[r * 30 + c] = v
                bodySumSq += v * v
            }
        }
        val bodyNormVal = sqrt(bodySumSq) + 1e-5f
        val bodyNorm = FloatArray(900) { bodyMag[it] / bodyNormVal }

        // 6. Anatomisches Kopfmerkmal: 10x30 (die obersten 10 Zeilen des 30x30-Körpers, 300 Dimensionen)
        val headMag = FloatArray(300)
        var headSumSq = 0.0f
        for (r in 0..9) {
            for (c in 0..29) {
                val v = bodyMag[r * 30 + c]
                headMag[r * 30 + c] = v
                headSumSq += v * v
            }
        }
        val headNormVal = sqrt(headSumSq) + 1e-5f
        val headNorm = FloatArray(300) { headMag[it] / headNormVal }

        return CellFeature(bodyNorm, headNorm, centerStd, centerMean, gradMean)
    }

    private fun computeCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        val len = min(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
        }
        return dot
    }

    companion object {
        fun calculateTwoMeansThreshold(means: FloatArray): Float {
            if (means.isEmpty()) return 128.0f
            if (means.size == 1) return if (means[0] >= 120f) means[0] - 1f else means[0] + 1f

            val minVal = means.minOrNull() ?: 0.0f
            val maxVal = means.maxOrNull() ?: 255.0f

            if (maxVal - minVal < 35.0f) {
                val avg = means.average().toFloat()
                return if (avg >= 120.0f) minVal - 1.0f else maxVal + 1.0f
            }

            var c1 = minVal
            var c2 = maxVal

            for (iter in 0 until 10) {
                var sum1 = 0.0f
                var cnt1 = 0
                var sum2 = 0.0f
                var cnt2 = 0

                for (m in means) {
                    if (abs(m - c1) <= abs(m - c2)) {
                        sum1 += m
                        cnt1++
                    } else {
                        sum2 += m
                        cnt2++
                    }
                }
                if (cnt1 > 0) c1 = sum1 / cnt1
                if (cnt2 > 0) c2 = sum2 / cnt2
            }
            return (c1 + c2) / 2.0f
        }

        fun sanitizeBoard(board: Array<CharArray>): Array<CharArray> {
            val result = Array(8) { r -> CharArray(8) { c -> board[r][c] } }

            // 1. Auf Reihe 1 (row 7) und Reihe 8 (row 0) darf niemals ein Bauer stehen
            for (c in 0..7) {
                if (result[0][c] == 'P' || result[0][c] == 'p') {
                    result[0][c] = '.'
                }
                if (result[7][c] == 'P' || result[7][c] == 'p') {
                    result[7][c] = '.'
                }
            }

            // 2. Figurenanzahl zählen und Obergrenzen durchsetzen (kapazitätsbewusste Abwertung, damit die Ersatzfigur nicht selbst überläuft)
            val pieceCounts = mutableMapOf<Char, Int>()
            val maxLimits = mapOf(
                'K' to 1, 'k' to 1,
                'Q' to 1, 'q' to 1,
                'R' to 2, 'r' to 2,
                'B' to 2, 'b' to 2,
                'N' to 2, 'n' to 2,
                'P' to 8, 'p' to 8
            )

            for (r in 0..7) {
                for (c in 0..7) {
                    val p = result[r][c]
                    if (p == '.') continue
                    val count = pieceCounts.getOrDefault(p, 0) + 1
                    pieceCounts[p] = count
                    val maxAllowed = maxLimits[p] ?: 8
                    if (count > maxAllowed) {
                        val isWhite = p.isUpperCase()
                        val candidates = if (isWhite) charArrayOf('R', 'B', 'N') else charArrayOf('r', 'b', 'n')
                        var fallbackPiece = '.'
                        for (cand in candidates) {
                            val candCount = pieceCounts.getOrDefault(cand, 0)
                            if (candCount < (maxLimits[cand] ?: 2)) {
                                fallbackPiece = cand
                                pieceCounts[cand] = candCount + 1
                                break
                            }
                        }
                        result[r][c] = fallbackPiece
                    }
                }
            }

            // 3. Eindeutigkeit beider Könige (das alte Nachfüllen wurde abgeschafft, die strenge Prüfung erfolgt stromabwärts)
            var whiteKingCount = 0
            var blackKingCount = 0
            for (r in 0..7) {
                for (c in 0..7) {
                    if (result[r][c] == 'K') whiteKingCount++
                    if (result[r][c] == 'k') blackKingCount++
                }
            }

            // 4. Abschließende Zweitprüfung
            val finalCounts = mutableMapOf<Char, Int>()
            for (r in 0..7) {
                for (c in 0..7) {
                    val p = result[r][c]
                    if (p == '.') continue
                    val cnt = finalCounts.getOrDefault(p, 0) + 1
                    finalCounts[p] = cnt
                    val limit = maxLimits[p] ?: 8
                    if (cnt > limit) {
                        result[r][c] = '.'
                    }
                }
            }

            return result
        }

        fun compressRow(row: CharArray): String {
            val sb = StringBuilder()
            var empty = 0
            for (sym in row) {
                if (sym == '.') {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(sym)
                }
            }
            if (empty > 0) sb.append(empty)
            return sb.toString()
        }

        /**
         * Reine Funktion: berechnet aus dem Standardbrett (row 0 = Reihe 8, row 7 = Reihe 1) die gültigen Rochaderechte
         * Regeln:
         * - Weiße kurze Rochade (K): weißer König auf e1 (r=7, c=4) und weißer Turm auf h1 (r=7, c=7)
         * - Weiße lange Rochade (Q): weißer König auf e1 (r=7, c=4) und weißer Turm auf a1 (r=7, c=0)
         * - Schwarze kurze Rochade (k): schwarzer König auf e8 (r=0, c=4) und schwarzer Turm auf h8 (r=0, c=7)
         * - Schwarze lange Rochade (q): schwarzer König auf e8 (r=0, c=4) und schwarzer Turm auf a8 (r=0, c=0)
         * - Nicht erfüllte Bedingungen streichen den jeweiligen Buchstaben, fällt alles weg, wird "-" ausgegeben (sonst lehnt Stockfish das FEN mit BAD_CASTLING_RIGHTS ab)
         */
        fun computeCastlingRights(board: Array<CharArray>): String {
            val sb = StringBuilder()
            if (board.size >= 8 && board[7].size >= 8 && board[7][4] == 'K') {
                if (board[7][7] == 'R') sb.append('K')
                if (board[7][0] == 'R') sb.append('Q')
            }
            if (board.size >= 8 && board[0].size >= 8 && board[0][4] == 'k') {
                if (board[0][7] == 'r') sb.append('k')
                if (board[0][0] == 'r') sb.append('q')
            }
            return if (sb.isEmpty()) "-" else sb.toString()
        }

        fun buildFenFromBoard(
            rawBoard: Array<CharArray>,
            isWhitePerspective: Boolean,
            boardRect: Rect = Rect(),
            medianSim: Float = 1.0f,
            occupiedCount: Int = 0
        ): DetectionResult {
            val standardBoard = if (isWhitePerspective) {
                rawBoard
            } else {
                Array(8) { r -> CharArray(8) { c -> rawBoard[7 - r][7 - c] } }
            }

            val activeColor = if (isWhitePerspective) "w" else "b"
            val fenRows = standardBoard.map { compressRow(it) }
            val boardFen = fenRows.joinToString("/")
            val castling = computeCastlingRights(standardBoard)
            val fullFen = "$boardFen $activeColor $castling - 0 1"

            return DetectionResult(
                boardFen = boardFen,
                fullFen = fullFen,
                activeColor = activeColor,
                isWhitePerspective = isWhitePerspective,
                rawBoard = rawBoard,
                standardBoard = standardBoard,
                boardRect = boardRect,
                medianSim = medianSim,
                occupiedCount = occupiedCount
            )
        }

        /**
         * Reine Funktion: bestimmt aus dem Bildschirmbrett (row 0 = oben, row 7 = unten),
         * welche Farbe unten sitzt, also welche Figuren die eigenen sind.
         *
         * Fehlerbild vor dieser Funktion: gezählt wurde nur die Farbmehrheit der beiden
         * untersten Reihen. Sobald der Gegner dort eindringt oder die eigene Grundreihe leer
         * wird, kippte das Ergebnis, das FEN wurde gespiegelt und die App schlug Züge für
         * die Figuren des Gegners vor.
         *
         * Stattdessen werden vier unabhängige Signale gewichtet addiert (positiv = Weiß unten):
         * 1. Bauernrichtung (Gewicht bis 4.0): Bauern können nicht zurück, ihre mittlere
         *    Bildschirmzeile ist deshalb über die ganze Partie hinweg das stabilste Signal.
         *    Das Gewicht sinkt mit der Anzahl der noch vorhandenen Bauern.
         * 2. Königsstand (Gewicht 2.0): der eigene König steht in aller Regel unterhalb des gegnerischen.
         * 3. Materialschwerpunkt (Gewicht bis 2.0): alle Figuren nach Abstand zur Brettmitte gewichtet.
         * 4. Grundreihen (Gewicht 1.0): die alte Heuristik, jetzt nur noch eine Stimme von vieren.
         *
         * @return Perspektive, Confidence in [0..1] und das ausschlaggebende Signal
         */
        fun detectPerspective(board: Array<CharArray>): PerspectiveVerdict {
            var whitePawnRowSum = 0.0f
            var whitePawns = 0
            var blackPawnRowSum = 0.0f
            var blackPawns = 0
            var whiteKingRow = -1
            var blackKingRow = -1
            var massSum = 0.0f
            var pieceCount = 0
            var topWhite = 0
            var topBlack = 0
            var botWhite = 0
            var botBlack = 0

            for (r in 0..7) {
                for (c in 0..7) {
                    val sym = board[r][c]
                    if (sym == '.') continue
                    val isWhite = sym.isUpperCase()
                    pieceCount++
                    // Abstand zur Brettmitte, normiert auf [-1..1]: unten positiv, oben negativ
                    massSum += (if (isWhite) 1.0f else -1.0f) * ((r - 3.5f) / 3.5f)
                    when (sym) {
                        'P' -> { whitePawnRowSum += r; whitePawns++ }
                        'p' -> { blackPawnRowSum += r; blackPawns++ }
                        'K' -> whiteKingRow = r
                        'k' -> blackKingRow = r
                    }
                    if (r <= 1) { if (isWhite) topWhite++ else topBlack++ }
                    if (r >= 6) { if (isWhite) botWhite++ else botBlack++ }
                }
            }

            var weightedSum = 0.0f
            var weightTotal = 0.0f
            var strongestSignal = ""
            var strongestContribution = 0.0f

            fun addSignal(name: String, rawValue: Float, weight: Float) {
                if (weight <= 0.0f) return
                val contribution = rawValue.coerceIn(-1.0f, 1.0f) * weight
                weightedSum += contribution
                weightTotal += weight
                if (abs(contribution) > abs(strongestContribution)) {
                    strongestContribution = contribution
                    strongestSignal = name
                }
            }

            // 1. Bauernrichtung: Differenz der mittleren Bildschirmzeile beider Bauernketten
            // (Grundstellung: Weiß 6, Schwarz 1 -> Differenz 5 -> voll ausgeschlagenes Signal)
            if (whitePawns > 0 && blackPawns > 0) {
                val pawnRowDiff = (whitePawnRowSum / whitePawns) - (blackPawnRowSum / blackPawns)
                val pawnWeight = 4.0f * min(1.0f, (whitePawns + blackPawns) / 8.0f)
                addSignal("Bauernrichtung", pawnRowDiff / 5.0f, pawnWeight)
            }

            // 2. Königsstand: nur verwertbar, wenn beide Könige erkannt wurden
            if (whiteKingRow >= 0 && blackKingRow >= 0) {
                addSignal("Königsstand", (whiteKingRow - blackKingRow) / 5.0f, 2.0f)
            }

            // 3. Materialschwerpunkt über alle Figuren, Gewicht sinkt mit abnehmendem Material
            if (pieceCount > 0) {
                addSignal("Materialschwerpunkt", massSum / pieceCount, 2.0f * min(1.0f, pieceCount / 16.0f))
            }

            // 4. Grundreihen: die alte Heuristik als schwächste Stimme
            addSignal("Grundreihen", ((botWhite - botBlack) + (topBlack - topWhite)) / 16.0f, 1.0f)

            val isWhite = when {
                weightedSum > 0.0f -> true
                weightedSum < 0.0f -> false
                // Patt aller Signale (z. B. völlig symmetrisches Brett): alte Heuristik entscheidet
                else -> botWhite >= botBlack
            }
            val confidence = if (weightTotal > 0.0f) {
                (abs(weightedSum) / weightTotal).coerceIn(0.0f, 1.0f)
            } else {
                0.0f
            }
            val reason = if (strongestSignal.isEmpty()) "Grundreihen (Rückfall)" else strongestSignal
            return PerspectiveVerdict(isWhite, confidence, reason)
        }

        /**
         * Reine Funktion: mittlerer Helligkeitsabstand zweier eingedampfter Brettausschnitte.
         *
         * Die Dauerbeobachtung vergleicht damit aufeinanderfolgende Frames, ohne jedes Mal die
         * vollständige Erkennung zu starten. Unterschiedlich lange Raster gelten als völlig
         * verschieden (Float.MAX_VALUE), damit ein Wechsel der Brettgröße sicher auslöst.
         */
        fun fingerprintDistance(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || b.isEmpty() || a.size != b.size) return Float.MAX_VALUE
            var sum = 0.0f
            for (i in a.indices) {
                sum += abs(a[i] - b[i])
            }
            return sum / a.size
        }

        /**
         * Reine Funktion: Fingerabdruck der eigenen Figuren als sortierte Liste "Figur@Feld".
         *
         * Grundlage der laufenden Beobachtung: Solange dieser Fingerabdruck gleich bleibt, hat sich an
         * den eigenen Figuren nichts geändert und die Engine muss nicht erneut rechnen. Sobald eine
         * eigene Figur ihr Feld wechselt (Zug, Schlagfall, Umwandlung), unterscheidet sich der
         * Fingerabdruck und die Analyse läuft neu an.
         *
         * @param standardBoard Brett in Standardausrichtung (row 0 = Reihe 8, row 7 = Reihe 1)
         * @param isWhitePerspective true = die eigenen Figuren sind die weißen (Großbuchstaben)
         */
        fun ownPieceSignature(standardBoard: Array<CharArray>, isWhitePerspective: Boolean): String {
            val fileChars = "abcdefgh"
            val entries = mutableListOf<String>()
            for (r in standardBoard.indices) {
                val row = standardBoard[r]
                for (c in row.indices) {
                    val sym = row[c]
                    if (sym == '.') continue
                    val isWhitePiece = sym.isUpperCase()
                    if (isWhitePiece != isWhitePerspective) continue
                    val file = if (c in fileChars.indices) fileChars[c] else '?'
                    entries.add("$sym@$file${8 - r}")
                }
            }
            entries.sort()
            return entries.joinToString(",")
        }

        /**
         * Reine Funktion: Zustandsautomat für die Perspektivsperre der Sitzung
         * @param currentLock aktuell gesperrte Perspektive der Sitzung (null = noch nicht gesperrt)
         * @param detectedPerspective die im aktuellen Frame erkannte Perspektive
         * @param occupiedCount Anzahl der belegten Felder im aktuellen Frame
         * @param medianSim Median der Template-Ähnlichkeit im aktuellen Frame
         * @param perspectiveConfidence Confidence der Perspektiverkennung aus detectPerspective in [0..1]
         * @return die aktualisierte Sperre (null = Confidence reicht noch nicht zum Sperren)
         */
        fun resolvePerspectiveLock(
            currentLock: Boolean?,
            detectedPerspective: Boolean,
            occupiedCount: Int,
            medianSim: Float,
            perspectiveConfidence: Float = 1.0f
        ): Boolean? {
            // 1. Neue Partie: ab 26 belegten Feldern und hoher Confidence wird die Perspektive zwangsweise neu kalibriert und gesperrt.
            // Die Perspektiv-Confidence muss dabei ebenfalls stimmen, sonst übernimmt eine Fehlerkennung
            // die Sperre für die gesamte Partie und die App analysiert die Figuren des Gegners.
            if (occupiedCount >= 26 && medianSim >= 0.60f && perspectiveConfidence >= 0.30f) {
                return detectedPerspective
            }

            // 2. Erstsperre: ohne bestehende Sperre muss die Confidence-Hürde (occupied >= 16 oder medianSim >= 0.70f) genommen werden
            if (currentLock == null) {
                return if ((occupiedCount >= 16 || medianSim >= 0.70f) && perspectiveConfidence >= 0.20f) {
                    detectedPerspective
                } else {
                    null // Unterhalb der Hürde wird noch nicht gesperrt, dieser Frame nutzt einmalig detectedPerspective
                }
            }

            // 3. Mittel-/Endspiel: bestehende Sperre beibehalten, damit Figurenentwicklung oder Angriff auf der Grundreihe die Perspektive nicht kippen lässt
            return currentLock
        }
    }
}