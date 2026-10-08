package be.jeedomtv.controller

import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.VideoUrl
import be.jeedomtv.model.driver.JeedomException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File de notifications (3 au plus en attente), coche de confirmation d'un ordre, et barre des
 * 10 dernières secondes avant la fermeture du panneau pour inactivité.
 */
class AppControllerQueueTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    private fun TestScope.started(factory: FakeDriverFactory = FakeDriverFactory(), visible: Boolean = false): AppController {
        val c = AppController(
            AppModel(AppState(uiVisible = visible)),
            FakeSettings(stored = config),
            factory,
            backgroundScope,
            OverlayPermission { true },
        )
        c.start()
        runCurrent()
        return c
    }

    private fun FakeDriverFactory.send(vararg commands: TvCommand) {
        pushChanges(commands("c${changesCalls.size}", *commands))
    }

    private fun note(id: Long, message: String, tag: String? = null, seconds: Int = 10, video: VideoUrl? = null) =
        TvCommand.Notify(id, "", message, tag = tag, durationSec = seconds, video = video)

    private val AppController.overlayMessage: String? get() = (state.value.overlay as? Overlay.Notice)?.banner?.message
    private val AppController.appMessage: String? get() = state.value.banner?.message

    // --- File de notifications -------------------------------------------------------------------

    @Test
    fun `par-dessus la video - affichees l'une apres l'autre, dans l'ordre`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Portail"), note(2, "Colis"), note(3, "Pluie"))
        runCurrent()
        assertEquals("Portail", c.overlayMessage)
        advanceTimeBy(10_100)
        assertEquals("Colis", c.overlayMessage)
        advanceTimeBy(10_000)
        assertEquals("Pluie", c.overlayMessage)
        advanceTimeBy(10_000)
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `deux notifications de 8 s coup sur coup - 8 s puis 8 s, par-dessus la video`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Test A", seconds = 8))
        runCurrent()
        factory.send(note(2, "Test B", seconds = 8))
        runCurrent()
        advanceTimeBy(7_900)
        assertEquals("A reste toute sa durée", "Test A", c.overlayMessage)
        advanceTimeBy(200)
        assertEquals("Test B", c.overlayMessage)
        advanceTimeBy(7_800)
        assertEquals("B reste toute sa durée, comptée depuis son affichage", "Test B", c.overlayMessage)
        advanceTimeBy(200)
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `deux notifications de 8 s coup sur coup - 8 s puis 8 s, dans l'application`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(note(1, "Test A", seconds = 8))
        runCurrent()
        factory.send(note(2, "Test B", seconds = 8))
        runCurrent()
        advanceTimeBy(7_900)
        assertEquals("Test A", c.appMessage)
        advanceTimeBy(200)
        assertEquals("Test B", c.appMessage)
        advanceTimeBy(7_800)
        assertEquals("Test B", c.appMessage)
        advanceTimeBy(200)
        assertNull(c.state.value.banner)
    }

    @Test
    fun `dans l'application - meme file`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(note(1, "Portail"), note(2, "Colis"))
        runCurrent()
        assertEquals("Portail", c.appMessage)
        advanceTimeBy(10_100)
        assertEquals("Colis", c.appMessage)
        advanceTimeBy(10_000)
        assertNull(c.state.value.banner)
    }

    @Test
    fun `trois au plus en attente - la plus ancienne en attente est abandonnee`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Affichée"), note(2, "A"), note(3, "B"), note(4, "C"), note(5, "D"))
        runCurrent()
        val seen = mutableListOf(c.overlayMessage)
        repeat(4) {
            advanceTimeBy(10_000)
            runCurrent()
            c.overlayMessage?.let { if (it != seen.last()) seen += it }
        }
        assertEquals(listOf("Affichée", "B", "C", "D"), seen)
    }

    @Test
    fun `meme tag que l'affichee - remplacement immediat`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Portail ouvert", tag = "portail"), note(2, "Colis"))
        runCurrent()
        factory.send(note(3, "Portail fermé", tag = "portail"))
        runCurrent()
        assertEquals("Portail fermé", c.overlayMessage)
        advanceTimeBy(10_100)
        assertEquals("la file continue", "Colis", c.overlayMessage)
    }

    @Test
    fun `meme tag qu'une en attente - remplacee a sa place dans la file`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Affichée"), note(2, "Pluie 1", tag = "pluie"), note(3, "Colis"))
        runCurrent()
        factory.send(note(4, "Pluie 2", tag = "pluie"))
        runCurrent()
        advanceTimeBy(10_100)
        assertEquals("Pluie 2", c.overlayMessage)
        advanceTimeBy(10_000)
        assertEquals("Colis", c.overlayMessage)
        advanceTimeBy(10_000)
        assertEquals("pas de doublon", Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `dismiss - retire une notification en attente`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Affichée"), note(2, "Colis", tag = "colis"), note(3, "Pluie"))
        runCurrent()
        factory.send(TvCommand.Dismiss(4, "colis"))
        runCurrent()
        assertEquals("Affichée", c.overlayMessage)
        advanceTimeBy(10_100)
        assertEquals("Pluie", c.overlayMessage)
    }

    @Test
    fun `dismiss de l'affichee - la suivante prend sa place aussitot`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(note(1, "Portail", tag = "portail"), note(2, "Colis"))
        runCurrent()
        factory.send(TvCommand.Dismiss(3, "portail"))
        runCurrent()
        assertEquals("Colis", c.appMessage)
    }

    @Test
    fun `video en attente - pas dans l'etat, donc aucun lecteur`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        val camera = VideoUrl("rtsp://camera.example/flux")
        factory.send(note(1, "Portail"), note(2, "Sonnette", video = camera))
        runCurrent()
        assertNull("seule la notification affichée est dans l'état", (c.state.value.overlay as Overlay.Notice).banner.video)
        assertNull(c.state.value.banner)
        advanceTimeBy(10_100)
        assertEquals(camera, (c.state.value.overlay as Overlay.Notice).banner.video)
    }

    @Test
    fun `question - jamais dans la file, affichee aussitot`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Portail"), note(2, "Colis"), TvCommand.Ask(3, "j", "", "Ouvrir ?", listOf("Oui"), 30))
        runCurrent()
        assertEquals("j", c.state.value.question?.ask)
        assertEquals("Portail", c.overlayMessage)
    }

    // --- Coche de confirmation -----------------------------------------------------------------

    @Test
    fun `ordre confirme - coche environ 1 s, interrupteur, volet et curseur`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        c.onCommand(RemoteCommand.Digit(1)) // Interrupteur : bascule.
        runCurrent()
        assertEquals("t1", c.state.value.confirmedTileId)
        advanceTimeBy(1_100)
        assertNull(c.state.value.confirmedTileId)

        c.onCommand(RemoteCommand.Digit(4)) // Curseur : réglage puis envoi.
        c.onCommand(RemoteCommand.Up)
        c.onCommand(RemoteCommand.Ok)
        runCurrent()
        assertEquals(TileAction.Set, factory.execCalls.last().action)
        assertEquals("t4", c.state.value.confirmedTileId)
    }

    @Test
    fun `ordre en attente puis refuse - pas de coche, message d'erreur`() = runTest {
        val factory = FakeDriverFactory()
        val gate = CompletableDeferred<Unit>()
        factory.onExec = { gate.await(); throw JeedomException("Commande refusée", httpCode = 500) }
        val c = started(factory, visible = true)
        c.onCommand(RemoteCommand.Digit(1))
        runCurrent()
        assertNull("pas de coche avant la réponse de Jeedom", c.state.value.confirmedTileId)
        gate.complete(Unit)
        runCurrent()
        assertNull(c.state.value.confirmedTileId)
        assertEquals("Commande refusée", c.state.value.notice)
    }

    @Test
    fun `scene - l'eclair habituel, pas de coche`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        c.onCommand(RemoteCommand.Digit(6)) // Scène avec confirmation.
        c.onCommand(RemoteCommand.Ok)
        runCurrent()
        assertEquals("t6", c.state.value.flashTileId)
        assertNull(c.state.value.confirmedTileId)
    }

    // --- Barre avant la fermeture du panneau -------------------------------------------------

    @Test
    fun `panneau - barre les 10 dernieres secondes, toute touche la reinitialise`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p1", durationSec = 0))
        runCurrent()
        advanceTimeBy(49_900)
        assertFalse(c.state.value.panelClosing)
        advanceTimeBy(200)
        assertTrue(c.state.value.panelClosing)
        c.onCommand(RemoteCommand.Right)
        assertFalse("touche : barre effacée, attente relancée", c.state.value.panelClosing)
        advanceTimeBy(49_000)
        assertTrue(c.state.value.overlay is Overlay.Panel)
        assertFalse(c.state.value.panelClosing)
        advanceTimeBy(1_100)
        assertTrue(c.state.value.panelClosing)
        advanceTimeBy(10_000)
        assertEquals(Overlay.None, c.state.value.overlay)
        assertFalse(c.state.value.panelClosing)
    }

    @Test
    fun `panneau avec duree de Jeedom - pas de barre, ce n'est pas de l'inactivite`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p1", durationSec = 30))
        runCurrent()
        advanceTimeBy(25_000)
        assertFalse(c.state.value.panelClosing)
        advanceTimeBy(5_100)
        assertEquals(Overlay.None, c.state.value.overlay)
    }
}
