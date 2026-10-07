package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.Back
import be.jeedomtv.controller.RemoteCommand.ChannelUp
import be.jeedomtv.controller.RemoteCommand.Digit
import be.jeedomtv.controller.RemoteCommand.Ok
import be.jeedomtv.controller.RemoteCommand.Right
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.ColorKey
import be.jeedomtv.model.ColorKey.Blue
import be.jeedomtv.model.ColorKey.Green
import be.jeedomtv.model.ColorKey.Red
import be.jeedomtv.model.ColorKey.Yellow
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.Page
import be.jeedomtv.model.PendingAction
import be.jeedomtv.model.Screen
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TileIcon
import be.jeedomtv.model.TileType
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

/** Tuile `button` et touches de couleur (raccourcis vers une page), dans l'application, sur le panneau et en arrière-plan. */
class AppControllerColorKeysTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    /** Les pages de l'exemple, avec rouge → Cuisine (p2), vert → Garage (p3), jaune → page inconnue. */
    private fun layoutWithKeys(keys: Map<ColorKey, String>? = mapOf(Red to "p2", Green to "p3", Yellow to "p9")) =
        contractLayout().copy(keys = keys)

    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(onLayout = { layoutWithKeys() }),
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

    private fun color(key: ColorKey) = RemoteCommand.Color(key)

    private val AppController.overlay get() = state.value.overlay

    private fun TestScope.idle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    // --- Tuile button ------------------------------------------------------------------------

    private val camera = Tile("b1", TileType.Button, "Caméra portail", TileIcon.Camera)
    private val gate = Tile("b2", TileType.Button, "Ouvrir le portail", TileIcon.Lock, confirm = true, value = "0")

    private fun buttonFactory() =
        FakeDriverFactory(onLayout = { Layout("r", listOf(Page("cams", "Caméras", listOf(camera, gate)))) })

    @Test
    fun `button - OK envoie press sans valeur, avec un bref retour visuel`() = runTest {
        val factory = buttonFactory()
        val c = started(factory, visible = true)
        assertTrue(c.onCommand(Ok))
        assertNull(c.state.value.confirm)
        assertEquals("retour visuel", "b1", c.state.value.flashTileId)
        runCurrent()
        assertEquals(listOf(ExecCall("b1", TileAction.Press, null)), factory.execCalls)
        idle()
        assertNull(c.state.value.flashTileId)
    }

    @Test
    fun `button - le chiffre N appuie sur la tuile N`() = runTest {
        val factory = buttonFactory()
        factory.onLayout = { Layout("r", listOf(Page("cams", "Caméras", listOf(gate, camera)))) }
        val c = started(factory, visible = true)
        c.press(Digit(2))
        assertEquals(1, c.state.value.focusedIndex)
        runCurrent()
        assertEquals(listOf(ExecCall("b1", TileAction.Press)), factory.execCalls)
    }

    @Test
    fun `button avec confirmation - Retour annule, OK confirme et appuie`() = runTest {
        val factory = buttonFactory()
        val c = started(factory, visible = true)
        c.press(Digit(2))
        assertEquals(PendingAction("b2", TileAction.Press, null, "Activer « Ouvrir le portail »"), c.state.value.confirm)
        c.press(Back)
        assertNull(c.state.value.confirm)
        idle()
        assertTrue(factory.execCalls.isEmpty())

        c.press(Ok, Ok)
        assertNull(c.state.value.confirm)
        assertEquals("b2", c.state.value.flashTileId)
        runCurrent()
        assertEquals(listOf(ExecCall("b2", TileAction.Press)), factory.execCalls)
    }

    @Test
    fun `button - la valeur renvoyee par Jeedom met la tuile a jour`() = runTest {
        val factory = buttonFactory()
        factory.onExec = { "1" }
        val c = started(factory, visible = true)
        c.press(Ok)
        runCurrent()
        assertEquals("1", c.state.value.findTile("b1")?.value)
    }

    // --- Application visible -----------------------------------------------------------------

    @Test
    fun `app visible - la touche affiche directement la page associee`() = runTest {
        val c = started(visible = true)
        c.press(Right)
        assertTrue(c.onCommand(color(Green)))
        assertEquals(2, c.state.value.pageIndex)
        assertEquals(0, c.state.value.focusedIndex)
        assertTrue(c.onCommand(color(Red)))
        assertEquals(1, c.state.value.pageIndex)
        // La même touche ne ferme rien dans l'application : la page reste affichée.
        assertTrue(c.onCommand(color(Red)))
        assertEquals(1, c.state.value.pageIndex)
        assertEquals(Overlay.None, c.overlay)
        assertFalse(c.state.value.foregroundRequested)
    }

    @Test
    fun `app visible - couleur sans page ou page inconnue, touche non traitee`() = runTest {
        val c = started(visible = true)
        assertFalse("jaune → page absente des pages", c.onCommand(color(Yellow)))
        assertFalse("bleu → aucune page", c.onCommand(color(Blue)))
        assertEquals(0, c.state.value.pageIndex)
    }

    @Test
    fun `app visible - la touche abandonne une confirmation ou un reglage en cours`() = runTest {
        val c = started(visible = true)
        c.press(Digit(6))
        assertTrue(c.state.value.confirm != null)
        c.press(color(Red))
        assertNull(c.state.value.confirm)
        assertEquals(1, c.state.value.pageIndex)

        c.press(color(Green), ChannelUp) // Garage, puis retour en boucle sur Salon (p1).
        assertEquals(0, c.state.value.pageIndex)
        c.press(Digit(4))
        assertTrue(c.state.value.adjust != null)
        c.press(color(Red))
        assertNull(c.state.value.adjust)
        assertEquals(1, c.state.value.pageIndex)
    }

    @Test
    fun `app visible - depuis la configuration, la touche revient aux pages`() = runTest {
        val c = started(visible = true)
        c.press(RemoteCommand.Menu)
        assertEquals(Screen.Setup, c.state.value.screen)
        assertTrue(c.onCommand(color(Green)))
        assertEquals(Screen.Pages, c.state.value.screen)
        assertEquals(2, c.state.value.pageIndex)
    }

    // --- Application cachée ------------------------------------------------------------------

    @Test
    fun `app cachee - la touche ouvre le panneau comme un show sans duree`() = runTest {
        val c = started()
        assertTrue(c.onCommand(color(Red)))
        assertEquals(Overlay.Panel("p2", 0), c.overlay)
        assertEquals(1, c.state.value.pageIndex)
        assertFalse("la vidéo reste devant", c.state.value.foregroundRequested)
        // Fermeture après une minute sans touche.
        advanceTimeBy(59_900)
        assertTrue(c.overlay is Overlay.Panel)
        advanceTimeBy(200)
        assertEquals(Overlay.None, c.overlay)
    }

    @Test
    fun `app cachee - Retour ferme le panneau ouvert par une touche`() = runTest {
        val c = started()
        c.press(color(Green))
        assertTrue(c.overlay is Overlay.Panel)
        assertTrue(c.onCommand(Back))
        assertEquals(Overlay.None, c.overlay)
        assertEquals("sélection de l'application rendue", 0, c.state.value.pageIndex)
    }

    @Test
    fun `app cachee - couleur sans page, rien ne s'ouvre`() = runTest {
        val c = started()
        assertFalse(c.onCommand(color(Blue)))
        assertFalse(c.onCommand(color(Yellow)))
        assertEquals(Overlay.None, c.overlay)
    }

    @Test
    fun `app cachee sans permission - la touche ouvre l'application sur la page`() = runTest {
        val c = started(permission = false)
        assertTrue(c.onCommand(color(Red)))
        assertEquals(Overlay.None, c.overlay)
        assertTrue(c.state.value.foregroundRequested)
        assertEquals(1, c.state.value.pageIndex)
    }

    @Test
    fun `app cachee - la touche remplace le bandeau d'un message`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layoutWithKeys() })
        val c = started(factory)
        factory.pushChanges(commands("c1", TvCommand.Notify(1, "", "Bonjour")))
        runCurrent()
        assertTrue(c.overlay is Overlay.Notice)
        c.press(color(Red))
        assertEquals(Overlay.Panel("p2", 0), c.overlay)
    }

    // --- Panneau ouvert ----------------------------------------------------------------------

    @Test
    fun `panneau - une autre couleur change de page, la meme touche le ferme`() = runTest {
        val c = started()
        c.press(color(Red))
        c.press(Right)
        assertTrue(c.onCommand(color(Green)))
        assertTrue(c.overlay is Overlay.Panel)
        assertEquals(2, c.state.value.pageIndex)
        assertEquals(0, c.state.value.focusedIndex)

        assertTrue("même touche que la page affichée", c.onCommand(color(Green)))
        assertEquals(Overlay.None, c.overlay)
        assertEquals("sélection de l'application rendue", 0, c.state.value.pageIndex)
    }

    @Test
    fun `panneau ouvert par Jeedom - la touche de sa page le ferme, une couleur inactive ne fait rien`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layoutWithKeys() })
        val c = started(factory)
        factory.pushChanges(commands("c1", TvCommand.Show(1, "p2", durationSec = 30)))
        runCurrent()
        assertEquals(Overlay.Panel("p2", 30), c.overlay)
        assertFalse(c.onCommand(color(Blue)))
        assertTrue(c.overlay is Overlay.Panel)
        assertTrue(c.onCommand(color(Red)))
        assertEquals(Overlay.None, c.overlay)
    }

    @Test
    fun `panneau - la touche relance la minute d'inactivite`() = runTest {
        val c = started()
        c.press(color(Red))
        advanceTimeBy(50_000)
        c.press(color(Green))
        advanceTimeBy(50_000)
        assertTrue(c.overlay is Overlay.Panel)
        advanceTimeBy(10_100)
        assertEquals(Overlay.None, c.overlay)
    }

    // --- Sans keys ---------------------------------------------------------------------------

    @Test
    fun `keys absent - rouge ouvre la premiere page, les autres couleurs ne font rien`() = runTest {
        val c = started(FakeDriverFactory(onLayout = { layoutWithKeys(keys = null) }))
        assertNull(c.state.value.colorKeys)
        for (key in listOf(Green, Yellow, Blue)) assertFalse(c.onCommand(color(key)))
        assertEquals(Overlay.None, c.overlay)
        assertTrue(c.onCommand(color(Red)))
        assertEquals(Overlay.Panel("p1", 0), c.overlay)
        assertEquals(0, c.state.value.pageIndex)
        c.press(Right)
        assertTrue("rouge sur sa propre page : fermeture", c.onCommand(color(Red)))
        assertEquals(Overlay.None, c.overlay)
    }

    @Test
    fun `keys present sans rouge - rouge inactif`() = runTest {
        val c = started(FakeDriverFactory(onLayout = { layoutWithKeys(keys = mapOf(Blue to "p3")) }))
        assertFalse(c.onCommand(color(Red)))
        assertTrue(c.onCommand(color(Blue)))
        assertEquals(Overlay.Panel("p3", 0), c.overlay)
    }

    @Test
    fun `keys suit le layout recharge`() = runTest {
        var layout = layoutWithKeys(keys = null)
        val factory = FakeDriverFactory(onLayout = { layout })
        val c = started(factory, visible = true)
        layout = layoutWithKeys(keys = mapOf(Red to "p3")).copy(revision = "r2")
        factory.pushChanges(changes("c1", revision = "r2"))
        runCurrent()
        assertEquals(mapOf(Red to "p3"), c.state.value.colorKeys)
        c.press(color(Red))
        assertEquals(2, c.state.value.pageIndex)
    }

    // --- Question prioritaire ----------------------------------------------------------------

    private fun question(id: Long) = TvCommand.Ask(id, "j$id", "", "Ouvrir ?", listOf("Ignorer", "Ouvrir"), 30)

    @Test
    fun `question - une touche de couleur ne la ferme pas, dans l'application`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layoutWithKeys() })
        val c = started(factory, visible = true)
        factory.pushChanges(commands("c1", question(1)))
        runCurrent()
        assertTrue("touche gardée par la question", c.onCommand(color(Red)))
        assertEquals("j1", c.state.value.question?.ask)
        assertEquals(0, c.state.value.pageIndex)
    }

    @Test
    fun `question par-dessus la video - une touche de couleur n'ouvre ni ne ferme rien`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layoutWithKeys() })
        val c = started(factory)
        factory.pushChanges(commands("c1", question(1)))
        runCurrent()
        assertTrue(c.state.value.question?.inOverlay == true)
        c.press(color(Red))
        assertEquals("j1", c.state.value.question?.ask)
        assertEquals(Overlay.None, c.overlay)
    }

    @Test
    fun `question par-dessus le panneau - la couleur ne ferme ni la question ni le panneau`() = runTest {
        val factory = FakeDriverFactory(onLayout = { layoutWithKeys() })
        val c = started(factory)
        c.press(color(Red))
        factory.pushChanges(commands("c1", question(1)))
        runCurrent()
        c.press(color(Red), color(Green))
        assertEquals("j1", c.state.value.question?.ask)
        assertEquals(Overlay.Panel("p2", 0), c.overlay)
        assertEquals(1, c.state.value.pageIndex)
    }
}
