package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.Back
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.driver.JeedomException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Images jointes aux questions et aux bandeaux : chargement en parallèle de l'affichage. */
class AppControllerImageTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")
    private val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    private fun TestScope.started(factory: FakeDriverFactory, visible: Boolean = false): AppController {
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

    private fun portail(id: Long = 1, ask: String = "j1", image: String? = "img-1") =
        TvCommand.Ask(id, ask, "", "On sonne au portail. Ouvrir ?", listOf("Ouvrir", "Ignorer"), 45, image)

    @Test
    fun `question - affichee aussitot, image posee des qu'elle est prete`() = runTest {
        val gate = CompletableDeferred<ByteArray>()
        val factory = FakeDriverFactory().apply { onImage = { gate.await() } }
        val c = started(factory)
        factory.send(portail())
        runCurrent()
        val question = c.state.value.question!!
        assertEquals("img-1", question.image)
        assertNull("pas encore téléchargée", question.imageBytes)
        assertEquals(listOf("img-1"), factory.images)

        gate.complete(photo)
        runCurrent()
        assertArrayEquals(photo, c.state.value.question!!.imageBytes)
    }

    @Test
    fun `question sans image - aucun telechargement`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(portail(image = null))
        runCurrent()
        assertNull(c.state.value.question!!.image)
        assertTrue(factory.images.isEmpty())
    }

    @Test
    fun `echec - pas d'image, pas de message`() = runTest {
        val factory = FakeDriverFactory().apply { onImage = { throw JeedomException("Image expirée", httpCode = 404) } }
        val c = started(factory, visible = true)
        factory.send(portail())
        runCurrent()
        val question = c.state.value.question!!
        assertNull(question.imageBytes)
        assertNull(c.state.value.notice)
    }

    @Test
    fun `question remplacee - l'ancienne image ne se pose pas sur la nouvelle`() = runTest {
        val first = CompletableDeferred<ByteArray>()
        val factory = FakeDriverFactory().apply { onImage = { id -> if (id == "img-1") first.await() else photo } }
        val c = started(factory)
        factory.send(portail(id = 1, ask = "a", image = "img-1"))
        runCurrent()
        factory.send(portail(id = 2, ask = "b", image = null))
        runCurrent()
        assertFalse("chargement annulé", first.isCompleted)
        first.complete(byteArrayOf(9))
        runCurrent()
        assertEquals("b", c.state.value.question!!.ask)
        assertNull(c.state.value.question!!.imageBytes)
    }

    @Test
    fun `question fermee - chargement annule`() = runTest {
        var cancelled = false
        val factory = FakeDriverFactory().apply {
            onImage = {
                try {
                    CompletableDeferred<ByteArray>().await()
                } finally {
                    cancelled = true
                }
            }
        }
        val c = started(factory)
        factory.send(portail())
        runCurrent()
        c.onCommand(Back)
        runCurrent()
        assertNull(c.state.value.question)
        assertTrue(cancelled)
    }

    @Test
    fun `bandeau en superposition - image posee, puis effacee avec lui`() = runTest {
        val factory = FakeDriverFactory().apply { onImage = { photo } }
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "Portier", "On sonne", image = "img-2"))
        runCurrent()
        val notice = c.state.value.overlay as Overlay.Notice
        assertEquals("img-2", notice.banner.image)
        assertArrayEquals(photo, notice.banner.imageBytes)
        advanceTimeBy(8_100)
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `bandeau dans l'application - image posee`() = runTest {
        val factory = FakeDriverFactory().apply { onImage = { photo } }
        val c = started(factory, visible = true)
        factory.send(TvCommand.Notify(1, "", "Colis livré", image = "img-3"))
        runCurrent()
        assertArrayEquals(photo, c.state.value.banner!!.imageBytes)
        advanceTimeBy(8_100)
        assertNull(c.state.value.banner)
    }

    @Test
    fun `bandeau remplace - chargement annule, nouvelle image`() = runTest {
        val slow = CompletableDeferred<ByteArray>()
        val factory = FakeDriverFactory().apply { onImage = { id -> if (id == "lent") slow.await() else photo } }
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "", "Premier", image = "lent"))
        runCurrent()
        factory.send(TvCommand.Notify(2, "", "Second", image = "rapide"))
        runCurrent()
        slow.complete(byteArrayOf(7))
        runCurrent()
        val banner = (c.state.value.overlay as Overlay.Notice).banner
        assertEquals("Second", banner.message)
        assertArrayEquals(photo, banner.imageBytes)
    }
}
