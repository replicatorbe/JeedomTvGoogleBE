package be.jeedomtv.controller

import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.QuestionStatus
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.driver.JeedomException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La notification affichée suit l'écran (application, panneau, superposition) avec son temps
 * restant ; identité stable ; image en échec ; expiration en file ; `ask_close` pendant l'envoi.
 */
class AppControllerRelocateTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(),
        visible: Boolean = false,
        permission: Boolean = true,
    ): AppController {
        val c = AppController(
            AppModel(AppState(uiVisible = visible)),
            FakeSettings(stored = config),
            factory,
            backgroundScope,
            OverlayPermission { permission },
            elapsedMs = { testScheduler.currentTime },
        )
        c.start()
        runCurrent()
        return c
    }

    private fun FakeDriverFactory.send(vararg commands: TvCommand) {
        pushChanges(commands("c${changesCalls.size}", *commands))
    }

    private fun note(id: Long, message: String, seconds: Int = 10, image: String? = null) =
        TvCommand.Notify(id, "", message, image = image, durationSec = seconds)

    private val AppController.overlayMessage: String? get() = (state.value.overlay as? Overlay.Notice)?.banner?.message

    // --- 1 : application cachée ou panneau fermé --------------------------------------------

    @Test
    fun `application cachee - la notification passe en superposition avec son temps restant`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(note(1, "Sonnette"), note(2, "Colis"))
        runCurrent()
        advanceTimeBy(4_000)
        c.onUiVisibilityChanged(false)
        assertNull(c.state.value.banner)
        assertEquals("Sonnette", c.overlayMessage)
        advanceTimeBy(5_900)
        assertEquals("pas plus que ses 10 s en tout", "Sonnette", c.overlayMessage)
        advanceTimeBy(200)
        assertEquals("la file avance", "Colis", c.overlayMessage)
    }

    @Test
    fun `application cachee sans permission - retiree, la file ne reste pas bloquee`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true, permission = false)
        factory.send(note(1, "Sonnette"), note(2, "Colis"))
        runCurrent()
        c.onUiVisibilityChanged(false)
        assertNull(c.state.value.banner)
        assertEquals(Overlay.None, c.state.value.overlay)
        c.onUiVisibilityChanged(true)
        assertEquals("Colis", c.state.value.banner?.message)
    }

    @Test
    fun `panneau ferme - sa notification passe en superposition`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p1"))
        runCurrent()
        factory.send(note(2, "Sonnette"))
        runCurrent()
        assertEquals("affichée dans le panneau", "Sonnette", c.state.value.banner?.message)
        advanceTimeBy(3_000)
        c.onCommand(RemoteCommand.Back)
        runCurrent()
        assertNull(c.state.value.banner)
        assertEquals("Sonnette", c.overlayMessage)
        advanceTimeBy(6_900)
        assertEquals("Sonnette", c.overlayMessage)
        advanceTimeBy(200)
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    // --- 11 : application au premier plan, panneau ouvert ----------------------------------

    @Test
    fun `application au premier plan - la superposition passe dans l'application`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Sonnette"))
        runCurrent()
        advanceTimeBy(6_000)
        c.onUiVisibilityChanged(true)
        assertEquals(Overlay.None, c.state.value.overlay)
        val banner = c.state.value.banner!!
        assertEquals("Sonnette", banner.message)
        assertEquals("même échéance : 10 s après son premier affichage", 10_000L, banner.endsAtMs)
        advanceTimeBy(3_900)
        assertEquals("Sonnette", c.state.value.banner?.message)
        advanceTimeBy(200)
        assertNull(c.state.value.banner)
    }

    @Test
    fun `panneau ouvert - la superposition passe dans le panneau`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Sonnette"))
        runCurrent()
        factory.send(TvCommand.Show(2, "p1"))
        runCurrent()
        assertTrue(c.state.value.overlay is Overlay.Panel)
        assertEquals("Sonnette", c.state.value.banner?.message)
    }

    // --- 4, 7, 16 : identité, image -----------------------------------------------------------

    @Test
    fun `identite stable - l'image arrivee ne change ni l'id ni l'echeance`() = runTest {
        val gate = CompletableDeferred<ByteArray>()
        val factory = FakeDriverFactory().apply { onImage = { gate.await() } }
        val c = started(factory)
        factory.send(note(1, "Portier", image = "i1"))
        runCurrent()
        val before = (c.state.value.overlay as Overlay.Notice).banner
        assertTrue(before.id != 0L)
        gate.complete(byteArrayOf(1, 2))
        runCurrent()
        val after = (c.state.value.overlay as Overlay.Notice).banner
        assertEquals(before.id, after.id)
        assertEquals(before.endsAtMs, after.endsAtMs)
    }

    @Test
    fun `image en echec - marquee, la carte texte prend le relais`() = runTest {
        val factory = FakeDriverFactory().apply { onImage = { throw JeedomException("Image expirée", httpCode = 404) } }
        val c = started(factory)
        factory.send(note(1, "Portier", image = "i1"))
        runCurrent()
        assertTrue((c.state.value.overlay as Overlay.Notice).banner.imageFailed)
    }

    @Test
    fun `panneau ouvert - pas de telechargement d'image, puis a l'ouverture de l'application`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p1"))
        runCurrent()
        factory.send(note(2, "Portier", image = "i1"))
        runCurrent()
        assertTrue("le panneau n'affiche que le texte", factory.images.isEmpty())
        c.onCommand(RemoteCommand.Menu)
        c.onUiVisibilityChanged(true)
        runCurrent()
        assertEquals(listOf("i1"), factory.images)
    }

    // --- 9 : expiration en file, badge -------------------------------------------------------

    @Test
    fun `file - une notification qui a attendu plus que sa duree est abandonnee`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(note(1, "Longue", seconds = 60), note(2, "Linge sec", seconds = 10), note(3, "Colis", seconds = 120))
        runCurrent()
        assertEquals(2, c.state.value.waitingNotifications)
        advanceTimeBy(60_100)
        assertEquals("« Linge sec » a attendu 60 s (plus de 30 s et de ses 10 s) : périmée", "Colis", c.overlayMessage)
        assertEquals(0, c.state.value.waitingNotifications)
    }

    // --- 8 : ask_close pendant l'envoi de la réponse ------------------------------------------

    @Test
    fun `ask_close pendant l'envoi - le 409 n'ecrase pas « Reponse donnee »`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val factory = FakeDriverFactory().apply {
            onAnswer = { _, _ ->
                gate.await()
                throw JeedomException("Déjà répondu", httpCode = 409)
            }
        }
        val c = started(factory, visible = true)
        factory.send(TvCommand.Ask(1, "j1", "Sonnette", "Ouvrir ?", listOf("Ouvrir", "Ignorer"), 30))
        runCurrent()
        c.onCommand(RemoteCommand.Ok)
        runCurrent()
        assertEquals(QuestionStatus.Sending, c.state.value.question?.status)
        factory.send(TvCommand.AskClose(2, "j1", answer = "Ouvrir", by = "TV salon"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(QuestionStatus.AnsweredElsewhere("Ouvrir", "TV salon"), c.state.value.question?.status)
        advanceTimeBy(3_100)
        assertNull(c.state.value.question)
    }
}
