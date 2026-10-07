package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.Back
import be.jeedomtv.controller.RemoteCommand.ChannelUp
import be.jeedomtv.controller.RemoteCommand.Digit
import be.jeedomtv.controller.RemoteCommand.Down
import be.jeedomtv.controller.RemoteCommand.Menu
import be.jeedomtv.controller.RemoteCommand.Ok
import be.jeedomtv.controller.RemoteCommand.Right
import be.jeedomtv.controller.RemoteCommand.Up
import be.jeedomtv.model.Adjust
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Banner
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.Screen
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.TvState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Superposition par-dessus la vidéo : bandeau, panneau, ou activité selon visibilité et permission. */
class AppControllerOverlayTest {

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
        )
        c.start()
        runCurrent()
        return c
    }

    private fun AppController.press(vararg commands: RemoteCommand) = commands.forEach { onCommand(it) }

    private fun FakeDriverFactory.send(vararg commands: TvCommand) {
        pushChanges(commands("c${changesCalls.size}", *commands))
    }

    private val AppController.overlay get() = state.value.overlay

    // --- Choix : bandeau, panneau ou activité -------------------------------------------------

    @Test
    fun `cachee avec permission - notify devient un bandeau en superposition de 8 s`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "Sonnette", "Quelqu'un sonne"))
        runCurrent()
        assertEquals(Overlay.Notice(Banner("Sonnette", "Quelqu'un sonne", durationMs = 8_000)), c.overlay)
        assertNull("pas le bandeau de l'application", c.state.value.banner)
        assertFalse("la vidéo reste devant", c.state.value.foregroundRequested)
        advanceTimeBy(7_900)
        assertTrue(c.overlay is Overlay.Notice)
        advanceTimeBy(200)
        assertEquals(Overlay.None, c.overlay)
    }

    @Test
    fun `cachee avec permission - show ouvre le panneau sans passer au premier plan`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p2", durationSec = 30))
        runCurrent()
        val state = c.state.value
        assertEquals(Overlay.Panel("p2", 30), state.overlay)
        assertEquals(1, state.pageIndex)
        assertEquals(0, state.focusedIndex)
        assertFalse(state.foregroundRequested)
        assertFalse(state.uiVisible)
    }

    @Test
    fun `visible - comportement inchange, pas de superposition`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "p2"), TvCommand.Notify(2, "", "Bonjour"))
        runCurrent()
        assertEquals(Overlay.None, c.overlay)
        assertEquals(1, c.state.value.pageIndex)
        assertEquals(Banner("", "Bonjour", durationMs = 8_000), c.state.value.banner)
    }

    @Test
    fun `sans permission - repli sur l'ouverture de l'activite, notify ignore`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, permission = false)
        factory.send(TvCommand.Notify(1, "", "Ignoré"))
        runCurrent()
        assertEquals(Overlay.None, c.overlay)
        assertNull(c.state.value.banner)

        factory.send(TvCommand.Show(2, "p2"))
        runCurrent()
        assertEquals(Overlay.None, c.overlay)
        assertTrue(c.state.value.foregroundRequested)
    }

    // --- Panneau : télécommande ---------------------------------------------------------------

    @Test
    fun `panneau - navigation, chiffres, CH+ et ordres comme sur les pages`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p1"))
        runCurrent()
        assertTrue(c.onCommand(Right))
        assertEquals(1, c.state.value.focusedIndex)
        c.press(Digit(1))
        runCurrent()
        assertEquals(listOf(ExecCall("t1", TileAction.Toggle)), factory.execCalls)
        c.press(ChannelUp)
        assertEquals(1, c.state.value.pageIndex)
        assertTrue(c.overlay is Overlay.Panel)
    }

    @Test
    fun `panneau - mode reglage et confirmation, Retour les annule avant de fermer`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p1"))
        runCurrent()
        c.press(Digit(4), Up)
        assertEquals(Adjust("t4", 21.0), c.state.value.adjust)
        c.press(Ok)
        runCurrent()
        assertEquals(listOf(ExecCall("t4", TileAction.Set, 21.0)), factory.execCalls)

        c.press(Digit(6))
        assertTrue(c.state.value.confirm != null)
        assertTrue(c.onCommand(Back))
        assertNull(c.state.value.confirm)
        assertTrue("Retour a d'abord annulé la confirmation", c.overlay is Overlay.Panel)

        c.press(Digit(4))
        c.press(Back)
        assertNull(c.state.value.adjust)
        assertTrue(c.overlay is Overlay.Panel)
    }

    @Test
    fun `panneau - Retour ferme et rend la page d'avant a l'application`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p3"))
        runCurrent()
        assertEquals(2, c.state.value.pageIndex)
        assertTrue("touche consommée : la vidéo ne la reçoit pas", c.onCommand(Back))
        assertEquals(Overlay.None, c.overlay)
        assertEquals(0, c.state.value.pageIndex)
        assertFalse(c.state.value.foregroundRequested)
    }

    @Test
    fun `panneau - Menu ouvre l'application complete sur la meme page`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p2"))
        runCurrent()
        c.press(Menu)
        val state = c.state.value
        assertEquals(Overlay.None, state.overlay)
        assertEquals(Screen.Pages, state.screen)
        assertEquals(1, state.pageIndex)
        assertTrue(state.foregroundRequested)
        c.onUiVisibilityChanged(true)
        assertFalse(c.state.value.foregroundRequested)
    }

    // --- Panneau : fermeture ------------------------------------------------------------------

    @Test
    fun `panneau - fermeture apres duration`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p2", durationSec = 10))
        runCurrent()
        advanceTimeBy(9_900)
        assertTrue(c.overlay is Overlay.Panel)
        advanceTimeBy(200)
        assertEquals(Overlay.None, c.overlay)
        assertEquals(0, c.state.value.pageIndex)
    }

    @Test
    fun `panneau - une touche annule la duree, puis une minute d'inactivite le ferme`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p1", durationSec = 10))
        runCurrent()
        advanceTimeBy(5_000)
        c.press(Right)
        advanceTimeBy(30_000)
        assertTrue("la durée ne s'applique plus", c.overlay is Overlay.Panel)
        c.press(Down)
        advanceTimeBy(59_900)
        assertTrue(c.overlay is Overlay.Panel)
        advanceTimeBy(200)
        assertEquals(Overlay.None, c.overlay)
    }

    @Test
    fun `panneau sans duree - ferme apres 60 s sans touche`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p1", durationSec = 0))
        runCurrent()
        advanceTimeBy(59_900)
        assertTrue(c.overlay is Overlay.Panel)
        advanceTimeBy(200)
        assertEquals(Overlay.None, c.overlay)
    }

    @Test
    fun `exit ferme le panneau ou le bandeau`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p2", durationSec = 30))
        runCurrent()
        c.press(Digit(1))
        factory.send(TvCommand.Exit(2))
        runCurrent()
        assertEquals(Overlay.None, c.overlay)
        assertEquals(0, c.state.value.pageIndex)
        assertFalse("rien à passer en arrière-plan", c.state.value.exitRequested)

        factory.send(TvCommand.Notify(3, "", "Bandeau"))
        factory.send(TvCommand.Exit(4))
        runCurrent()
        assertEquals(Overlay.None, c.overlay)
    }

    @Test
    fun `un nouvel ordre remplace la superposition courante`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "", "Bandeau"))
        runCurrent()
        factory.send(TvCommand.Show(2, "p2", durationSec = 30))
        runCurrent()
        assertEquals(Overlay.Panel("p2", 30), c.overlay)

        factory.send(TvCommand.Show(3, "p3", durationSec = 10))
        runCurrent()
        assertEquals(Overlay.Panel("p3", 10), c.overlay)
        assertEquals(2, c.state.value.pageIndex)
        advanceTimeBy(10_100)
        assertEquals(Overlay.None, c.overlay)
        assertEquals("page d'avant le premier panneau", 0, c.state.value.pageIndex)
    }

    @Test
    fun `notify pendant le panneau - bandeau dans le panneau, panneau garde`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p2"), TvCommand.Notify(2, "Sonnette", "Porte"))
        runCurrent()
        assertTrue(c.overlay is Overlay.Panel)
        assertEquals(Banner("Sonnette", "Porte", durationMs = 8_000), c.state.value.banner)
    }

    @Test
    fun `l'application affichee ferme la superposition`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p2"))
        runCurrent()
        c.onUiVisibilityChanged(true)
        assertEquals(Overlay.None, c.overlay)
    }

    // --- État signalé ---------------------------------------------------------------------------

    @Test
    fun `etat - visible et page du panneau, puis cache a sa fermeture`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        advanceTimeBy(400)
        assertEquals(TvState(visible = false, screenOn = true, page = "p1"), factory.states.last())

        factory.send(TvCommand.Show(1, "p3"))
        advanceTimeBy(400)
        assertEquals(TvState(visible = true, screenOn = true, page = "p3"), factory.states.last())

        c.press(Back)
        advanceTimeBy(400)
        assertEquals(TvState(visible = false, screenOn = true, page = "p1"), factory.states.last())
    }

    @Test
    fun `etat - le bandeau ne rend pas l'application visible`() = runTest {
        val factory = FakeDriverFactory()
        started(factory)
        advanceTimeBy(400)
        val before = factory.states.size
        factory.send(TvCommand.Notify(1, "", "Bandeau"))
        advanceTimeBy(400)
        assertTrue(factory.states.drop(before).none { it.visible })
    }
}
