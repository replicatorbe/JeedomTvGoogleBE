package be.jeedomtv.view

import org.junit.Assert.assertEquals
import org.junit.Test

class TileTextTest {

    @Test
    fun `nom avec pièce séparée par un point médian`() {
        assertEquals("Jardin" to "Projecteur LED NORD", splitRoom("Jardin · Projecteur LED NORD"))
    }

    @Test
    fun `nom sans pièce`() {
        assertEquals(null to "Consigne salon", splitRoom("Consigne salon"))
    }

    @Test
    fun `séparateur en bord de nom ignoré`() {
        assertEquals(null to " · Volet", splitRoom(" · Volet"))
        assertEquals(null to "Volet · ", splitRoom("Volet · "))
    }
}
