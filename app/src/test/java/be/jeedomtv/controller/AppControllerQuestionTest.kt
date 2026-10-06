package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.Back
import be.jeedomtv.controller.RemoteCommand.ChannelUp
import be.jeedomtv.controller.RemoteCommand.Digit
import be.jeedomtv.controller.RemoteCommand.Down
import be.jeedomtv.controller.RemoteCommand.Left
import be.jeedomtv.controller.RemoteCommand.Menu
import be.jeedomtv.controller.RemoteCommand.Ok
import be.jeedomtv.controller.RemoteCommand.Right
import be.jeedomtv.controller.RemoteCommand.Up
import be.jeedomtv.model.Adjust
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.QuestionStatus
import be.jeedomtv.model.Screen
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.TvState
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

/** Questions de Jeedom (ordre `ask`, bloc « Demander ») : affichage, choix, réponse, fermeture. */
class AppControllerQuestionTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(),
        visible: Boolean = true,
        permission: Boolean = true,
    ): AppController {
        val c = AppController(
            AppModel(AppState(uiVisible = visible)),
            FakeSettings(stored = config),
            factory,
            backgroundScope,
            OverlayPermission { permission },
        )
        c.start()
        runCurrent()
        return c
    }

    private fun AppController.press(vararg commands: RemoteCommand) = commands.forEach { onCommand(it) }

    private fun FakeDriverFactory.send(vararg commands: TvCommand) {
        pushChanges(commands("c${changesCalls.size}", *commands))
    }

    private fun portail(id: Long = 1, ask: String = "jeton-1", timeout: Int = 30) =
        TvCommand.Ask(id, ask, "Sonnette", "On sonne au portail. Ouvrir ?", listOf("Ouvrir", "Ignorer"), timeout)

    private val AppController.question get() = state.value.question

    // --- Affichage -----------------------------------------------------------------------------

    @Test
    fun `application visible - question dans l'application, premiere reponse selectionnee`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(portail())
        runCurrent()
        val q = c.question!!
        assertEquals("jeton-1", q.ask)
        assertEquals("Sonnette", q.title)
        assertEquals(listOf("Ouvrir", "Ignorer"), q.answers)
        assertEquals(0, q.selected)
        assertEquals(30, q.remainingSec)
        assertEquals(QuestionStatus.Choosing, q.status)
        assertFalse(q.inOverlay)
        assertFalse(c.state.value.foregroundRequested)
    }

    @Test
    fun `application cachee avec permission - question en superposition, visible pour Jeedom`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = false)
        factory.send(portail())
        advanceTimeBy(400)
        assertTrue(c.question!!.inOverlay)
        assertFalse("la vidéo reste devant", c.state.value.foregroundRequested)
        assertEquals(TvState(visible = true, screenOn = true, page = "p1"), factory.states.last())
    }

    @Test
    fun `application cachee sans permission - ouverture de l'activite, puis retour en arriere-plan`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = false, permission = false)
        factory.send(portail())
        runCurrent()
        assertFalse(c.question!!.inOverlay)
        assertTrue(c.state.value.foregroundRequested)
        c.onUiVisibilityChanged(true)
        c.press(Back)
        assertNull(c.question)
        assertTrue("l'application d'avant revient", c.state.value.exitRequested)
    }

    @Test
    fun `l'application affichee pendant une question en superposition la reprend`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = false)
        factory.send(portail())
        runCurrent()
        c.onUiVisibilityChanged(true)
        assertFalse(c.question!!.inOverlay)
        c.onUiVisibilityChanged(false)
        assertTrue(c.question!!.inOverlay)
    }

    // --- Choix et réponse ----------------------------------------------------------------------

    @Test
    fun `fleches - changement de reponse, borne aux extremites`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Ask(1, "j", "", "Alarme ?", listOf("Totale", "Nuit", "Annuler"), 30))
        runCurrent()
        c.press(Left)
        assertEquals(0, c.question!!.selected)
        c.press(Right, Right, Right)
        assertEquals(2, c.question!!.selected)
        c.press(Up)
        assertEquals(1, c.question!!.selected)
        c.press(Down)
        assertEquals(2, c.question!!.selected)
        c.press(ChannelUp, Menu)
        assertEquals("CH+ et Menu sans effet", Screen.Pages, c.state.value.screen)
        assertEquals(0, c.state.value.pageIndex)
        assertTrue(factory.answers.isEmpty())
    }

    @Test
    fun `OK envoie la reponse selectionnee, resultat 2 s puis fermeture`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(portail())
        runCurrent()
        c.press(Right, Ok)
        runCurrent()
        assertEquals(listOf("jeton-1" to "Ignorer"), factory.answers)
        assertEquals(QuestionStatus.Sent("Ignorer"), c.question!!.status)
        advanceTimeBy(1_900)
        assertTrue(c.question != null)
        advanceTimeBy(200)
        assertNull(c.question)
    }

    @Test
    fun `chiffre - selectionne la reponse N sans l'envoyer, OK l'envoie`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(portail())
        runCurrent()
        c.press(Digit(3), Digit(0))
        assertEquals("hors liste : sélection inchangée", 0, c.question!!.selected)
        c.press(Digit(2))
        runCurrent()
        assertTrue("un chiffre n'envoie rien", factory.answers.isEmpty())
        assertEquals(1, c.question!!.selected)
        c.press(Ok)
        runCurrent()
        assertEquals(listOf("jeton-1" to "Ignorer"), factory.answers)
    }

    @Test
    fun `envoi en cours - affiche, touches ignorees`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val factory = FakeDriverFactory().apply { onAnswer = { _, _ -> gate.await() } }
        val c = started(factory)
        factory.send(portail())
        runCurrent()
        c.press(Ok)
        runCurrent()
        assertEquals(QuestionStatus.Sending, c.question!!.status)
        c.press(Right, Ok)
        assertEquals(0, c.question!!.selected)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("jeton-1" to "Ouvrir"), factory.answers)
    }

    @Test
    fun `404 - question expiree`() = runTest {
        val factory = FakeDriverFactory().apply {
            onAnswer = { _, _ -> throw JeedomException("Jeton inconnu", httpCode = 404) }
        }
        val c = started(factory)
        factory.send(portail())
        runCurrent()
        c.press(Ok)
        runCurrent()
        assertEquals(QuestionStatus.Failed("Question expirée"), c.question!!.status)
        advanceTimeBy(2_100)
        assertNull(c.question)
    }

    @Test
    fun `422 - reponse refusee, autre erreur - message de Jeedom`() = runTest {
        var code = 422
        val factory = FakeDriverFactory().apply {
            onAnswer = { _, _ -> throw JeedomException("Jeedom injoignable (192.168.1.10)", httpCode = code.takeIf { it > 0 }) }
        }
        val c = started(factory)
        factory.send(portail(id = 1, ask = "a"))
        runCurrent()
        c.press(Ok)
        runCurrent()
        assertEquals(QuestionStatus.Failed("Réponse refusée"), c.question!!.status)

        code = 0
        factory.send(portail(id = 2, ask = "b"))
        runCurrent()
        c.press(Ok)
        runCurrent()
        assertEquals(QuestionStatus.Failed("Jeedom injoignable (192.168.1.10)"), c.question!!.status)
    }

    @Test
    fun `Retour ferme sans repondre, touche consommee`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(portail())
        runCurrent()
        assertTrue("l'application ne se ferme pas", c.onCommand(Back))
        assertNull(c.question)
        runCurrent()
        assertTrue(factory.answers.isEmpty())
    }

    // --- Délai, remplacement, restauration -----------------------------------------------------

    @Test
    fun `fin du delai - fermeture, les touches ne le prolongent pas`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(portail(timeout = 10))
        runCurrent()
        advanceTimeBy(4_100)
        assertEquals(6, c.question!!.remainingSec)
        c.press(Right, Left)
        advanceTimeBy(5_000)
        assertEquals(1, c.question!!.remainingSec)
        advanceTimeBy(1_000)
        assertNull(c.question)
        assertTrue(factory.answers.isEmpty())
    }

    @Test
    fun `delai absent - une minute`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(portail(timeout = 0))
        runCurrent()
        assertEquals(60, c.question!!.remainingSec)
    }

    @Test
    fun `une nouvelle question remplace la precedente`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(portail(id = 1, ask = "a", timeout = 10))
        runCurrent()
        c.press(Right)
        advanceTimeBy(5_000)
        factory.send(TvCommand.Ask(2, "b", "23 h", "Volets encore ouverts. Fermer ?", listOf("Fermer", "Laisser"), 20))
        runCurrent()
        val q = c.question!!
        assertEquals("b", q.ask)
        assertEquals(0, q.selected)
        assertEquals(20, q.remainingSec)
        advanceTimeBy(10_100)
        assertTrue("l'ancien délai ne ferme pas la nouvelle", c.question != null)
    }

    @Test
    fun `question par-dessus le panneau - le panneau reste ouvert derriere`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = false)
        factory.send(TvCommand.Show(1, "p2", durationSec = 10))
        runCurrent()
        factory.send(portail(id = 2, timeout = 30))
        runCurrent()
        assertTrue(c.state.value.overlay is Overlay.Panel)
        c.press(Right)
        assertEquals("les touches vont à la question", 0, c.state.value.focusedIndex)
        advanceTimeBy(20_000)
        assertTrue("la durée du panneau est suspendue", c.state.value.overlay is Overlay.Panel)
        c.press(Back)
        assertNull(c.question)
        assertEquals(Overlay.Panel("p2", 10), c.state.value.overlay)
        assertEquals(1, c.state.value.pageIndex)
        c.press(Right)
        assertEquals("le panneau a de nouveau la main", 1, c.state.value.focusedIndex)
        advanceTimeBy(60_100)
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `question par-dessus un reglage - le reglage revient`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        c.press(Digit(4), Up)
        factory.send(portail())
        runCurrent()
        c.press(Up, Back)
        assertEquals(Adjust("t4", 21.0), c.state.value.adjust)
        c.press(Up)
        assertEquals(21.5, c.state.value.adjust!!.pending!!, 0.0)
    }

    @Test
    fun `question par-dessus le bandeau`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = false)
        factory.send(TvCommand.Notify(1, "", "Bandeau"), portail(id = 2))
        runCurrent()
        assertTrue(c.state.value.overlay is Overlay.Notice)
        assertTrue(c.question!!.inOverlay)
    }

    @Test
    fun `exit ferme la question`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = false)
        factory.send(portail(id = 1), TvCommand.Exit(2))
        runCurrent()
        assertNull(c.question)
    }

    @Test
    fun `etat - la question en superposition rend visible, sa fermeture non`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = false)
        factory.send(portail())
        advanceTimeBy(400)
        assertTrue(factory.states.last().visible)
        c.press(Back)
        advanceTimeBy(400)
        assertFalse(factory.states.last().visible)
    }
}
