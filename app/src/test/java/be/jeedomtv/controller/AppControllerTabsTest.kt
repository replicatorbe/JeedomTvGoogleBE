package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.Back
import be.jeedomtv.controller.RemoteCommand.ChannelDown
import be.jeedomtv.controller.RemoteCommand.ChannelUp
import be.jeedomtv.controller.RemoteCommand.Digit
import be.jeedomtv.controller.RemoteCommand.Down
import be.jeedomtv.controller.RemoteCommand.Left
import be.jeedomtv.controller.RemoteCommand.Ok
import be.jeedomtv.controller.RemoteCommand.Right
import be.jeedomtv.controller.RemoteCommand.Up
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.ColorKey
import be.jeedomtv.model.FocusZone
import be.jeedomtv.model.FocusZone.Tabs
import be.jeedomtv.model.FocusZone.Tiles
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.Page
import be.jeedomtv.model.Screen
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TvCommand
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation dans les onglets aux flèches (télécommandes sans CH+ / CH-) : même contrôleur pour
 * l'écran des pages et le panneau en superposition. Pages de l'exemple : Salon (6 tuiles),
 * Cuisine (2), Garage (1), et une page vide ajoutée par certains tests.
 */
class AppControllerTabsTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(),
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
        assertEquals(Screen.Pages, c.state.value.screen)
        return c
    }

    /** Panneau ouvert par un ordre `show` sur la page [page], application cachée. */
    private fun TestScope.panel(factory: FakeDriverFactory = FakeDriverFactory(), page: String = "p1"): AppController {
        val c = started(factory, visible = false)
        factory.pushChanges(commands("c1", TvCommand.Show(1, page, durationSec = 40)))
        runCurrent()
        assertTrue(c.state.value.overlay is Overlay.Panel)
        return c
    }

    private fun AppController.press(vararg commands: RemoteCommand) = commands.forEach { onCommand(it) }

    private val AppController.zone: FocusZone get() = state.value.focusZone
    private val AppController.page: Int get() = state.value.pageIndex

    private fun withEmptyPage() = FakeDriverFactory(onLayout = {
        contractLayout().let { Layout(it.revision, it.pages + Page("p4", "Vide", emptyList())) }
    })

    // --- Montée et descente --------------------------------------------------------------------

    @Test
    fun `haut sur la premiere rangee - le focus passe aux onglets, sur la page courante`() = runTest {
        val c = started()
        c.press(Right, Right)
        assertTrue(c.onCommand(Up))
        assertEquals(Tabs, c.zone)
        assertEquals("page inchangée", 0, c.page)
        assertTrue("la touche est consommée", c.onCommand(Up))
        assertEquals(Tabs, c.zone)
    }

    @Test
    fun `haut depuis la deuxieme rangee - remonte d'une rangee, reste dans les tuiles`() = runTest {
        val c = started()
        c.press(Down)
        assertEquals(4, c.state.value.focusedIndex)
        c.press(Up)
        assertEquals(Tiles, c.zone)
        assertEquals(0, c.state.value.focusedIndex)
    }

    @Test
    fun `gauche et droite dans les onglets - page voisine affichee aussitot, en boucle`() = runTest {
        val c = started()
        c.press(Up, Right)
        assertEquals(1, c.page)
        assertEquals(Tabs, c.zone)
        c.press(Right)
        assertEquals(2, c.page)
        c.press(Right)
        assertEquals("boucle vers la première page", 0, c.page)
        c.press(Left)
        assertEquals("boucle vers la dernière page", 2, c.page)
        assertEquals(Tabs, c.zone)
    }

    @Test
    fun `bas ou OK dans les onglets - premiere tuile de la page affichee, sans rien actionner`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        c.press(Right, Up)
        c.press(Down)
        assertEquals(Tiles, c.zone)
        assertEquals("première tuile, pas la tuile d'avant", 0, c.state.value.focusedIndex)

        c.press(Up, Right, Ok)
        assertEquals(Tiles, c.zone)
        assertEquals(1, c.page)
        assertEquals(0, c.state.value.focusedIndex)
        runCurrent()
        assertTrue("OK dans les onglets n'actionne aucune tuile", factory.execCalls.isEmpty())
    }

    @Test
    fun `Retour dans les onglets - retour aux tuiles, l'application ne quitte pas`() = runTest {
        val c = started()
        c.press(Right, Up)
        assertTrue("touche consommée : pas de sortie de l'application", c.onCommand(Back))
        assertEquals(Tiles, c.zone)
        assertEquals("tuile d'avant retrouvée", 1, c.state.value.focusedIndex)
        assertFalse("Retour depuis les tuiles : comportement habituel (quitter)", c.onCommand(Back))
    }

    // --- Chiffres, CH+ / CH-, couleurs ----------------------------------------------------------

    @Test
    fun `chiffre depuis les onglets - redescend sur la tuile N et l'actionne`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        c.press(Up, Right) // Cuisine : k1, k2.
        c.press(Digit(2))
        assertEquals(Tiles, c.zone)
        assertEquals(1, c.state.value.focusedIndex)
        runCurrent()
        assertEquals(listOf(ExecCall("k2", TileAction.Toggle)), factory.execCalls)
    }

    @Test
    fun `chiffre sans tuile correspondante - le focus reste dans les onglets`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        c.press(Up, Right, Digit(7))
        assertEquals(Tabs, c.zone)
        runCurrent()
        assertTrue(factory.execCalls.isEmpty())
    }

    @Test
    fun `CH+ et CH- depuis les onglets - page voisine et retour aux tuiles`() = runTest {
        val c = started()
        c.press(Up, ChannelUp)
        assertEquals(1, c.page)
        assertEquals(Tiles, c.zone)
        c.press(Up, ChannelDown)
        assertEquals(0, c.page)
        assertEquals(Tiles, c.zone)
    }

    @Test
    fun `touche de couleur depuis les onglets - page associee et retour aux tuiles`() = runTest {
        val c = started()
        c.press(Up)
        assertTrue(c.onCommand(RemoteCommand.Color(ColorKey.Red))) // Sans keys : première page.
        assertEquals(0, c.page)
        assertEquals(Tiles, c.zone)
    }

    // --- Page vide -----------------------------------------------------------------------------

    @Test
    fun `page vide - bas et OK gardent le focus dans les onglets, Retour quitte`() = runTest {
        val c = started(withEmptyPage())
        c.press(Up, Left)
        assertEquals("p4", c.state.value.currentPage?.id)
        c.press(Down, Ok)
        assertEquals(Tabs, c.zone)
        assertFalse("rien à rejoindre : Retour garde son effet habituel", c.onCommand(Back))
        c.press(Right)
        assertEquals("boucle", 0, c.page)
        c.press(Down)
        assertEquals(Tiles, c.zone)
    }

    @Test
    fun `page vide atteinte par CH- - haut remonte aux onglets`() = runTest {
        val c = started(withEmptyPage())
        c.press(ChannelDown)
        assertEquals("p4", c.state.value.currentPage?.id)
        assertEquals(Tiles, c.zone)
        c.press(Up)
        assertEquals(Tabs, c.zone)
    }

    // --- Priorités ---------------------------------------------------------------------------

    @Test
    fun `question prioritaire - les fleches choisissent la reponse, le focus ne bouge pas`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        c.press(Up)
        factory.pushChanges(commands("c1", TvCommand.Ask(1, "j", "", "Ouvrir ?", listOf("Ignorer", "Ouvrir"), 30)))
        runCurrent()
        c.press(Right)
        assertEquals(1, c.state.value.question?.selected)
        assertEquals("la page ne change pas", 0, c.page)
        assertEquals(Tabs, c.zone)
        c.press(Back)
        assertNull(c.state.value.question)
        assertEquals("après la question, toujours dans les onglets", Tabs, c.zone)
    }

    @Test
    fun `reglage en cours - haut regle la valeur, ne monte pas aux onglets`() = runTest {
        val c = started()
        c.press(Digit(4)) // Curseur : mode réglage.
        assertTrue(c.state.value.adjust != null)
        c.press(Up)
        assertEquals(Tiles, c.zone)
        assertTrue(c.state.value.adjust != null)
    }

    // --- Panneau en superposition --------------------------------------------------------------

    @Test
    fun `panneau - meme navigation, Retour dans les onglets ne ferme pas le panneau`() = runTest {
        val c = panel()
        c.press(Up)
        assertEquals(Tabs, c.zone)
        c.press(Right, Right)
        assertEquals(2, c.page)
        assertTrue(c.state.value.overlay is Overlay.Panel)
        c.press(Back)
        assertEquals(Tiles, c.zone)
        assertTrue("le panneau reste ouvert", c.state.value.overlay is Overlay.Panel)
        c.press(Back)
        assertEquals("Retour depuis les tuiles ferme le panneau", Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `panneau - OK dans les onglets redescend, fermeture puis reouverture sur les tuiles`() = runTest {
        val factory = FakeDriverFactory()
        val c = panel(factory)
        c.press(Up, Left, Ok)
        assertEquals(Tiles, c.zone)
        assertEquals(2, c.page)
        c.press(Up, Back, Back)
        assertEquals(Overlay.None, c.state.value.overlay)
        assertEquals("la fermeture rend les tuiles à l'application", Tiles, c.zone)
        factory.pushChanges(commands("c2", TvCommand.Show(2, "p2", durationSec = 0)))
        runCurrent()
        assertEquals(Tiles, c.zone)
    }

    @Test
    fun `panneau - page vide, Retour dans les onglets ferme le panneau`() = runTest {
        val c = panel(withEmptyPage(), page = "p4")
        c.press(Up)
        assertEquals(Tabs, c.zone)
        c.press(Back)
        assertEquals(Overlay.None, c.state.value.overlay)
    }
}
