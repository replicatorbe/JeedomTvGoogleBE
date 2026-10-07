package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.Back
import be.jeedomtv.controller.RemoteCommand.ChannelUp
import be.jeedomtv.controller.RemoteCommand.Digit
import be.jeedomtv.controller.RemoteCommand.Down
import be.jeedomtv.controller.RemoteCommand.Left
import be.jeedomtv.controller.RemoteCommand.Ok
import be.jeedomtv.controller.RemoteCommand.Right
import be.jeedomtv.controller.RemoteCommand.Up
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Banner
import be.jeedomtv.model.Choice
import be.jeedomtv.model.ChoiceMode
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.Page
import be.jeedomtv.model.PendingAction
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TileIcon
import be.jeedomtv.model.TileType
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

/** Tuile `select` (mode de choix) et durée des messages (`notify.duration`). */
class AppControllerSelectTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    private val choices = listOf(Choice("auto", "Auto"), Choice("cold", "Froid"), Choice("heat", "Chauffage"))
    private val mode = Tile("t50", TileType.Select, "Salle à manger · Mode clim", TileIcon.Thermostat, value = "cold", choices = choices)
    private val source = Tile("t51", TileType.Select, "Source de chauffe", TileIcon.Generic, confirm = true, value = "x", choices = choices)
    private val empty = Tile("t52", TileType.Select, "Sans choix", value = "1")

    private fun layout(vararg tiles: Tile = arrayOf(mode, source, empty)) =
        Layout("r", listOf(Page("p1", "Clim", tiles.toList()), Page("p2", "Autre", listOf(Tile("i", TileType.Info, "Info")))))

    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(onLayout = { layout() }),
        visible: Boolean = true,
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

    private fun AppController.press(vararg commands: RemoteCommand) = commands.forEach { onCommand(it) }

    private val AppController.value get() = state.value.findTile("t50")?.value

    // --- Mode de choix -----------------------------------------------------------------------

    @Test
    fun `OK ouvre le mode de choix sur la valeur actuelle`() = runTest {
        val c = started()
        assertTrue(c.onCommand(Ok))
        assertEquals(ChoiceMode("t50", 1), c.state.value.choice)
        assertEquals(mode, c.state.value.choiceTile)
    }

    @Test
    fun `fleches parcourent la liste, bornees aux extremites`() = runTest {
        val c = started()
        c.press(Ok, Right)
        assertEquals(2, c.state.value.choice?.selected)
        c.press(Down, Right)
        assertEquals("dernier choix", 2, c.state.value.choice?.selected)
        c.press(Left, Up, Up, Left)
        assertEquals("premier choix", 0, c.state.value.choice?.selected)
        c.press(Down)
        assertEquals(1, c.state.value.choice?.selected)
    }

    @Test
    fun `OK envoie set avec la valeur choisie, mise a jour optimiste puis correction`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layout() })
        val reply = CompletableDeferred<String?>()
        factory.onExec = { reply.await() }
        val c = started(factory)
        c.press(Ok, Right, Ok)
        assertNull("mode de choix refermé", c.state.value.choice)
        assertEquals("optimiste", "heat", c.value)
        runCurrent()
        assertEquals(listOf(ExecCall("t50", TileAction.Set, null, "heat")), factory.execCalls)
        reply.complete("auto") // Jeedom corrige.
        runCurrent()
        assertEquals("auto", c.value)

        factory.pushChanges(changes("c1", "r", "t50" to "cold"))
        runCurrent()
        assertEquals("changes fait foi", "cold", c.value)
    }

    @Test
    fun `erreur - l'ancienne valeur revient et un message s'affiche`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layout() })
        factory.onExec = { throw JeedomException("Valeur refusée", httpCode = 422) }
        val c = started(factory)
        c.press(Ok, Left, Ok)
        runCurrent()
        assertEquals("cold", c.value)
        assertEquals("Valeur refusée", c.state.value.notice)
    }

    @Test
    fun `Retour annule sans rien envoyer`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layout() })
        val c = started(factory)
        c.press(Ok, Right)
        assertTrue(c.onCommand(Back))
        assertNull(c.state.value.choice)
        assertEquals("cold", c.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(factory.execCalls.isEmpty())
    }

    @Test
    fun `chiffres et CH+ gardes par le mode de choix`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layout() })
        val c = started(factory)
        c.press(Ok, Digit(2), ChannelUp)
        assertEquals(ChoiceMode("t50", 1), c.state.value.choice)
        assertEquals(0, c.state.value.pageIndex)
        assertEquals(0, c.state.value.focusedIndex)
        runCurrent()
        assertTrue(factory.execCalls.isEmpty())
    }

    @Test
    fun `chiffre N ouvre le choix de la tuile N, valeur hors liste - premier choix`() = runTest {
        val c = started()
        c.press(Digit(2))
        assertEquals(ChoiceMode("t51", 0), c.state.value.choice)
    }

    @Test
    fun `tuile sans choix - OK ne fait rien`() = runTest {
        val c = started()
        c.press(Digit(3))
        assertNull(c.state.value.choice)
    }

    @Test
    fun `avec confirmation - la boite resume le choix, OK confirme et envoie`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layout() })
        val c = started(factory)
        c.press(Digit(2), Right, Ok)
        assertNull(c.state.value.choice)
        assertEquals(
            PendingAction("t51", TileAction.Set, null, "Régler « Source de chauffe » sur « Froid »", choice = "cold"),
            c.state.value.confirm,
        )
        runCurrent()
        assertTrue("rien avant la confirmation", factory.execCalls.isEmpty())
        c.press(Ok)
        assertEquals("cold", c.state.value.findTile("t51")?.value)
        runCurrent()
        assertEquals(listOf(ExecCall("t51", TileAction.Set, null, "cold")), factory.execCalls)
    }

    @Test
    fun `layout recharge - mode garde et borne, abandonne si la tuile disparait`() = runTest {
        var current = layout()
        val factory = FakeDriverFactory(onLayout = { current })
        val c = started(factory)
        c.press(Ok, Right)
        current = layout(mode.copy(choices = choices.take(2)), source).copy(revision = "r2")
        factory.pushChanges(changes("c1", revision = "r2"))
        runCurrent()
        assertEquals(ChoiceMode("t50", 1), c.state.value.choice)

        current = layout(source).copy(revision = "r3")
        factory.pushChanges(changes("c2", revision = "r3"))
        runCurrent()
        assertNull(c.state.value.choice)
    }

    @Test
    fun `panneau en superposition - le mode de choix fonctionne, Retour le ferme sans fermer le panneau`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layout() })
        val c = started(factory, visible = false)
        factory.pushChanges(commands("c1", TvCommand.Show(1, "p1")))
        runCurrent()
        assertTrue(c.state.value.overlay is Overlay.Panel)
        c.press(Ok, Left)
        assertEquals(ChoiceMode("t50", 0), c.state.value.choice)
        c.press(Back)
        assertNull(c.state.value.choice)
        assertTrue("le panneau reste", c.state.value.overlay is Overlay.Panel)
        c.press(Ok, Left, Ok)
        runCurrent()
        assertEquals(listOf(ExecCall("t50", TileAction.Set, null, "auto")), factory.execCalls)
        c.press(Ok)
        c.press(Back, Back)
        assertEquals("Retour hors du mode ferme le panneau", Overlay.None, c.state.value.overlay)
        assertNull(c.state.value.choice)
    }

    @Test
    fun `touche de couleur - abandonne le mode de choix`() = runTest {
        val c = started()
        c.press(Ok)
        c.press(RemoteCommand.Color(be.jeedomtv.model.ColorKey.Red))
        assertNull(c.state.value.choice)
    }

    // --- Durée des messages ------------------------------------------------------------------

    @Test
    fun `notify avec duree - le bandeau de l'application reste duration secondes`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layout() })
        val c = started(factory)
        factory.pushChanges(commands("c1", TvCommand.Notify(1, "", "Long", durationSec = 30)))
        runCurrent()
        assertEquals(Banner("", "Long"), c.state.value.banner)
        advanceTimeBy(29_900)
        assertEquals(Banner("", "Long"), c.state.value.banner)
        advanceTimeBy(200)
        assertNull(c.state.value.banner)
    }

    @Test
    fun `notify avec duree - le bandeau en superposition aussi`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layout() })
        val c = started(factory, visible = false)
        factory.pushChanges(commands("c1", TvCommand.Notify(1, "", "Court", durationSec = 3)))
        runCurrent()
        assertTrue(c.state.value.overlay is Overlay.Notice)
        advanceTimeBy(2_900)
        assertTrue(c.state.value.overlay is Overlay.Notice)
        advanceTimeBy(200)
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `notify sans duree - duree par defaut d'environ 8 s`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layout() })
        val c = started(factory)
        factory.pushChanges(commands("c1", TvCommand.Notify(1, "", "Normal")))
        runCurrent()
        advanceTimeBy(7_900)
        assertEquals(Banner("", "Normal"), c.state.value.banner)
        advanceTimeBy(200)
        assertNull(c.state.value.banner)
    }
}
