package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.Back
import be.jeedomtv.controller.RemoteCommand.ChannelDown
import be.jeedomtv.controller.RemoteCommand.ChannelUp
import be.jeedomtv.controller.RemoteCommand.Left
import be.jeedomtv.controller.RemoteCommand.Menu
import be.jeedomtv.controller.RemoteCommand.Ok
import be.jeedomtv.controller.RemoteCommand.Right
import be.jeedomtv.controller.RemoteCommand.Up
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Board
import be.jeedomtv.model.BoardSection
import be.jeedomtv.model.Changes
import be.jeedomtv.model.ColorKey
import be.jeedomtv.model.FocusZone
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.Page
import be.jeedomtv.model.PageType
import be.jeedomtv.model.Screen
import be.jeedomtv.model.Train
import be.jeedomtv.model.TrainStatus
import be.jeedomtv.model.TvCommand
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
 * Tableau des trains (page `board`) et pages cachées : absentes des onglets et de la navigation,
 * dans l'application comme dans le panneau ; ouvertes seulement par `show` ou une touche qui les vise.
 */
class AppControllerBoardTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    private fun board(vararg times: String, updated: String = "07:12") = Board(
        listOf(BoardSection("b1", "Soignies → Bruxelles", updated = updated, trains = times.map { Train(it, vehicle = "IC 1706") }))
    )

    /** Salon, la page Trains cachée glissée en deuxième position, puis Cuisine et Garage. */
    private fun boardLayout(keys: Map<ColorKey, String>? = null) = contractLayout().let { base ->
        base.copy(
            pages = listOf(
                base.pages[0],
                Page("p7", "Trains", emptyList(), PageType.Board, hidden = true, board = board("07:09")),
                base.pages[1],
                base.pages[2],
            ),
            keys = keys,
        )
    }

    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(onLayout = { boardLayout() }),
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
        assertEquals(Screen.Pages, c.state.value.screen)
        return c
    }

    private fun AppController.press(vararg commands: RemoteCommand) = commands.forEach { onCommand(it) }

    private fun FakeDriverFactory.send(vararg commands: TvCommand) {
        pushChanges(commands("c${changesCalls.size}", *commands))
    }

    private val AppController.page get() = state.value.currentPage?.id
    private val AppController.tabs get() = state.value.tabPages.map { it.value.id }

    /** Pages parcourues par [command] répétée, depuis la page affichée. */
    private fun AppController.visited(command: RemoteCommand, times: Int = 6): List<String?> =
        (1..times).map { onCommand(command); page }

    // --- Pages cachées : jamais par la navigation -------------------------------------------

    @Test
    fun `app - page cachee absente des onglets, de CH+ CH- et des fleches dans les onglets`() = runTest {
        val c = started(visible = true)
        assertEquals("page d'accueil", "p1", c.page)
        assertEquals(listOf("p1", "p2", "p3"), c.tabs)
        assertEquals(listOf("p2", "p3", "p1", "p2", "p3", "p1"), c.visited(ChannelUp))
        assertEquals(listOf("p3", "p2", "p1", "p3", "p2", "p1"), c.visited(ChannelDown))
        c.press(Up)
        assertEquals(FocusZone.Tabs, c.state.value.focusZone)
        assertEquals(listOf("p2", "p3", "p1", "p2"), c.visited(Right, 4))
        assertEquals(listOf("p1", "p3", "p2", "p1"), c.visited(Left, 4))
        assertTrue(c.state.value.pages.none { it.hidden && it.id == c.page })
    }

    @Test
    fun `panneau - page cachee jamais affichee par les touches de couleur ni la navigation`() = runTest {
        val c = started(visible = false)
        // Sans `keys`, la touche rouge ouvre la première page non cachée.
        assertTrue(c.onCommand(RemoteCommand.Color(ColorKey.Red)))
        assertTrue(c.state.value.overlay is Overlay.Panel)
        assertEquals("p1", c.page)
        assertEquals(listOf("p1", "p2", "p3"), c.tabs)
        assertEquals(listOf("p2", "p3", "p1", "p2", "p3", "p1"), c.visited(ChannelUp))
        c.press(Up)
        assertEquals(FocusZone.Tabs, c.state.value.focusZone)
        assertEquals(listOf("p2", "p3", "p1", "p2"), c.visited(Right, 4))
        assertEquals(listOf("p1", "p3", "p2", "p1"), c.visited(Left, 4))
        // Les autres couleurs, sans `keys`, restent inactives.
        assertFalse(c.onCommand(RemoteCommand.Color(ColorKey.Blue)))
        assertTrue(c.state.value.overlay is Overlay.Panel)
        assertTrue(c.state.value.currentBoard == null)
    }

    @Test
    fun `page cachee en premier - jamais page d'arrivee, ni dans l'application ni dans le panneau`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout().let { it.copy(pages = listOf(it.pages[1]) + (it.pages - it.pages[1])) } })
        val c = started(factory, visible = false)
        assertEquals(listOf("p7", "p1", "p2", "p3"), c.state.value.pages.map { it.id })
        assertEquals("p1", c.page)
        c.press(RemoteCommand.Color(ColorKey.Red))
        assertEquals("p1", c.page)
        c.press(Back)
        c.onUiVisibilityChanged(true)
        assertEquals("p1", c.page)
        assertEquals(listOf("p3", "p2", "p1", "p3"), c.visited(ChannelDown, 4))
    }

    // --- Ouverture explicite : show et keys --------------------------------------------------

    @Test
    fun `app visible - show ouvre le tableau, Retour ramene a la page d'avant`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = true)
        c.press(ChannelUp, Right)
        assertEquals("p2", c.page)
        factory.send(TvCommand.Show(1, "p7", durationSec = 0))
        runCurrent()
        assertEquals("p7", c.page)
        assertTrue(c.state.value.currentBoard != null)
        assertEquals("le tableau en onglet tant qu'il est affiché", listOf("p1", "p7", "p2", "p3"), c.tabs)
        // Aucune action : les autres touches sont sans effet.
        c.press(Ok, Right, ChannelUp, Up, Menu)
        assertEquals("p7", c.page)
        assertEquals(Screen.Pages, c.state.value.screen)
        assertTrue(c.onCommand(Back))
        assertEquals("p2", c.page)
        assertEquals(1, c.state.value.focusedIndex)
        assertFalse(c.state.value.exitRequested)
        // Retour suivant : comportement habituel (quitter).
        assertFalse(c.onCommand(Back))
    }

    @Test
    fun `app cachee sans permission - show au premier plan, Retour rend l'application d'avant`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = false, permission = false)
        factory.send(TvCommand.Show(1, "p7", durationSec = 0))
        runCurrent()
        assertTrue(c.state.value.foregroundRequested)
        c.onUiVisibilityChanged(true)
        assertEquals("p7", c.page)
        assertTrue(c.onCommand(Back))
        assertTrue("retour à l'application d'avant", c.state.value.exitRequested)
        assertEquals("p1", c.page)
    }

    @Test
    fun `app visible - show avec duree, une touche garde le tableau jusqu'a Retour`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "p7", durationSec = 30))
        runCurrent()
        assertEquals("p7", c.page)
        c.press(Ok)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("p7", c.page)
        c.press(Back)
        assertEquals("p1", c.page)
    }

    @Test
    fun `app visible - show avec duree, retour seul a l'ecran d'avant`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = true)
        c.press(ChannelUp)
        factory.send(TvCommand.Show(1, "p7", durationSec = 30))
        runCurrent()
        assertEquals("p7", c.page)
        advanceTimeBy(30_100)
        runCurrent()
        assertEquals("p2", c.page)
    }

    @Test
    fun `app cachee avec permission - show ouvre le tableau en superposition, Retour le ferme`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = false)
        factory.send(TvCommand.Show(1, "p7", durationSec = 0))
        runCurrent()
        assertEquals(Overlay.Panel("p7", 0), c.state.value.overlay)
        assertEquals("p7", c.page)
        assertFalse("la vidéo reste devant", c.state.value.foregroundRequested)
        c.press(Ok, Right, Menu, ChannelUp)
        assertEquals("p7", c.page)
        assertTrue(c.state.value.overlay is Overlay.Panel)
        assertTrue(c.onCommand(Back))
        assertEquals(Overlay.None, c.state.value.overlay)
        assertEquals("p1", c.page)
    }

    @Test
    fun `superposition - le tableau reste cinq minutes sans touche`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = false)
        factory.send(TvCommand.Show(1, "p7", durationSec = 0))
        runCurrent()
        advanceTimeBy(4 * 60_000L)
        runCurrent()
        assertTrue(c.state.value.overlay is Overlay.Panel)
        advanceTimeBy(60_100)
        runCurrent()
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `touche de couleur visant explicitement la page cachee - app et panneau`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout(keys = mapOf(ColorKey.Yellow to "p7", ColorKey.Red to "p1")) })
        val c = started(factory, visible = false)
        c.press(RemoteCommand.Color(ColorKey.Yellow))
        assertEquals(Overlay.Panel("p7", 0), c.state.value.overlay)
        assertEquals("p7", c.page)
        // La même touche le referme.
        c.press(RemoteCommand.Color(ColorKey.Yellow))
        assertEquals(Overlay.None, c.state.value.overlay)

        c.onUiVisibilityChanged(true)
        c.press(ChannelUp)
        assertEquals("p2", c.page)
        c.press(RemoteCommand.Color(ColorKey.Yellow))
        assertEquals("p7", c.page)
        c.press(Back)
        assertEquals("p2", c.page)
    }

    @Test
    fun `Accueil sur le tableau - la page d'avant revient, pas le tableau`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = true)
        c.press(ChannelUp)
        factory.send(TvCommand.Show(1, "p7", durationSec = 0))
        runCurrent()
        assertEquals("p7", c.page)
        c.onUiVisibilityChanged(false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("pas tout de suite : l'activité est peut-être recréée", "p7", c.page)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals("p2", c.page)
        // Puis un panneau rouge (sans keys) : jamais le tableau.
        c.press(RemoteCommand.Color(ColorKey.Red))
        assertEquals("p1", c.page)
    }

    @Test
    fun `activite recreee sur le tableau - il reste affiche`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "p7", durationSec = 0))
        runCurrent()
        c.onUiVisibilityChanged(false)
        advanceTimeBy(500)
        c.onUiVisibilityChanged(true)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("p7", c.page)
        c.press(Back)
        assertEquals("p1", c.page)
    }

    @Test
    fun `panneau ouvert depuis l'application quittee sur le tableau - a sa fermeture, plus de tableau`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "p7", durationSec = 0))
        runCurrent()
        c.onUiVisibilityChanged(false)
        c.press(RemoteCommand.Color(ColorKey.Red))
        assertEquals("p1", c.page)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("le panneau garde sa page", "p1", c.page)
        c.press(Back)
        assertEquals(Overlay.None, c.state.value.overlay)
        assertEquals("p1", c.page)
        c.onUiVisibilityChanged(true)
        assertEquals("p1", c.page)
    }

    // --- Mises à jour en direct --------------------------------------------------------------

    @Test
    fun `changes boards remplace le tableau affiche, absent ne change rien`() = runTest {
        val factory = FakeDriverFactory(onLayout = { boardLayout() })
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "p7", durationSec = 0))
        runCurrent()
        val updated = board("07:38", "07:53", updated = "07:13")
        factory.pushChanges(Changes("c2", "9f2c1a", emptyList(), boards = mapOf("p7" to updated, "p1" to updated, "inconnue" to updated)))
        runCurrent()
        assertEquals(updated, c.state.value.currentBoard)
        assertNull("une page de tuiles n'a pas de tableau", c.state.value.pages[0].board)
        assertEquals("p7", c.page)
        factory.pushChanges(Changes("c3", "9f2c1a", emptyList()))
        runCurrent()
        assertEquals(updated, c.state.value.currentBoard)
    }

    @Test
    fun `withBoards - seulement les pages board, l'etat est garde s'il n'y a rien a faire`() {
        val state = AppState(pages = boardLayout().pages)
        assertTrue(state.withBoards(mapOf("p1" to Board())) === state)
        val canceled = Board(listOf(BoardSection("b1", "T", trains = listOf(Train("07:38", status = TrainStatus.Canceled)))))
        assertEquals(canceled, state.withBoards(mapOf("p7" to canceled)).pages[1].board)
    }

    @Test
    fun `nouveau layout - le tableau affiche reste affiche`() = runTest {
        var layout = boardLayout()
        val factory = FakeDriverFactory(onLayout = { layout })
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "p7", durationSec = 0))
        runCurrent()
        layout = boardLayout().copy(revision = "r2")
        factory.pushChanges(changes("c2", "r2"))
        runCurrent()
        assertEquals("r2", c.state.value.revision)
        assertEquals("p7", c.page)
    }

    @Test
    fun `layout sans page board - Layout de l'exemple inchange`() {
        val state = AppState().withLayout(Layout("r", contractLayout().pages))
        assertEquals("p1", state.currentPage?.id)
        assertEquals(listOf("p1", "p2", "p3"), state.tabPages.map { it.value.id })
    }
}
