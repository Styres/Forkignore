package com.chess.copilot

import com.chess.copilot.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests für den Fingerabdruck der eigenen Figuren. Er entscheidet in der laufenden Beobachtung,
 * ob die Engine erneut rechnen muss: gleicher Fingerabdruck = keine eigene Figur hat das Feld gewechselt.
 */
class EigeneFigurenSignaturTest {

    private fun grundstellung() = arrayOf(
        charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
        charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
        charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
    )

    @Test
    fun testNurEigeneFigurenStehenImFingerabdruck() {
        val signatur = UltraRobustClassifier.ownPieceSignature(grundstellung(), isWhitePerspective = true)
        // 16 eigene Figuren, alle in Großbuchstaben, keine gegnerische dabei
        assertEquals(16, signatur.split(",").size)
        assertEquals(true, signatur.contains("K@e1"))
        assertEquals(true, signatur.contains("P@a2"))
        assertEquals(false, signatur.contains("k@"))
        assertEquals(false, signatur.contains("p@"))
    }

    @Test
    fun testGegnerischerZugAendertDenFingerabdruckNicht() {
        val vorher = grundstellung()
        val nachher = grundstellung()
        // Schwarz zieht e7-e5: nur eine gegnerische Figur wechselt das Feld
        nachher[1][4] = '.'
        nachher[3][4] = 'p'

        assertEquals(
            UltraRobustClassifier.ownPieceSignature(vorher, isWhitePerspective = true),
            UltraRobustClassifier.ownPieceSignature(nachher, isWhitePerspective = true)
        )
    }

    @Test
    fun testEigenerZugAendertDenFingerabdruck() {
        val vorher = grundstellung()
        val nachher = grundstellung()
        // Weiß zieht e2-e4
        nachher[6][4] = '.'
        nachher[4][4] = 'P'

        assertNotEquals(
            UltraRobustClassifier.ownPieceSignature(vorher, isWhitePerspective = true),
            UltraRobustClassifier.ownPieceSignature(nachher, isWhitePerspective = true)
        )
    }

    @Test
    fun testGeschlageneEigeneFigurAendertDenFingerabdruck() {
        val vorher = grundstellung()
        val nachher = grundstellung()
        // Der eigene Springer auf b1 wird geschlagen
        nachher[7][1] = 'n'

        assertNotEquals(
            UltraRobustClassifier.ownPieceSignature(vorher, isWhitePerspective = true),
            UltraRobustClassifier.ownPieceSignature(nachher, isWhitePerspective = true)
        )
    }

    @Test
    fun testAusSichtVonSchwarzZaehlenDieKleinbuchstaben() {
        val signatur = UltraRobustClassifier.ownPieceSignature(grundstellung(), isWhitePerspective = false)
        assertEquals(16, signatur.split(",").size)
        assertEquals(true, signatur.contains("k@e8"))
        assertEquals(true, signatur.contains("p@a7"))
        assertEquals(false, signatur.contains("K@"))
    }

    @Test
    fun testReihenfolgeIstStabil() {
        // Derselbe Aufbau muss unabhängig von der Reihenfolge der Felder denselben Text ergeben
        val a = UltraRobustClassifier.ownPieceSignature(grundstellung(), isWhitePerspective = true)
        val b = UltraRobustClassifier.ownPieceSignature(grundstellung(), isWhitePerspective = true)
        assertEquals(a, b)
    }
}
