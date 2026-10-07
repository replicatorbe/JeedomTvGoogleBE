package be.jeedomtv.view

import be.jeedomtv.model.HeaderItem
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
    fun `valeur d'une info du bandeau`() {
        assertEquals("17 °C", headerValueText(HeaderItem("h1", "Extérieur", value = "17", unit = "°C")))
        assertEquals("0,4 mm", headerValueText(HeaderItem("h2", "Pluie", value = "0.4", unit = "mm")))
        assertEquals("demain : Déchets organiques", headerValueText(HeaderItem("h3", "Poubelles", value = "demain : Déchets organiques")))
        assertEquals("—", headerValueText(HeaderItem("h4", "Solaire", value = null, unit = "W")))
    }

    @Test
    fun `largeurs des puces - tout tient, chacune a la sienne`() {
        assertEquals(listOf(100, 300, 120), shareWidths(listOf(100, 300, 120), 600))
    }

    @Test
    fun `largeurs des puces - seules les plus larges sont reduites, la place est remplie`() {
        // 6 puces, 900 px : les petites gardent leur largeur, les deux longues se partagent le reste.
        val widths = shareWidths(listOf(110, 400, 120, 120, 130, 300), 900)
        assertEquals(listOf(110, 210, 120, 120, 130, 210), widths)
        assertEquals(900, widths.sum())
    }

    @Test
    fun `largeurs des puces - place insuffisante pour toutes, parts egales`() {
        assertEquals(listOf(50, 50, 50), shareWidths(listOf(100, 200, 300), 150))
        assertEquals(listOf(0, 0), shareWidths(listOf(10, 20), 0))
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
