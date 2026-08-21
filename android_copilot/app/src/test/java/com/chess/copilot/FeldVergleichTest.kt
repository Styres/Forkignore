package com.chess.copilot

import com.chess.copilot.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für den Feldvergleich der Dauerbeobachtung: Er entscheidet, ob sich auf dem Brett etwas
 * bewegt hat, ohne dafür die volle Erkennung zu starten.
 */
class FeldVergleichTest {

    private fun leeresBrett() = FloatArray(64) { 120f }

    @Test
    fun testUnveraendertesBrettMeldetKeineAenderung() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        assertFalse(UltraRobustClassifier.boardCellsChanged(means, stds, means.copyOf(), stds.copyOf()))
    }

    @Test
    fun testLeichtesRauschenMeldetKeineAenderung() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        // Kompressionsrauschen bewegt Helligkeit und Streuung nur minimal
        val nachher = FloatArray(64) { means[it] + if (it % 2 == 0) 3f else -2f }
        val nachherStds = FloatArray(64) { stds[it] + 1.5f }
        assertFalse(UltraRobustClassifier.boardCellsChanged(means, stds, nachher, nachherStds))
    }

    @Test
    fun testFigurVerlaesstEinFeld() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        // Auf Feld 20 stand eine Figur (hohe Streuung), jetzt ist es leer
        stds[20] = 45f
        val nachherStds = stds.copyOf()
        nachherStds[20] = 4f
        assertTrue(UltraRobustClassifier.boardCellsChanged(means, stds, means.copyOf(), nachherStds))
    }

    @Test
    fun testFigurBetrittEinFeld() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        val nachherStds = stds.copyOf()
        nachherStds[42] = 48f
        assertTrue(UltraRobustClassifier.boardCellsChanged(means, stds, means.copyOf(), nachherStds))
    }

    @Test
    fun testFelderUnterDemPfeilWerdenUebersprungen() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        // Genau auf Feld 30 liegt der Pfeil und verfälscht die Helligkeit deutlich
        val nachher = means.copyOf()
        nachher[30] = 220f
        assertTrue(UltraRobustClassifier.boardCellsChanged(means, stds, nachher, stds.copyOf()))
        assertFalse(
            UltraRobustClassifier.boardCellsChanged(
                means, stds, nachher, stds.copyOf(), ignoredCells = setOf(30)
            )
        )
    }

    @Test
    fun testUnterschiedlicheGroessenGeltenAlsVeraendert() {
        assertTrue(
            UltraRobustClassifier.boardCellsChanged(
                FloatArray(64), FloatArray(64), FloatArray(16), FloatArray(16)
            )
        )
    }

    @Test
    fun testPfeilfelderEntlangDerStrecke() {
        // Waagerechter Pfeil von Feld (3,1) nach (3,5): alle Felder dazwischen zählen dazu
        val felder = UltraRobustClassifier.cellsCoveredByArrow(3, 1, 3, 5)
        assertEquals(setOf(3 * 8 + 1, 3 * 8 + 2, 3 * 8 + 3, 3 * 8 + 4, 3 * 8 + 5), felder)
    }

    @Test
    fun testPfeilfelderBeimSpringerzug() {
        // Springerzug von (7,1) nach (5,2): Start und Ziel müssen enthalten sein
        val felder = UltraRobustClassifier.cellsCoveredByArrow(7, 1, 5, 2)
        assertTrue(felder.contains(7 * 8 + 1))
        assertTrue(felder.contains(5 * 8 + 2))
        // und nichts außerhalb des Bretts
        assertTrue(felder.all { it in 0..63 })
    }
}
