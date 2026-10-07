package be.jeedomtv.view

import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileType
import org.junit.Assert.assertEquals
import org.junit.Test

class TileTextTest {

    @Test
    fun `nom avec pièce séparée par un point médian`() {
        assertEquals("Jardin" to "Projecteur LED NORD", splitRoom("Jardin · Projecteur LED NORD"))
    }

    @Test
    fun `bouton - triangle sans valeur, sinon la valeur`() {
        assertEquals("▶", tileValueText(Tile("b", TileType.Button, "Caméra")))
        assertEquals("Ouvert", tileValueText(Tile("b", TileType.Button, "Portail", value = "Ouvert")))
        assertEquals("1", tileValueText(Tile("b", TileType.Button, "Projecteur", value = "1")))
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
