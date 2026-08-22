package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die Frage "bin ich wieder am Zug?".
 *
 * Man ist am Zug, sobald der Gegner gezogen hat, also sobald eine gegnerische Figur auf einem Feld
 * steht, das vorher nicht ihm gehörte. Der eigene Zug selbst macht einen nicht wieder zugberechtigt.
 */
class AmZugErkennungTest {

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

    private fun gegnerFelder(board: Array<CharArray>) =
        UltraRobustClassifier.opponentSquares(board, isWhitePerspective = true)

    @Test
    fun testGegnerischeFelderWerdenKorrektGelesen() {
        val felder = gegnerFelder(grundstellung())
        assertEquals(16, felder.size)
        assertTrue(felder.contains("e8"))
        assertTrue(felder.contains("a7"))
        assertFalse(felder.contains("e1"))
    }

    @Test
    fun testGegnerzugMachtMichWiederZugberechtigt() {
        val vorher = gegnerFelder(grundstellung())
        val nachher = grundstellung()
        // Schwarz zieht e7-e5
        nachher[1][4] = '.'
        nachher[3][4] = 'p'

        assertTrue(UltraRobustClassifier.opponentMovedSince(vorher, gegnerFelder(nachher)))
    }

    @Test
    fun testEigenerZugMachtMichNichtZugberechtigt() {
        val vorher = gegnerFelder(grundstellung())
        val nachher = grundstellung()
        // Weiß zieht e2-e4: an den gegnerischen Feldern ändert sich nichts
        nachher[6][4] = '.'
        nachher[4][4] = 'P'

        assertFalse(UltraRobustClassifier.opponentMovedSince(vorher, gegnerFelder(nachher)))
    }

    @Test
    fun testEigenerSchlagfallMachtMichNichtZugberechtigt() {
        val vorher = grundstellung()
        // Ausgangslage: ein schwarzer Bauer steht auf d5, ein weißer auf e4
        vorher[1][3] = '.'
        vorher[3][3] = 'p'
        vorher[6][4] = '.'
        vorher[4][4] = 'P'

        val nachher = arrayOf(*vorher.map { it.copyOf() }.toTypedArray())
        // Weiß schlägt exd5: die gegnerische Figur verschwindet, es kommt keine neue hinzu
        nachher[4][4] = '.'
        nachher[3][3] = 'P'

        assertFalse(
            UltraRobustClassifier.opponentMovedSince(gegnerFelder(vorher), gegnerFelder(nachher))
        )
    }

    @Test
    fun testGegnerischerSchlagfallZaehltAlsZug() {
        val vorher = grundstellung()
        vorher[1][3] = '.'
        vorher[3][3] = 'p'
        vorher[6][4] = '.'
        vorher[4][4] = 'P'

        val nachher = arrayOf(*vorher.map { it.copyOf() }.toTypedArray())
        // Schwarz schlägt dxe4: die gegnerische Figur steht jetzt auf einem vorher eigenen Feld
        nachher[3][3] = '.'
        nachher[4][4] = 'p'

        assertTrue(
            UltraRobustClassifier.opponentMovedSince(gegnerFelder(vorher), gegnerFelder(nachher))
        )
    }

    @Test
    fun testUnveraendertesBrettLoestNichtsAus() {
        val felder = gegnerFelder(grundstellung())
        assertFalse(UltraRobustClassifier.opponentMovedSince(felder, felder))
    }

    @Test
    fun testAusSichtVonSchwarzZaehlenDieWeissenFiguren() {
        val vorher = UltraRobustClassifier.opponentSquares(grundstellung(), isWhitePerspective = false)
        assertTrue(vorher.contains("e1"))
        assertFalse(vorher.contains("e8"))

        val nachher = grundstellung()
        nachher[6][4] = '.'
        nachher[4][4] = 'P'
        assertTrue(
            UltraRobustClassifier.opponentMovedSince(
                vorher,
                UltraRobustClassifier.opponentSquares(nachher, isWhitePerspective = false)
            )
        )
    }
}
