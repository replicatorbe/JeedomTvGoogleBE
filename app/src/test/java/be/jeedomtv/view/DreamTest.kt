package be.jeedomtv.view

import be.jeedomtv.model.AppState
import be.jeedomtv.model.HeaderItem
import be.jeedomtv.model.JeedomConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Écran de veille : contenu selon l'état, horloge et date en français, petit déplacement. */
class DreamTest {

    private val header = (1..7).map { HeaderItem("h$it", "Info $it", value = "$it") }
    private val config = JeedomConfig("192.168.1.10", "cle")

    @Test
    fun `sans configuration - l'heure seule`() {
        val content = dreamContent(AppState(header = header, revision = "r"))
        assertTrue(content.header.isEmpty())
        assertFalse(content.unreachable)
    }

    @Test
    fun `configuree et en ligne - les infos du bandeau, 6 au plus`() {
        val content = dreamContent(AppState(config = config, revision = "r", header = header))
        assertEquals(header.take(6), content.header)
        assertFalse(content.unreachable)
    }

    @Test
    fun `hors ligne ou pages jamais chargees - l'heure et Jeedom injoignable`() {
        val offline = dreamContent(AppState(config = config, revision = "r", header = header, offline = true))
        assertTrue(offline.header.isEmpty())
        assertTrue(offline.unreachable)
        val neverLoaded = dreamContent(AppState(config = config, revision = null))
        assertTrue(neverLoaded.unreachable)
    }

    @Test
    fun `sans header - seulement l'heure, en ligne`() {
        val content = dreamContent(AppState(config = config, revision = "r"))
        assertTrue(content.header.isEmpty())
        assertFalse(content.unreachable)
    }

    @Test
    fun `heure et date en francais`() {
        val millis = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 7, 21, 5, 30) }.timeInMillis
        assertEquals("21:05", dreamTime(millis))
        assertEquals("Mercredi 7 octobre", dreamDate(millis))
        val janvier = Calendar.getInstance().apply { set(2027, Calendar.JANUARY, 1, 8, 0, 0) }.timeInMillis
        assertEquals("08:00", dreamTime(janvier))
        assertEquals("Vendredi 1 janvier", dreamDate(janvier))
    }

    @Test
    fun `deplacement - change chaque minute, reste petit, revient en boucle`() {
        val shifts = (0L until 8L).map { dreamShift(it) }
        assertEquals(8, shifts.toSet().size)
        shifts.forEach { (x, y) -> assertTrue(kotlin.math.abs(x) <= 48 && kotlin.math.abs(y) <= 48) }
        assertEquals(dreamShift(3), dreamShift(11))
        assertEquals(dreamShift(0), dreamShift(-8))
    }
}
