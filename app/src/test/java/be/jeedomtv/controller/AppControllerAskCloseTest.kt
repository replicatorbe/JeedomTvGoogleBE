package be.jeedomtv.controller

import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.QuestionStatus
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.VideoUrl
import be.jeedomtv.model.driver.JeedomException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Question à plusieurs TV : fermeture par `ask_close` (réponse donnée ailleurs) et réponse refusée en 409. */
class AppControllerAskCloseTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")
    private val camera = VideoUrl("rtsp://camera.example/flux")

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

    private fun sonnette(id: Long, ask: String = "j1") =
        TvCommand.Ask(id, ask, "Sonnette", "On sonne au portail. Ouvrir ?", listOf("Ignorer", "Ouvrir"), 45, video = camera)

    @Test
    fun `ask_close avec reponse - « Reponse donnee sur » environ 3 s, puis fermeture, video liberee aussitot`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(sonnette(1))
        runCurrent()
        assertEquals(QuestionStatus.Choosing, c.state.value.question?.status)

        factory.send(TvCommand.AskClose(2, "j1", answer = "Ouvrir", by = "TV salon"))
        runCurrent()
        val status = c.state.value.question?.status
        assertEquals(QuestionStatus.AnsweredElsewhere("Ouvrir", "TV salon"), status)
        // La vidéo ne joue qu'en cours de choix : le lecteur est rendu dès maintenant.
        assertNotEquals(QuestionStatus.Choosing, status)
        advanceTimeBy(2_900)
        assertTrue(c.state.value.question != null)
        advanceTimeBy(200)
        assertNull(c.state.value.question)
        assertTrue("rien n'est envoyé depuis cette TV", factory.answers.isEmpty())
    }

    @Test
    fun `ask_close sans reponse - fermeture immediate`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(sonnette(1))
        runCurrent()
        factory.send(TvCommand.AskClose(2, "j1"))
        runCurrent()
        assertNull(c.state.value.question)
    }

    @Test
    fun `ask_close d'un autre jeton - sans effet sur la question affichee`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(sonnette(1, ask = "j1"))
        runCurrent()
        factory.send(TvCommand.AskClose(2, "autre", answer = "Ouvrir", by = "TV chambre"))
        runCurrent()
        assertEquals("j1", c.state.value.question?.ask)
        assertEquals(QuestionStatus.Choosing, c.state.value.question?.status)
    }

    @Test
    fun `ask_close sans question affichee - sans effet`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.AskClose(1, "j1", answer = "Ouvrir"))
        runCurrent()
        assertNull(c.state.value.question)
    }

    @Test
    fun `ask_close avant la question, dans le meme lot - la question n'apparait pas`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.AskClose(1, "j1", answer = "Ouvrir", by = "TV salon"), sonnette(2))
        runCurrent()
        assertNull(c.state.value.question)
    }

    @Test
    fun `ask_close recu plus tot - la question arrivee ensuite n'apparait pas, une autre si`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.AskClose(1, "j1"))
        runCurrent()
        factory.send(sonnette(2, ask = "j1"))
        runCurrent()
        assertNull(c.state.value.question)
        factory.send(sonnette(3, ask = "j2"))
        runCurrent()
        assertEquals("j2", c.state.value.question?.ask)
    }

    @Test
    fun `reponse refusee en 409 - « Deja repondu » puis fermeture`() = runTest {
        val factory = FakeDriverFactory()
        factory.onAnswer = { _, _ -> throw JeedomException("Question déjà répondue", httpCode = 409) }
        val c = started(factory, visible = true)
        factory.send(sonnette(1))
        runCurrent()
        c.onCommand(RemoteCommand.Right)
        c.onCommand(RemoteCommand.Ok)
        runCurrent()
        assertEquals(QuestionStatus.AlreadyAnswered, c.state.value.question?.status)
        advanceTimeBy(2_100)
        assertNull(c.state.value.question)
    }

    @Test
    fun `autres erreurs inchangees - 404 question expiree`() = runTest {
        val factory = FakeDriverFactory()
        factory.onAnswer = { _, _ -> throw JeedomException("x", httpCode = 404) }
        val c = started(factory, visible = true)
        factory.send(sonnette(1))
        runCurrent()
        c.onCommand(RemoteCommand.Ok)
        runCurrent()
        assertEquals(QuestionStatus.Failed("Question expirée"), c.state.value.question?.status)
    }
}
