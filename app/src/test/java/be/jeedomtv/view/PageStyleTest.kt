package be.jeedomtv.view

import be.jeedomtv.controller.contractTiles
import be.jeedomtv.model.Choice
import be.jeedomtv.model.Page
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileIcon
import be.jeedomtv.model.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Habillage des pages : icônes des onglets et des tuiles, valeur en coin, jauge, état allumé. */
class PageStyleTest {

    private fun page(name: String, vararg tiles: Tile) = Page("p", name, tiles.toList())

    @Test
    fun `icone d'onglet d'apres le nom de la page`() {
        assertEquals("mdi:lightbulb-group", pageMdiIcon(page("Lumières")))
        assertEquals("mdi:window-shutter", pageMdiIcon(page("Volets")))
        assertEquals("mdi:thermostat", pageMdiIcon(page("Chauffage et clim")))
        assertEquals("mdi:thermometer", pageMdiIcon(page("Températures")))
        assertEquals("mdi:cctv", pageMdiIcon(page("Caméras")))
        assertEquals("mdi:palette", pageMdiIcon(page("Scénarios")))
    }

    @Test
    fun `icone d'onglet d'apres les tuiles, sinon aucune`() {
        val light = Tile("a", TileType.Switch, "Plafond", TileIcon.Light)
        val scene = Tile("s", TileType.Scene, "Cinéma", TileIcon.Scene)
        assertEquals("mdi:lightbulb-group", pageMdiIcon(page("Rez", light, light.copy(id = "b"))))
        assertEquals("mdi:palette", pageMdiIcon(page("Soirée", scene)))
        assertNull("page mélangée", pageMdiIcon(page("Salon", light, scene)))
        assertNull("page vide", pageMdiIcon(page("Divers")))
    }

    @Test
    fun `icone de tuile - variante allumee, type par defaut, toutes presentes dans la police`() {
        val light = Tile("a", TileType.Switch, "Plafond", TileIcon.Light, value = "0")
        assertEquals("mdi:lightbulb", tileMdiIcon(light))
        assertEquals("mdi:lightbulb-on", tileMdiIcon(light.copy(value = "1")))
        assertEquals("mdi:format-list-bulleted", tileMdiIcon(Tile("l", TileType.Select, "Mode")))
        assertEquals("mdi:cctv", tileMdiIcon(Tile("c", TileType.Button, "Portail", TileIcon.Camera)))
        val codes = parseMdiCodepoints(File("src/main/assets/mdi/codepoints.txt").readText())
        val names = TileIcon.entries.flatMap { listOf(it.mdiName(), it.mdiName(on = true)) } +
            listOf("mdi:format-list-bulleted", "mdi:gesture-tap-button", "mdi:lightbulb-group", "mdi:help-circle-outline")
        names.forEach { assertTrue(it, codes.containsKey(mdiName(it))) }
    }

    @Test
    fun `valeur en coin - etat, valeur, choix avec chevron, rien pour une scene`() {
        val (switch, shutter, shutterNoPos, slider, info, scene) = contractTiles()
        assertEquals("Allumé", tileCornerText(switch))
        assertEquals("Ouvert", tileCornerText(shutter))
        assertEquals("▲ ▼", tileCornerText(shutterNoPos))
        assertEquals("20,5 °C", tileCornerText(slider))
        assertEquals("24 °C", tileCornerText(info))
        assertNull(tileCornerText(scene))
        val select = Tile("m", TileType.Select, "Mode", value = "cold", choices = listOf(Choice("cold", "Froid")))
        assertEquals("Froid ›", tileCornerText(select))
    }

    @Test
    fun `jauge - volet et curseur avec bornes seulement`() {
        val (switch, shutter, shutterNoPos, slider) = contractTiles()
        assertEquals(1f, tileFraction(shutter))
        assertEquals(0.55f, tileFraction(slider)!!, 0.001f)
        assertNull(tileFraction(shutterNoPos))
        assertNull(tileFraction(switch))
    }

    @Test
    fun `allumee - seulement un interrupteur allume`() {
        val (switch, shutter) = contractTiles()
        assertTrue(switch.isActive)
        assertFalse(switch.copy(value = "0").isActive)
        assertFalse(shutter.isActive)
    }

    private operator fun <T> List<T>.component6(): T = this[5]
}
