package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.Back
import be.jeedomtv.controller.RemoteCommand.ChannelDown
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
import be.jeedomtv.model.FocusZone
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Page
import be.jeedomtv.model.PendingAction
import be.jeedomtv.model.Screen
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TileType
import be.jeedomtv.model.driver.AuthenticationException
import be.jeedomtv.model.driver.JeedomException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppControllerTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    /**
     * Le contrôleur vit dans le backgroundScope du TestScope : sa boucle des changements tourne
     * sans fin (comme dans l'application), le test n'a pas à l'attendre.
     */
    private fun TestScope.controller(
        settings: FakeSettings = FakeSettings(),
        factory: FakeDriverFactory = FakeDriverFactory(),
        scope: CoroutineScope = backgroundScope,
        initial: AppState = AppState(),
    ) = AppController(AppModel(initial), settings, factory, scope)

    /**
     * advanceUntilIdle ignore les tâches du backgroundScope : on avance l'horloge virtuelle d'une
     * minute (minuteurs des messages, du retour visuel, anti-rebond de l'état).
     */
    private fun TestScope.idle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    /**
     * Contrôleur connecté, sur l'écran des pages. Par défaut l'application n'est pas visible ;
     * la boucle des changements tourne quand même (elle attend une réponse du test).
     */
    private fun TestScope.connected(
        factory: FakeDriverFactory = FakeDriverFactory(),
        settings: FakeSettings = FakeSettings(stored = config),
        scope: CoroutineScope = backgroundScope,
        visible: Boolean = false,
    ): AppController {
        val c = controller(settings, factory, scope, AppState(uiVisible = visible))
        c.start()
        // runCurrent et non advanceUntilIdle : ce dernier ignore les tâches du backgroundScope.
        runCurrent()
        assertEquals(Screen.Pages, c.state.value.screen)
        return c
    }

    private fun AppController.press(vararg commands: RemoteCommand) = commands.forEach { onCommand(it) }

    private fun AppController.tile(id: String): Tile = state.value.findTile(id)!!

    // --- Démarrage et connexion ----------------------------------------------------------------

    @Test
    fun `start sans configuration affiche Setup`() = runTest {
        val factory = FakeDriverFactory()
        val c = controller(factory = factory)
        c.start()
        assertEquals(Screen.Loading, c.state.value.screen)
        idle()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun `start avec configuration ping puis layout puis Pages`() = runTest {
        val settings = FakeSettings(stored = config)
        val factory = FakeDriverFactory()
        val c = connected(factory, settings)
        val state = c.state.value
        assertEquals(listOf(config), factory.pings)
        assertEquals(1, factory.layoutCount)
        assertEquals(contractLayout().pages, state.pages)
        assertEquals("9f2c1a", state.revision)
        assertEquals("TV salon", state.tvName)
        assertEquals(config, state.config)
        assertEquals(0, state.pageIndex)
        assertEquals(0, state.focusedIndex)
        assertNull(state.error)
        assertEquals(listOf(config), settings.saved)
    }

    @Test
    fun `cle refusee renvoie vers Setup avec le message, sans enregistrer`() = runTest {
        val settings = FakeSettings()
        val factory = FakeDriverFactory().apply { onPing = { throw AuthenticationException("Clé refusée par Jeedom") } }
        val c = controller(settings, factory)
        c.submitSetup(config)
        idle()
        val state = c.state.value
        assertEquals(Screen.Setup, state.screen)
        assertEquals("Clé refusée par Jeedom", state.error)
        assertEquals(config, state.config)
        assertTrue(settings.saved.isEmpty())
        assertEquals(0, factory.layoutCount)
    }

    @Test
    fun `la configuration est enregistree des le ping reussi, meme si le layout echoue`() = runTest {
        val settings = FakeSettings()
        val factory = FakeDriverFactory(onLayout = { throw JeedomException("Erreur de Jeedom (HTTP 500)") })
        val c = controller(settings, factory)
        c.submitSetup(config)
        idle()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals("Erreur de Jeedom (HTTP 500)", c.state.value.error)
        assertEquals(listOf(config), settings.saved)
    }

    @Test
    fun `une exception inattendue donne une erreur generique`() = runTest {
        val factory = FakeDriverFactory().apply { onPing = { throw IllegalStateException("boum") } }
        val c = controller(factory = factory)
        c.submitSetup(config)
        idle()
        assertEquals("Connexion à Jeedom impossible", c.state.value.error)
    }

    @Test
    fun `Menu ouvre Setup, Retour y ramene aux pages`() = runTest {
        val c = connected()
        assertTrue(c.onCommand(Menu))
        assertEquals(Screen.Setup, c.state.value.screen)
        assertTrue(c.onCommand(Back))
        assertEquals(Screen.Pages, c.state.value.screen)
    }

    @Test
    fun `Retour sur Setup sans connexion n'est pas consomme`() = runTest {
        val c = controller()
        c.start()
        idle()
        assertFalse(c.onCommand(Back))
        assertFalse(c.onCommand(Ok))
    }

    @Test
    fun `Retour sur les pages n'est pas consomme (quitter)`() = runTest {
        val c = connected()
        assertFalse(c.onCommand(Back))
    }

    // --- Navigation ----------------------------------------------------------------------------

    @Test
    fun `fleches dans la grille de 4 colonnes`() = runTest {
        val c = connected()
        c.press(Up)
        assertEquals("haut depuis la première ligne : les onglets", FocusZone.Tabs, c.state.value.focusZone)
        c.press(Down)
        assertEquals(FocusZone.Tiles, c.state.value.focusZone)
        assertEquals(0, c.state.value.focusedIndex)
        c.press(Left)
        assertEquals(0, c.state.value.focusedIndex)
        c.press(Right, Right)
        assertEquals(2, c.state.value.focusedIndex)
        c.press(Down)
        assertEquals("ligne incomplète : dernière tuile", 5, c.state.value.focusedIndex)
        c.press(Down)
        assertEquals(5, c.state.value.focusedIndex)
        c.press(Up)
        assertEquals(1, c.state.value.focusedIndex)
        c.press(Down)
        assertEquals(5, c.state.value.focusedIndex)
        c.press(Right)
        assertEquals(5, c.state.value.focusedIndex)
        c.press(Left, Left)
        assertEquals(3, c.state.value.focusedIndex)
        c.press(Down)
        assertEquals(5, c.state.value.focusedIndex)
    }

    @Test
    fun `CH+ et CH- changent de page en boucle`() = runTest {
        val c = connected()
        c.press(Right)
        c.press(ChannelUp)
        assertEquals(1, c.state.value.pageIndex)
        assertEquals(0, c.state.value.focusedIndex)
        c.press(ChannelUp)
        assertEquals(2, c.state.value.pageIndex)
        c.press(ChannelUp)
        assertEquals(0, c.state.value.pageIndex)
        c.press(ChannelDown)
        assertEquals(2, c.state.value.pageIndex)
    }

    @Test
    fun `chiffre agit sur la tuile N comme OK`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        assertTrue(c.onCommand(Digit(1)))
        idle()
        assertEquals(listOf(ExecCall("t1", TileAction.Toggle)), factory.execCalls)

        c.press(Digit(4))
        assertEquals(3, c.state.value.focusedIndex)
        assertEquals(Adjust("t4", 20.5), c.state.value.adjust)
    }

    @Test
    fun `chiffre sur une info, hors page ou zero`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.press(Digit(5))
        assertEquals(4, c.state.value.focusedIndex)
        c.press(Digit(7), Digit(0))
        assertEquals(4, c.state.value.focusedIndex)
        c.press(Ok)
        idle()
        assertTrue(factory.execCalls.isEmpty())
        assertNull(c.state.value.adjust)
    }

    // --- Interrupteur --------------------------------------------------------------------------

    @Test
    fun `toggle optimiste puis valeur de la reponse`() = runTest {
        val factory = FakeDriverFactory().apply { onExec = { "0" } }
        val c = connected(factory)
        c.press(Ok)
        assertEquals("mise à jour optimiste immédiate", "0", c.tile("t1").value)
        idle()
        assertEquals("0", c.tile("t1").value)
        assertEquals(listOf(ExecCall("t1", TileAction.Toggle)), factory.execCalls)
    }

    @Test
    fun `toggle corrige par la reponse`() = runTest {
        // L'équipement n'a pas (encore) changé : la réponse fait foi.
        val factory = FakeDriverFactory().apply { onExec = { "1" } }
        val c = connected(factory)
        c.press(Ok)
        assertEquals("0", c.tile("t1").value)
        idle()
        assertEquals("1", c.tile("t1").value)
    }

    @Test
    fun `toggle sans valeur en retour garde la valeur optimiste`() = runTest {
        val c = connected(FakeDriverFactory().apply { onExec = { null } })
        c.press(ChannelUp, Ok)
        idle()
        assertEquals("1", c.tile("k1").value)
    }

    @Test
    fun `toggle en erreur revient a l'ancienne valeur et affiche un message 4 s`() = runTest {
        val factory = FakeDriverFactory().apply { onExec = { throw JeedomException("Tuile inconnue") } }
        val c = connected(factory)
        c.press(Ok)
        assertEquals("0", c.tile("t1").value)
        runCurrent()
        assertEquals("1", c.tile("t1").value)
        assertEquals("Tuile inconnue", c.state.value.notice)
        advanceTimeBy(3_900)
        assertEquals("Tuile inconnue", c.state.value.notice)
        advanceTimeBy(200)
        assertNull(c.state.value.notice)
    }

    // --- Mode réglage --------------------------------------------------------------------------

    @Test
    fun `curseur - pas, bornes et envoi de set`() = runTest {
        val factory = FakeDriverFactory().apply { onExec = { it.value.toString() } }
        val c = connected(factory)
        c.press(Digit(4))
        assertEquals(Adjust("t4", 20.5), c.state.value.adjust)
        c.press(Up)
        assertEquals(21.0, c.state.value.adjust!!.pending!!, 0.0)
        repeat(30) { c.press(Up) }
        assertEquals("bornée au max", 25.0, c.state.value.adjust!!.pending!!, 0.0)
        c.press(Left)
        assertEquals(15.0, c.state.value.adjust!!.pending!!, 0.0)
        c.press(Down)
        assertEquals("bornée au min", 15.0, c.state.value.adjust!!.pending!!, 0.0)
        c.press(Right, Down)
        assertEquals(24.5, c.state.value.adjust!!.pending!!, 0.0)
        assertTrue(factory.execCalls.isEmpty())

        c.press(Ok)
        assertNull(c.state.value.adjust)
        idle()
        assertEquals(listOf(ExecCall("t4", TileAction.Set, 24.5)), factory.execCalls)
        assertEquals("24.5", c.tile("t4").value)
    }

    @Test
    fun `pas de 0,1 sans derive d'arrondi`() = runTest {
        val tile = Tile("x", TileType.Slider, "Fin", min = 0.0, max = 1.0, step = 0.1, value = "0")
        val factory = FakeDriverFactory(onLayout = { Layout("r", listOf(Page("p", "P", listOf(tile)))) })
        val c = connected(factory)
        c.press(Ok, Up, Up, Up)
        assertEquals(0.3, c.state.value.adjust!!.pending!!, 0.0)
    }

    @Test
    fun `Retour annule le reglage sans rien envoyer`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.press(Digit(4), Up, Up)
        assertTrue(c.onCommand(Back))
        assertNull(c.state.value.adjust)
        assertEquals(Screen.Pages, c.state.value.screen)
        idle()
        assertTrue(factory.execCalls.isEmpty())
        assertEquals("20.5", c.tile("t4").value)
    }

    @Test
    fun `volet avec position - set, CH+ monte, CH- descend`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.press(Digit(2))
        assertEquals(Adjust("t2", 100.0), c.state.value.adjust)
        c.press(Down, Down)
        assertEquals(80.0, c.state.value.adjust!!.pending!!, 0.0)
        c.press(ChannelUp, ChannelDown)
        assertEquals("CH+/CH- ne quittent pas le réglage", Adjust("t2", 80.0), c.state.value.adjust)
        c.press(Ok)
        idle()
        assertEquals(
            listOf(ExecCall("t2", TileAction.Up), ExecCall("t2", TileAction.Down), ExecCall("t2", TileAction.Set, 80.0)),
            factory.execCalls,
        )
        assertNull(c.state.value.adjust)
    }

    @Test
    fun `curseur - CH+ et CH- sans effet`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.press(Digit(4), ChannelUp, ChannelDown, Digit(1))
        idle()
        assertTrue(factory.execCalls.isEmpty())
        assertEquals(Adjust("t4", 20.5), c.state.value.adjust)
        assertEquals(0, c.state.value.pageIndex)
    }

    @Test
    fun `valeur absente - la valeur en attente part du min`() = runTest {
        val tile = Tile("s", TileType.Slider, "Sans valeur", min = 10.0, max = 30.0, step = 2.0, value = null)
        val c = connected(FakeDriverFactory(onLayout = { Layout("r", listOf(Page("p", "P", listOf(tile)))) }))
        c.press(Ok)
        assertEquals(Adjust("s", 10.0), c.state.value.adjust)
    }

    @Test
    fun `volet sans position - haut, bas, stop et sortie`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.press(Digit(3))
        assertEquals(Adjust("t3", null), c.state.value.adjust)
        c.press(Up, Down, Ok)
        assertEquals("OK envoie stop sans quitter", Adjust("t3", null), c.state.value.adjust)
        c.press(Back)
        assertNull(c.state.value.adjust)
        idle()
        assertEquals(
            listOf(ExecCall("t3", TileAction.Up), ExecCall("t3", TileAction.Down), ExecCall("t3", TileAction.Stop)),
            factory.execCalls,
        )
    }

    // --- Confirmation et scénario --------------------------------------------------------------

    @Test
    fun `scene avec confirmation - Retour annule, OK confirme et lance`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.press(Digit(6))
        assertEquals(PendingAction("t6", TileAction.Run, null, "Lancer « Bonne nuit »"), c.state.value.confirm)
        c.press(Up, Digit(1), ChannelUp)
        assertEquals("la confirmation garde la main", 5, c.state.value.focusedIndex)
        assertEquals(0, c.state.value.pageIndex)
        assertTrue(c.onCommand(Back))
        assertNull(c.state.value.confirm)
        idle()
        assertTrue(factory.execCalls.isEmpty())

        c.press(Ok)
        assertTrue(c.state.value.confirm != null)
        c.press(Ok)
        assertNull(c.state.value.confirm)
        assertEquals("retour visuel", "t6", c.state.value.flashTileId)
        idle()
        assertEquals(listOf(ExecCall("t6", TileAction.Run)), factory.execCalls)
        assertNull(c.state.value.flashTileId)
    }

    @Test
    fun `scene sans confirmation part directement`() = runTest {
        val tile = Tile("sc", TileType.Scene, "Cinéma")
        val factory = FakeDriverFactory(onLayout = { Layout("r", listOf(Page("p", "P", listOf(tile)))) })
        val c = connected(factory)
        c.press(Ok)
        assertNull(c.state.value.confirm)
        idle()
        assertEquals(listOf(ExecCall("sc", TileAction.Run)), factory.execCalls)
    }

    @Test
    fun `confirmation d'un reglage et d'un volet sans position`() = runTest {
        val tiles = listOf(
            Tile("v", TileType.Shutter, "Porte garage", confirm = true),
            Tile("c", TileType.Slider, "Chaudière", confirm = true, value = "50", unit = "%", min = 0.0, max = 100.0, step = 10.0),
        )
        val factory = FakeDriverFactory(onLayout = { Layout("r", listOf(Page("p", "P", tiles))) })
        val c = connected(factory)

        c.press(Ok, Up)
        assertEquals(TileAction.Up, c.state.value.confirm?.action)
        c.press(Ok)
        assertEquals("on reste en réglage après confirmation", Adjust("v", null), c.state.value.adjust)
        c.press(Back, Right, Ok, Up, Ok)
        assertNull(c.state.value.adjust)
        assertEquals(PendingAction("c", TileAction.Set, 60.0, "Régler « Chaudière » sur 60 %"), c.state.value.confirm)
        c.press(Ok)
        idle()
        assertEquals(listOf(ExecCall("v", TileAction.Up), ExecCall("c", TileAction.Set, 60.0)), factory.execCalls)
    }

    @Test
    fun `toggle confirme`() = runTest {
        val tile = Tile("al", TileType.Switch, "Alarme", confirm = true, value = "0")
        val factory = FakeDriverFactory(onLayout = { Layout("r", listOf(Page("p", "P", listOf(tile)))) })
        val c = connected(factory)
        c.press(Ok)
        assertEquals("Allumer « Alarme »", c.state.value.confirm?.label)
        assertEquals("0", c.tile("al").value)
        c.press(Ok)
        assertEquals("1", c.tile("al").value)
        idle()
        assertEquals(listOf(ExecCall("al", TileAction.Toggle)), factory.execCalls)
    }

    @Test
    fun `erreur d'un ordre - message temporaire`() = runTest {
        val factory = FakeDriverFactory().apply { onExec = { throw JeedomException("Action non permise") } }
        val c = connected(factory)
        c.press(Digit(3), Up)
        advanceTimeBy(100)
        assertEquals("Action non permise", c.state.value.notice)
        idle()
        assertNull(c.state.value.notice)
    }

    // --- Changements en direct -----------------------------------------------------------------

    @Test
    fun `la boucle tourne des la connexion, application invisible, et sur Setup`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        assertFalse(c.state.value.uiVisible)
        assertEquals(listOf<String?>(null), factory.changesCalls)

        c.press(Menu)
        factory.pushChanges(changes("100", "9f2c1a", "t1" to "0"))
        runCurrent()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals("0", c.tile("t1").value)
        assertEquals(listOf(null, "100"), factory.changesCalls)
    }

    @Test
    fun `une seule boucle a la fois`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.onUiVisibilityChanged(true)
        c.onUiVisibilityChanged(false)
        c.onUiVisibilityChanged(true)
        runCurrent()
        assertEquals(listOf<String?>(null), factory.changesCalls)
        factory.pushChanges(changes("1"))
        runCurrent()
        assertEquals(listOf(null, "1"), factory.changesCalls)
    }

    @Test
    fun `applique les changements et relance avec le curseur`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.onUiVisibilityChanged(true)
        factory.pushChanges(changes("100"))
        factory.pushChanges(changes("101", "9f2c1a", "t1" to "0", "t4" to "21", "k2" to "1"))
        runCurrent()
        assertEquals(listOf(null, "100", "101"), factory.changesCalls)
        assertEquals("0", c.tile("t1").value)
        assertEquals("21", c.tile("t4").value)
        assertEquals("1", c.tile("k2").value)
        assertEquals(1, factory.layoutCount)
        assertFalse(c.state.value.offline)
    }

    @Test
    fun `nouvelle revision - rechargement du layout`() = runTest {
        var layout = contractLayout()
        val factory = FakeDriverFactory(onLayout = { layout })
        val c = connected(factory)
        c.press(ChannelUp, Right)
        c.onUiVisibilityChanged(true)
        runCurrent()

        layout = Layout(
            "a1b2c3",
            listOf(Page("p2", "Cuisine", listOf(Tile("k1", TileType.Switch, "Plan de travail", value = "1")))),
        )
        factory.pushChanges(changes("200", "a1b2c3"))
        runCurrent()
        val state = c.state.value
        assertEquals(2, factory.layoutCount)
        assertEquals("a1b2c3", state.revision)
        assertEquals(layout.pages, state.pages)
        assertEquals("page retrouvée par son id", 0, state.pageIndex)
        assertEquals("sélection gardée dans les bornes", 0, state.focusedIndex)
        assertEquals(listOf(null, "200"), factory.changesCalls)
    }

    @Test
    fun `meme revision - pas de rechargement`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.onUiVisibilityChanged(true)
        factory.pushChanges(changes("1", "9f2c1a"))
        runCurrent()
        assertEquals(1, factory.layoutCount)
    }

    @Test
    fun `erreur reseau - hors ligne, 3 s, layout recharge, reprise sans curseur`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.onUiVisibilityChanged(true)
        factory.pushChanges(changes("100"))
        factory.failChanges(JeedomException("Jeedom injoignable (192.168.1.10)"))
        runCurrent()
        assertTrue(c.state.value.offline)
        assertEquals(listOf(null, "100"), factory.changesCalls)

        advanceTimeBy(2_900)
        assertEquals(1, factory.layoutCount)
        advanceTimeBy(200)
        assertEquals(2, factory.layoutCount)
        assertEquals(listOf(null, "100", null), factory.changesCalls)

        factory.pushChanges(changes("300", "9f2c1a", "t1" to "0"))
        runCurrent()
        assertFalse(c.state.value.offline)
        assertEquals("0", c.tile("t1").value)
    }

    @Test
    fun `layout injoignable pendant la reprise - on reessaie`() = runTest {
        var down = false
        val factory = FakeDriverFactory(onLayout = { if (down) throw JeedomException("Jeedom injoignable") else contractLayout() })
        val c = connected(factory)
        c.onUiVisibilityChanged(true)
        runCurrent()
        down = true
        factory.failChanges(JeedomException("Jeedom injoignable"))
        factory.failChanges(JeedomException("Jeedom injoignable"))
        // Pauses de 3 s puis 6 s : Jeedom reste injoignable.
        advanceTimeBy(9_100)
        assertTrue(c.state.value.offline)
        assertEquals(listOf(null, null, null), factory.changesCalls)
        assertEquals(Screen.Pages, c.state.value.screen)
    }

    @Test
    fun `cle refusee pendant la boucle - retour a Setup`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.onUiVisibilityChanged(true)
        factory.failChanges(AuthenticationException("Clé refusée par Jeedom"))
        runCurrent()
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals("Clé refusée par Jeedom", c.state.value.error)
    }

    @Test
    fun `un changement pendant un reglage met a jour la valeur actuelle, pas la valeur en attente`() = runTest {
        val factory = FakeDriverFactory()
        val c = connected(factory)
        c.onUiVisibilityChanged(true)
        c.press(Digit(4), Up)
        factory.pushChanges(changes("1", "9f2c1a", "t4" to "19"))
        runCurrent()
        assertEquals("19", c.tile("t4").value)
        assertEquals(Adjust("t4", 21.0), c.state.value.adjust)
    }

    // --- Fonctions pures -----------------------------------------------------------------------

    @Test
    fun `withLayout abandonne un reglage sur une tuile disparue`() {
        val state = AppState(screen = Screen.Pages, adjust = Adjust("t4", 21.0)).withLayout(contractLayout())
        assertEquals(Adjust("t4", 21.0), state.adjust)
        val gone = state.withLayout(Layout("r", listOf(Page("p1", "Salon", emptyList()))))
        assertNull(gone.adjust)
        assertEquals(0, gone.focusedIndex)
    }

    @Test
    fun `formatValue`() {
        assertEquals("40 %", formatValue(40.0, "%"))
        assertEquals("20,5 °C", formatValue(20.5, "°C"))
        assertEquals("3", formatValue(3.0, ""))
    }
}
