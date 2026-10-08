package be.jeedomtv.controller

import be.jeedomtv.controller.RemoteCommand.ChannelUp
import be.jeedomtv.controller.RemoteCommand.Digit
import be.jeedomtv.controller.RemoteCommand.Menu
import be.jeedomtv.controller.RemoteCommand.Right
import be.jeedomtv.model.Adjust
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Banner
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Screen
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.TvState
import be.jeedomtv.model.driver.AuthenticationException
import be.jeedomtv.model.driver.JeedomException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MVP 3 : ordres de Jeedom (show, notify, exit), état signalé, boucle en arrière-plan. */
class AppControllerOrdersTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    /** Contrôleur dans le backgroundScope (boucle sans fin), démarré avec une configuration enregistrée. */
    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(),
        visible: Boolean = false,
    ): AppController {
        val c = AppController(
            AppModel(AppState(uiVisible = visible)), FakeSettings(stored = config), factory, backgroundScope,
            elapsedMs = { testScheduler.currentTime },
        )
        c.start()
        runCurrent()
        return c
    }

    private fun AppController.press(vararg commands: RemoteCommand) = commands.forEach { onCommand(it) }

    private fun FakeDriverFactory.send(vararg commands: TvCommand) {
        pushChanges(commands("c${changesCalls.size}", *commands))
    }

    // --- Démarrage et boucle en arrière-plan ---------------------------------------------------

    @Test
    fun `Jeedom injoignable au demarrage - hors ligne sur les pages, puis rattrapage par la boucle`() = runTest {
        var down = true
        val factory = FakeDriverFactory(onLayout = { if (down) throw JeedomException("Jeedom injoignable") else contractLayout() })
        factory.onPing = { if (down) throw JeedomException("Jeedom injoignable") else be.jeedomtv.model.PingInfo(1, "TV", null, null) }
        val c = started(factory)
        assertEquals("jamais Setup en arrière-plan", Screen.Pages, c.state.value.screen)
        assertTrue(c.state.value.offline)
        assertTrue(c.state.value.pages.isEmpty())
        assertEquals("la boucle tourne déjà", listOf<String?>(null), factory.changesCalls)

        down = false
        factory.pushChanges(changes("1"))
        runCurrent()
        assertFalse(c.state.value.offline)
        assertEquals("révision inconnue : layout chargé", contractLayout().pages, c.state.value.pages)
        assertEquals(Screen.Pages, c.state.value.screen)
    }

    @Test
    fun `cle refusee au demarrage - Setup si visible`() = runTest {
        val factory = FakeDriverFactory().apply { onPing = { throw AuthenticationException("Clé refusée par Jeedom") } }
        val c = started(factory, visible = true)
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals("Clé refusée par Jeedom", c.state.value.error)
        assertTrue(factory.changesCalls.isEmpty())
    }

    @Test
    fun `cle refusee au demarrage en arriere-plan - hors ligne, puis Setup une fois visible`() = runTest {
        val factory = FakeDriverFactory().apply { onPing = { throw AuthenticationException("Clé refusée par Jeedom") } }
        val c = started(factory)
        assertEquals(Screen.Pages, c.state.value.screen)
        assertTrue(c.state.value.offline)

        factory.failChanges(AuthenticationException("Clé refusée par Jeedom"))
        runCurrent()
        assertEquals("invisible : on réessaie", Screen.Pages, c.state.value.screen)

        c.onUiVisibilityChanged(true)
        factory.failChanges(AuthenticationException("Clé refusée par Jeedom"))
        advanceTimeBy(3_100)
        assertEquals(Screen.Setup, c.state.value.screen)
        assertEquals("Clé refusée par Jeedom", c.state.value.error)
    }

    @Test
    fun `erreur en arriere-plan - ni Loading ni Setup`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        val screens = mutableListOf<Screen>()
        repeat(3) { factory.failChanges(JeedomException("Jeedom injoignable")) }
        repeat(10) {
            advanceTimeBy(1_000)
            screens += c.state.value.screen
        }
        assertTrue(screens.toString(), screens.all { it == Screen.Pages })
        assertTrue(c.state.value.offline)
    }

    @Test
    fun `rallumage de l'ecran - boucle relancee aussitot avec layout frais`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.pushChanges(changes("100"))
        runCurrent()
        assertEquals(listOf(null, "100"), factory.changesCalls)

        c.onScreenChanged(false)
        runCurrent()
        assertFalse(c.state.value.screenOn)
        assertEquals("la veille n'arrête pas la boucle", listOf(null, "100"), factory.changesCalls)

        // Longue veille : l'attente d'avant est morte depuis longtemps.
        advanceTimeBy(10 * 60_000L)
        c.onScreenChanged(true)
        runCurrent()
        assertTrue(c.state.value.screenOn)
        assertEquals(2, factory.layoutCount)
        assertEquals(listOf(null, "100", null), factory.changesCalls)
    }

    @Test
    fun `ecran eteint puis rallume aussitot - l'attente en cours est gardee`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.pushChanges(changes("100"))
        runCurrent()
        advanceTimeBy(5_000)
        c.onScreenChanged(false)
        advanceTimeBy(10_000)
        c.onScreenChanged(true)
        runCurrent()
        assertEquals("pas d'attente abandonnée côté Jeedom", listOf(null, "100"), factory.changesCalls)
        assertEquals(1, factory.layoutCount)

        // La même attente reçoit l'ordre.
        factory.pushChanges(commands("101", TvCommand.Exit(1)))
        runCurrent()
        assertEquals(listOf(null, "100", "101"), factory.changesCalls)
    }

    @Test
    fun `rallumage avec la boucle en erreur - relancee aussitot`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.failChanges(JeedomException("Jeedom injoignable"))
        runCurrent()
        assertTrue(c.state.value.offline)
        c.onScreenChanged(true)
        runCurrent()
        assertEquals("sans attendre la pause de 3 s", listOf(null, null), factory.changesCalls)
    }

    @Test
    fun `retour du reseau - boucle relancee, une seule`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        c.onNetworkMaybeRestored()
        c.onNetworkMaybeRestored()
        runCurrent()
        assertEquals(listOf<String?>(null, null), factory.changesCalls.take(2))
        factory.pushChanges(changes("5"))
        runCurrent()
        assertEquals(listOf(null, null, "5"), factory.changesCalls)
    }

    // --- show ----------------------------------------------------------------------------------

    @Test
    fun `show affiche la page, selection sur la premiere tuile, reglage annule`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        c.press(Digit(4))
        assertEquals(Adjust("t4", 20.5), c.state.value.adjust)

        factory.send(TvCommand.Show(1, "p3"))
        runCurrent()
        val state = c.state.value
        assertEquals(2, state.pageIndex)
        assertEquals(0, state.focusedIndex)
        assertNull(state.adjust)
        assertFalse("déjà visible", state.foregroundRequested)
    }

    @Test
    fun `show annule une confirmation et quitte Setup en arriere-plan`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        c.press(Digit(6))
        assertTrue(c.state.value.confirm != null)
        factory.send(TvCommand.Show(1, "p2"))
        runCurrent()
        assertNull(c.state.value.confirm)

        c.press(Menu)
        c.onUiVisibilityChanged(false)
        factory.send(TvCommand.Show(2, "p1"))
        runCurrent()
        assertEquals(Screen.Pages, c.state.value.screen)
        assertEquals(0, c.state.value.pageIndex)
    }

    @Test
    fun `show ignore - page inconnue, ou formulaire de configuration a l'ecran`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "inconnue"))
        runCurrent()
        assertEquals(0, c.state.value.pageIndex)

        c.press(Menu)
        factory.send(TvCommand.Show(2, "p2"))
        runCurrent()
        assertEquals(Screen.Setup, c.state.value.screen)
    }

    @Test
    fun `show application cachee - demande de premier plan, acquittee a l'affichage`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p2"))
        runCurrent()
        assertTrue(c.state.value.foregroundRequested)
        assertEquals(1, c.state.value.pageIndex)
        c.onUiVisibilityChanged(true)
        assertFalse(c.state.value.foregroundRequested)
    }

    @Test
    fun `duration - retour a la page d'avant`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        c.press(Right)
        factory.send(TvCommand.Show(1, "p2", durationSec = 30))
        runCurrent()
        assertEquals(1, c.state.value.pageIndex)
        advanceTimeBy(29_900)
        assertEquals(1, c.state.value.pageIndex)
        advanceTimeBy(200)
        assertEquals(0, c.state.value.pageIndex)
        assertEquals("sélection d'avant retrouvée", 1, c.state.value.focusedIndex)
        assertFalse(c.state.value.exitRequested)
    }

    @Test
    fun `duration - l'application cachee retourne en arriere-plan`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p2", durationSec = 10))
        runCurrent()
        c.onUiVisibilityChanged(true) // L'activité est passée au premier plan.
        advanceTimeBy(10_100)
        assertTrue(c.state.value.exitRequested)
        assertEquals(0, c.state.value.pageIndex)
        c.onExitHandled()
        assertFalse(c.state.value.exitRequested)
    }

    @Test
    fun `duration - annulee par une touche de la telecommande`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "p2", durationSec = 10))
        runCurrent()
        c.press(Right)
        advanceTimeBy(20_000)
        assertEquals("l'affichage devient définitif", 1, c.state.value.pageIndex)
    }

    @Test
    fun `duration 0 - sans retour, et deux show gardent l'ecran d'origine`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "p2", durationSec = 0))
        runCurrent()
        advanceTimeBy(120_000)
        assertEquals(1, c.state.value.pageIndex)

        factory.send(TvCommand.Show(2, "p3", durationSec = 10))
        factory.send(TvCommand.Show(3, "p1", durationSec = 10))
        runCurrent()
        assertEquals(0, c.state.value.pageIndex)
        advanceTimeBy(10_100)
        assertEquals("retour à p2, l'écran d'avant le premier show temporaire", 1, c.state.value.pageIndex)
    }

    @Test
    fun `dedoublonnage par id`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(7, "p2"))
        runCurrent()
        c.press(ChannelUp)
        assertEquals(2, c.state.value.pageIndex)
        factory.send(TvCommand.Show(7, "p2"))
        runCurrent()
        assertEquals("id déjà traité : ignoré", 2, c.state.value.pageIndex)
        factory.send(TvCommand.Show(8, "p2"))
        runCurrent()
        assertEquals(1, c.state.value.pageIndex)
    }

    // --- exit et notify ------------------------------------------------------------------------

    @Test
    fun `exit - passage en arriere-plan si visible`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        c.press(Digit(4))
        factory.send(TvCommand.Exit(1))
        runCurrent()
        assertTrue(c.state.value.exitRequested)
        assertNull(c.state.value.adjust)
        c.onExitHandled()
        c.onUiVisibilityChanged(false)

        factory.send(TvCommand.Exit(2))
        runCurrent()
        assertFalse("déjà en arrière-plan", c.state.value.exitRequested)
    }

    @Test
    fun `exit annule un retour automatique`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, visible = true)
        factory.send(TvCommand.Show(1, "p2", durationSec = 10), TvCommand.Exit(2))
        runCurrent()
        c.onExitHandled()
        advanceTimeBy(20_000)
        assertEquals(1, c.state.value.pageIndex)
        assertFalse(c.state.value.exitRequested)
    }

    @Test
    fun `notify - bandeau de 8 s si visible, ignore sinon`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "Sonnette", "Quelqu'un à la porte"))
        runCurrent()
        assertNull(c.state.value.banner)

        c.onUiVisibilityChanged(true)
        factory.send(TvCommand.Notify(2, "", "Lave-linge terminé"))
        runCurrent()
        assertEquals(Banner("", "Lave-linge terminé", durationMs = 8_000), c.state.value.banner.sansIdentite())
        advanceTimeBy(7_900)
        assertTrue(c.state.value.banner != null)
        advanceTimeBy(200)
        assertNull(c.state.value.banner)
    }

    @Test
    fun `notify juste apres un show qui ramene l'application au premier plan`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Show(1, "p2", durationSec = 20), TvCommand.Notify(2, "Sonnette", "Quelqu'un sonne"))
        runCurrent()
        assertEquals(Banner("Sonnette", "Quelqu'un sonne", durationMs = 8_000), c.state.value.banner.sansIdentite())
    }

    // --- État signalé à Jeedom -----------------------------------------------------------------

    @Test
    fun `etat envoye apres la connexion puis a chaque changement, avec anti-rebond`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        advanceTimeBy(400)
        assertEquals(listOf(TvState(visible = false, screenOn = true, page = "p1")), factory.states)

        c.onUiVisibilityChanged(true)
        c.press(ChannelUp)
        c.press(ChannelUp)
        advanceTimeBy(200)
        assertEquals("anti-rebond", 1, factory.states.size)
        advanceTimeBy(200)
        assertEquals(TvState(visible = true, screenOn = true, page = "p3"), factory.states.last())
        assertEquals(2, factory.states.size)

        c.press(Right) // Sélection : pas un changement d'état.
        advanceTimeBy(400)
        assertEquals(2, factory.states.size)

        c.press(Menu)
        c.onScreenChanged(false)
        advanceTimeBy(400)
        assertEquals(TvState(visible = true, screenOn = false, page = null), factory.states.last())
    }

    @Test
    fun `etat renvoye a chaque (re)demarrage de la boucle`() = runTest {
        val factory = FakeDriverFactory()
        started(factory)
        advanceTimeBy(400)
        assertEquals(1, factory.states.size)

        factory.pushChanges(changes("1"))
        advanceTimeBy(400)
        assertEquals("premier appel réussi de la boucle", 2, factory.states.size)

        factory.pushChanges(changes("2"))
        advanceTimeBy(400)
        assertEquals("appel suivant : rien", 2, factory.states.size)

        factory.failChanges(JeedomException("Jeedom injoignable"))
        advanceTimeBy(3_100)
        factory.pushChanges(changes("3"))
        advanceTimeBy(400)
        assertEquals("reconnexion", 3, factory.states.size)
    }

    @Test
    fun `chaque etat envoye porte la version de l'application`() = runTest {
        val factory = FakeDriverFactory()
        val c = AppController(
            AppModel(AppState()), FakeSettings(stored = config), factory, backgroundScope, appVersion = "0.4.0",
        )
        c.start()
        runCurrent()
        advanceTimeBy(400)
        c.onUiVisibilityChanged(true)
        advanceTimeBy(400)
        assertEquals(2, factory.states.size)
        assertTrue(factory.states.all { it.appVersion == "0.4.0" })
    }

    @Test
    fun `erreur d'envoi de l'etat ignoree`() = runTest {
        val factory = FakeDriverFactory().apply { onState = { throw JeedomException("Jeedom injoignable") } }
        val c = started(factory, visible = true)
        advanceTimeBy(400)
        c.press(ChannelUp)
        advanceTimeBy(400)
        assertEquals(2, factory.states.size)
        assertEquals(Screen.Pages, c.state.value.screen)
        assertNull(c.state.value.notice)
    }
}
