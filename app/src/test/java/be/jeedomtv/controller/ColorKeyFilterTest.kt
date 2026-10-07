package be.jeedomtv.controller

import android.view.KeyEvent
import be.jeedomtv.model.AppState
import be.jeedomtv.model.ColorKey
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.Page
import be.jeedomtv.model.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règle du service d'accessibilité : il ne garde que les touches de couleur associées à une
 * page, quand une autre application a le focus ; sinon la fenêtre de l'application les reçoit
 * elle-même. Une touche n'est jamais traitée deux fois.
 */
class ColorKeyFilterTest {

    private val pages = listOf(Page("p1", "Salon", emptyList()), Page("p2", "Cuisine", emptyList()))
    private var state = AppState(pages = pages)
    private val sent = mutableListOf<RemoteCommand>()
    private val filter = ColorKeyFilter(state = { state }, onCommand = { sent += it; true })

    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP
    private val red = KeyEvent.KEYCODE_PROG_RED

    private fun key(keyCode: Int, action: Int, repeat: Int = 0) = filter.onKey(keyCode, action, repeat)

    @Test
    fun `app cachee - rouge consomme a l'appui et au relachement, transmis une seule fois`() {
        assertTrue(key(red, down))
        assertTrue("répétition", key(red, down, repeat = 1))
        assertTrue(key(red, up))
        assertEquals(listOf(RemoteCommand.Color(ColorKey.Red)), sent)
    }

    @Test
    fun `le relachement reste consomme meme si le panneau s'est ouvert entre-temps`() {
        assertTrue(key(red, down))
        state = state.copy(overlay = Overlay.Panel("p1", 0))
        assertTrue(key(red, up))
    }

    @Test
    fun `toutes les autres touches passent, sans exception`() {
        val others = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_A,
        )
        for (code in others) {
            assertFalse("$code appui", key(code, down))
            assertFalse("$code relâchement", key(code, up))
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `sans keys, seules les couleurs autres que rouge passent`() {
        for (code in listOf(KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE)) {
            assertFalse(key(code, down))
            assertFalse(key(code, up))
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `couleur associee par keys, et page inconnue inactive`() {
        state = state.copy(colorKeys = mapOf(ColorKey.Blue to "p2", ColorKey.Green to "p9"))
        assertFalse("rouge absent de keys", key(red, down))
        assertFalse(key(red, up))
        assertFalse("vert → page inconnue", key(KeyEvent.KEYCODE_PROG_GREEN, down))
        assertTrue(key(KeyEvent.KEYCODE_PROG_BLUE, down))
        assertTrue(key(KeyEvent.KEYCODE_PROG_BLUE, up))
        assertEquals(listOf(RemoteCommand.Color(ColorKey.Blue)), sent)
    }

    @Test
    fun `pas encore de pages - rien n'est consomme`() {
        state = AppState()
        assertFalse(key(red, down))
        assertFalse(key(red, up))
    }

    @Test
    fun `application affichee - la touche va a l'activite, pas au service`() {
        state = state.copy(uiVisible = true)
        assertFalse(key(red, down))
        assertFalse(key(red, up))
        assertTrue("traitée par la vue seulement", sent.isEmpty())
    }

    @Test
    fun `panneau ouvert - la touche va au panneau, pas au service`() {
        state = state.copy(overlay = Overlay.Panel("p2", 0))
        assertFalse(key(red, down))
        assertFalse(key(red, up))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `panneau ferme par la touche - son relachement orphelin est consomme`() {
        state = state.copy(overlay = Overlay.Panel("p1", 0))
        assertFalse("l'appui va au panneau", key(red, down))
        state = state.copy(overlay = Overlay.None) // Le panneau s'est fermé à l'appui.
        assertTrue("le relâchement n'ira pas à la vidéo", key(red, up))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `question par-dessus la video - la touche va a la question`() {
        state = state.copy(question = Question("j", "", "Ouvrir ?", listOf("Oui"), 30, 30, inOverlay = true))
        assertFalse(key(red, down))
        assertFalse(key(red, up))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `relachement sans appui connu - il passe`() {
        assertFalse(key(red, up))
    }

    @Test
    fun `clear oublie les appuis en cours`() {
        assertTrue(key(red, down))
        filter.clear()
        assertFalse(key(red, up))
    }
}
