package be.jeedomtv.controller

import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Banner
import be.jeedomtv.model.Changes
import be.jeedomtv.model.Corner
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.QuestionStatus
import be.jeedomtv.model.StatusBar
import be.jeedomtv.model.StatusItem
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.VideoUrl
import be.jeedomtv.view.bannerVideoAllowed
import be.jeedomtv.view.questionWindowKind
import be.jeedomtv.view.QuestionWindowKind
import be.jeedomtv.view.statusWindowKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Barre d'état, notifications riches (`tag`, `dismiss`, vidéo) et question avec vidéo. */
class AppControllerRichTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")
    private val camera = VideoUrl("rtsp://camera.example/flux")

    private val status = StatusBar(
        corner = Corner.BottomStart,
        clock = true,
        opacity = 85,
        items = listOf(StatusItem("meteo", "mdi:weather-rainy", "18°")),
    )

    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(),
        visible: Boolean = false,
    ): AppController {
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

    private fun FakeDriverFactory.pushStatus(status: StatusBar?) {
        pushChanges(Changes("s${changesCalls.size}", "9f2c1a", emptyList(), statusChanged = true, status = status))
    }

    // --- Barre d'état --------------------------------------------------------------------------

    @Test
    fun `barre d'etat - chargee avec le layout, visible dans son coin`() = runTest {
        val c = started(FakeDriverFactory(onLayout = { contractLayout().copy(status = status) }))
        assertEquals(status, c.state.value.status)
        assertEquals(Corner.BottomStart, statusWindowKind(c.state.value))
    }

    @Test
    fun `barre d'etat - changes la remplace en entier, null la retire, absent la garde`() = runTest {
        val factory = FakeDriverFactory(onLayout = { contractLayout().copy(status = status) })
        val c = started(factory)
        val other = StatusBar(corner = Corner.TopEnd, clock = false, items = listOf(StatusItem("porte", "mdi:lock-open-variant")))
        factory.pushStatus(other)
        runCurrent()
        assertEquals(other, c.state.value.status)

        factory.pushChanges(changes("c9"))
        runCurrent()
        assertEquals("sans status : inchangée", other, c.state.value.status)

        factory.pushStatus(null)
        runCurrent()
        assertNull(c.state.value.status)
        assertNull(statusWindowKind(c.state.value))
    }

    @Test
    fun `barre d'etat - ni changement de revision ni rechargement du layout`() = runTest {
        val factory = FakeDriverFactory(onLayout = { contractLayout().copy(status = status) })
        val c = started(factory)
        val layouts = factory.layoutCount
        factory.pushStatus(status.copy(opacity = 50))
        runCurrent()
        assertEquals(layouts, factory.layoutCount)
        assertEquals(50, c.state.value.status?.opacity)
    }

    @Test
    fun `barre d'etat - masquee pendant notre ecran de veille, ecran eteint ou opacite nulle`() = runTest {
        val c = started(FakeDriverFactory(onLayout = { contractLayout().copy(status = status) }))
        c.onDreamingChanged(true)
        assertNull(statusWindowKind(c.state.value))
        c.onDreamingChanged(false)
        assertEquals(Corner.BottomStart, statusWindowKind(c.state.value))
        c.onScreenChanged(false)
        assertNull(statusWindowKind(c.state.value))
        assertNull(statusWindowKind(AppState(status = status.copy(opacity = 0))))
        assertNull(
            "écran des pages affiché : dessinée dans l'application, pas en fenêtre",
            statusWindowKind(AppState(status = status, uiVisible = true, screen = be.jeedomtv.model.Screen.Pages)),
        )
        assertNull(
            "configuration ou chargement affichés : pas de fenêtre par-dessus le formulaire",
            statusWindowKind(AppState(status = status, uiVisible = true, screen = be.jeedomtv.model.Screen.Setup)),
        )
        assertNull(statusWindowKind(AppState(status = status, uiVisible = true, screen = be.jeedomtv.model.Screen.Loading)))
    }

    // --- Notifications riches --------------------------------------------------------------------

    @Test
    fun `notify - les champs riches passent au bandeau en superposition`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "Sonnette", "On sonne", tag = "sonnette", icon = "mdi:doorbell", iconColor = -1, corner = Corner.BottomEnd, video = camera))
        runCurrent()
        val banner = (c.state.value.overlay as Overlay.Notice).banner
        assertEquals(Banner("Sonnette", "On sonne", tag = "sonnette", icon = "mdi:doorbell", iconColor = -1, corner = Corner.BottomEnd, video = camera, durationMs = 8_000), banner.sansIdentite())
    }

    @Test
    fun `notify - meme tag, le nouveau remplace l'ancien et relance sa duree`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "", "Premier", tag = "portail", durationSec = 10))
        runCurrent()
        advanceTimeBy(8_000)
        factory.send(TvCommand.Notify(2, "", "Second", tag = "portail", durationSec = 10))
        runCurrent()
        assertEquals("Second", (c.state.value.overlay as Overlay.Notice).banner.message)
        advanceTimeBy(9_000)
        assertTrue("durée du nouveau bandeau", c.state.value.overlay is Overlay.Notice)
        advanceTimeBy(1_100)
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `dismiss - retire le bandeau en superposition de ce tag, pas un autre`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "", "Portail", tag = "portail", durationSec = 60))
        runCurrent()
        factory.send(TvCommand.Dismiss(2, "sonnette"))
        runCurrent()
        assertTrue("autre tag : sans effet", c.state.value.overlay is Overlay.Notice)
        factory.send(TvCommand.Dismiss(3, "portail"))
        runCurrent()
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `dismiss - retire le bandeau de l'application, sans effet s'il n'est plus affiche`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(TvCommand.Notify(1, "", "Lave-linge", tag = "linge", durationSec = 60))
        runCurrent()
        assertEquals("linge", c.state.value.banner?.tag)
        factory.send(TvCommand.Dismiss(2, "linge"))
        runCurrent()
        assertNull(c.state.value.banner)
        factory.send(TvCommand.Dismiss(3, "linge"))
        runCurrent()
        assertNull(c.state.value.banner)
        advanceTimeBy(70_000)
        assertNull(c.state.value.banner)
    }

    @Test
    fun `notify sans video - bandeau sans video, video autorisee hors question`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(TvCommand.Notify(1, "", "Simple"))
        runCurrent()
        assertNull(c.state.value.banner?.video)
        assertTrue(bannerVideoAllowed(c.state.value))
    }

    // --- Question avec vidéo -------------------------------------------------------------------

    @Test
    fun `question avec video - grande mise en page, elle garde le seul decodeur`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "", "Sonnette", video = camera, durationSec = 60))
        factory.send(TvCommand.Ask(2, "j", "", "Ouvrir ?", listOf("Ignorer", "Ouvrir"), 30, video = camera))
        runCurrent()
        val question = c.state.value.question!!
        assertEquals(camera, question.video)
        assertEquals(QuestionWindowKind.Dialog, questionWindowKind(c.state.value))
        assertFalse("le bandeau montre son image, pas la vidéo", bannerVideoAllowed(c.state.value))
    }

    @Test
    fun `question avec video - apres la reponse, plus de lecture (statut envoye)`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(TvCommand.Ask(1, "j", "", "Ouvrir ?", listOf("Ignorer", "Ouvrir"), 30, video = camera))
        runCurrent()
        c.onCommand(RemoteCommand.Ok)
        assertTrue(c.state.value.question?.status != QuestionStatus.Choosing)
        runCurrent()
        assertEquals(listOf("j" to "Ignorer"), factory.answers)
    }

    @Test
    fun `question sans image ni video - bandeau compact comme avant`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Ask(1, "j", "", "Ouvrir ?", listOf("Oui"), 30))
        runCurrent()
        assertEquals(QuestionWindowKind.Banner, questionWindowKind(c.state.value))
        assertTrue(bannerVideoAllowed(c.state.value))
    }
}
