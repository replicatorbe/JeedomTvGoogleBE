package be.jeedomtv.view

import android.view.KeyEvent
import be.jeedomtv.controller.RemoteCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeyForwarderTest {

    private val received = mutableListOf<RemoteCommand>()

    private fun overlay() = RemoteKeyForwarder(actOnRelease = setOf(RemoteCommand.Back, RemoteCommand.Menu)) {
        received += it
        true
    }

    private fun RemoteKeyForwarder.down(code: Int, repeat: Int = 0) = onKey(code, KeyEvent.ACTION_DOWN, repeat)
    private fun RemoteKeyForwarder.up(code: Int) = onKey(code, KeyEvent.ACTION_UP, 0)

    @Test
    fun `superposition - Retour agit au relachement, appui et relachement consommes`() {
        val keys = overlay()
        assertTrue(keys.down(KeyEvent.KEYCODE_BACK))
        assertTrue("rien à l'appui : la fenêtre doit encore recevoir le relâchement", received.isEmpty())
        assertTrue(keys.up(KeyEvent.KEYCODE_BACK))
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Back), received)
    }

    @Test
    fun `superposition - Retour maintenu ne ferme qu'une fois`() {
        val keys = overlay()
        keys.down(KeyEvent.KEYCODE_BACK)
        assertTrue(keys.down(KeyEvent.KEYCODE_BACK, repeat = 1))
        assertTrue(keys.down(KeyEvent.KEYCODE_BACK, repeat = 2))
        keys.up(KeyEvent.KEYCODE_BACK)
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Back), received)
    }

    @Test
    fun `superposition - relachement sans appui (appui parti a la video) - ignore et non consomme`() {
        val keys = overlay()
        assertFalse(keys.up(KeyEvent.KEYCODE_BACK))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `superposition - fenetre retiree avant le relachement, aucune commande`() {
        val keys = overlay()
        keys.down(KeyEvent.KEYCODE_MENU)
        keys.clear()
        assertFalse(keys.up(KeyEvent.KEYCODE_MENU))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `superposition - OK et fleches agissent a l'appui`() {
        val keys = overlay()
        assertTrue(keys.down(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(keys.down(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf(RemoteCommand.Ok, RemoteCommand.Right), received)
        assertTrue(keys.up(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun `activite - Retour non traite par le controleur passe a Android`() {
        val keys = RemoteKeyForwarder { received += it; it != RemoteCommand.Back }
        assertFalse(keys.down(KeyEvent.KEYCODE_BACK))
        assertFalse(keys.up(KeyEvent.KEYCODE_BACK))
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Back), received)
    }

    @Test
    fun `OK maintenu - une seule commande, repetitions consommees`() {
        val keys = RemoteKeyForwarder { received += it; true }
        keys.down(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(keys.down(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1))
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Ok), received)
    }
}
