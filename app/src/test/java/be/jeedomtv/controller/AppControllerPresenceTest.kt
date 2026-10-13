package be.jeedomtv.controller

import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Capabilities
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.Screen
import be.jeedomtv.model.ShowFallback
import be.jeedomtv.model.ShowMode
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.WokeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Présence humaine et allumage : état signalé, ordres wake / sleep, `show` en mode auto, `sleepAfter`. */
class AppControllerPresenceTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")
    private val all = Capabilities(keys = true, foreground = true, media = true, wake = true, sleep = true)

    /** Écran de la TV piloté par le test : `wake` l'allume (si permis), `sleep` le note. */
    private class FakeScreenPower(var canWake: Boolean = true, var canSleep: Boolean = true) : ScreenPower {
        var wakes = 0
        var sleeps = 0
        override fun wake(): Boolean {
            if (canWake) wakes++
            return canWake
        }
        override fun sleep(): Boolean {
            if (canSleep) sleeps++
            return canSleep
        }
    }

    private fun TestScope.started(
        factory: FakeDriverFactory = FakeDriverFactory(),
        power: FakeScreenPower = FakeScreenPower(),
        screenOn: Boolean = false,
        overlay: Boolean = true,
        capabilities: Capabilities? = all,
    ): AppController {
        val c = AppController(
            AppModel(AppState(uiVisible = false)), FakeSettings(stored = config), factory, backgroundScope,
            overlayPermission = { overlay },
            elapsedMs = { testScheduler.currentTime },
            screenPower = power,
        )
        c.start()
        runCurrent()
        c.onScreenChanged(screenOn)
        capabilities?.let { c.onCapabilities(it, emptyList()) }
        runCurrent()
        return c
    }

    private fun FakeDriverFactory.send(vararg commands: TvCommand) {
        pushChanges(commands("c${changesCalls.size}", *commands))
    }

    /** L'écran s'allume (diffusion ACTION_SCREEN_ON) un peu après la demande. */
    private fun TestScope.screenComesOn(c: AppController) {
        advanceTimeBy(1_500)
        c.onScreenChanged(true)
        runCurrent()
    }

    private val AppController.presence get() = state.value.presence!!

    // --- État signalé -----------------------------------------------------------------------

    @Test
    fun `sans releve des permissions - l'etat n'a pas les champs de presence`() = runTest {
        val factory = FakeDriverFactory()
        started(factory, screenOn = true, capabilities = null)
        advanceTimeBy(1_000)
        assertTrue(factory.states.isNotEmpty())
        assertTrue(factory.states.all { it.presence == null && it.idleSeconds == null })
    }

    @Test
    fun `etat envoye au demarrage avec capabilities, puis a chaque changement de champ`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, screenOn = true)
        advanceTimeBy(1_000)
        val first = factory.states.last()
        assertEquals(all, first.presence!!.capabilities)
        assertNull("aucune touche encore", first.idleSeconds)
        assertNull("démarrage écran allumé", first.presence!!.wokeBy)

        val before = factory.states.size
        c.onForegroundApp("com.netflix.ninja")
        advanceTimeBy(1_000)
        assertEquals(before + 1, factory.states.size)
        assertEquals("com.netflix.ninja", factory.states.last().presence!!.foreground)
        assertTrue(factory.states.last().presence!!.inUse)

        c.onCapabilities(all.copy(keys = false), emptyList())
        advanceTimeBy(1_000)
        assertFalse("service d'accessibilité désactivé", factory.states.last().presence!!.capabilities.keys)
    }

    @Test
    fun `touches - jamais un etat par touche, au plus un par minute`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, screenOn = true)
        c.onForegroundApp("com.tcl.tv") // déjà en usage : les touches ne changent pas inUse
        advanceTimeBy(1_000)
        val before = factory.states.size
        repeat(120) {
            c.onRemoteKeyObserved()
            advanceTimeBy(1_000)
        }
        val sent = factory.states.size - before
        assertEquals("2 min de touches : la première, puis une par minute", 2, sent)
        assertEquals(0, factory.states.last().idleSeconds)
    }

    @Test
    fun `inUse repasse a faux 10 min apres la derniere touche`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, screenOn = true)
        c.onRemoteKeyObserved()
        advanceTimeBy(1_000)
        assertTrue(factory.states.last().presence!!.inUse)
        advanceTimeBy(10 * 60_000L)
        assertFalse(factory.states.last().presence!!.inUse)
        assertEquals(600, factory.states.last().idleSeconds)
    }

    // --- wake et sleep ----------------------------------------------------------------------

    @Test
    fun `ordre wake - ecran allume par nous - wokeBy jeedom, puis sleep met en veille`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power)
        factory.send(TvCommand.Wake(1))
        runCurrent()
        assertEquals(1, power.wakes)
        screenComesOn(c)
        assertEquals(WokeBy.Jeedom, c.presence.wokeBy)
        assertFalse(c.presence.humanTookOver)

        factory.send(TvCommand.Sleep(2))
        runCurrent()
        assertEquals(1, power.sleeps)
    }

    @Test
    fun `ordre wake puis une touche - sleep refuse, et l'etat suivant le dit`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power)
        factory.send(TvCommand.Wake(1))
        runCurrent()
        screenComesOn(c)
        advanceTimeBy(5_000)
        c.onRemoteKeyObserved()
        advanceTimeBy(1_000)
        assertTrue(c.presence.humanTookOver)

        val before = factory.states.size
        factory.send(TvCommand.Sleep(2))
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(0, power.sleeps)
        assertTrue("état renvoyé", factory.states.size > before)
        assertTrue(factory.states.last().presence!!.humanTookOver)
    }

    @Test
    fun `allumage a la telecommande - human, sleep refuse, force passe`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power)
        c.onScreenChanged(true)
        runCurrent()
        assertEquals(WokeBy.Human, c.presence.wokeBy)
        advanceTimeBy(5_000)
        c.onRemoteKeyObserved()
        factory.send(TvCommand.Sleep(1))
        runCurrent()
        assertEquals(0, power.sleeps)
        factory.send(TvCommand.Sleep(2, force = true))
        runCurrent()
        assertEquals(1, power.sleeps)
    }

    @Test
    fun `regle 3 - reveil par un autre chemin puis ordre wake sans touche - jeedom`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power)
        c.onScreenChanged(true)
        advanceTimeBy(20_000)
        assertEquals(WokeBy.Human, c.presence.wokeBy)
        factory.send(TvCommand.Wake(1))
        runCurrent()
        assertEquals("écran déjà allumé : sans effet", 0, power.wakes)
        assertEquals(WokeBy.Jeedom, c.presence.wokeBy)
    }

    @Test
    fun `sleep refuse pendant une question`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power)
        factory.send(TvCommand.Wake(1))
        runCurrent()
        screenComesOn(c)
        factory.send(TvCommand.Ask(2, "jeton", "", "Ouvrir ?", listOf("Non", "Oui"), 30))
        runCurrent()
        assertNotNull(c.state.value.question)
        factory.send(TvCommand.Sleep(3))
        runCurrent()
        assertEquals(0, power.sleeps)
    }

    @Test
    fun `notify avec wake - affiche une fois l'ecran allume, temps compte depuis l'affichage`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "Linge", "Le linge est sec", durationSec = 10, wake = true))
        runCurrent()
        assertTrue("en attente de l'allumage", c.state.value.overlay == Overlay.None && c.state.value.banner == null)
        screenComesOn(c)
        assertTrue(c.state.value.overlay is Overlay.Notice)
        advanceTimeBy(9_000)
        assertTrue("10 s depuis l'affichage réel", c.state.value.overlay is Overlay.Notice)
    }

    @Test
    fun `wake sans permission - l'ordre est execute tout de suite`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, FakeScreenPower(canWake = false))
        factory.send(TvCommand.Notify(1, "", "Bonjour", wake = true))
        runCurrent()
        assertTrue(c.state.value.overlay is Overlay.Notice)
    }

    @Test
    fun `ecran qui ne s'allume pas - l'ordre part apres le delai`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.send(TvCommand.Notify(1, "", "Bonjour", wake = true))
        runCurrent()
        assertEquals(Overlay.None, c.state.value.overlay)
        advanceTimeBy(10_001)
        assertTrue(c.state.value.overlay is Overlay.Notice)
    }

    // --- show : mode auto et sleepAfter ------------------------------------------------------

    @Test
    fun `mode auto, Netflix en lecture - seulement la notification discrete, aucun changement d'application`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, screenOn = true)
        c.onForegroundApp("com.netflix.ninja")
        c.onMediaPlaying(true)
        factory.send(
            TvCommand.Show(1, "p2", 30, mode = ShowMode.Auto, fallback = ShowFallback("Trains", "IC 1706 : +4 min", "mdi:train", 20))
        )
        runCurrent()
        assertFalse("pas d'ouverture de l'application", c.state.value.foregroundRequested)
        val notice = c.state.value.overlay as Overlay.Notice
        assertEquals("Trains", notice.banner.title)
        assertEquals("IC 1706 : +4 min", notice.banner.message)
        assertEquals("mdi:train", notice.banner.icon)
        assertEquals(20_000L, notice.banner.durationMs)
    }

    @Test
    fun `mode auto sans fallback - titre = nom de la page, 15 s`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, screenOn = true)
        c.onForegroundApp("com.tcl.tv")
        factory.send(TvCommand.Show(1, "p2", 30, mode = ShowMode.Auto))
        runCurrent()
        val notice = c.state.value.overlay as Overlay.Notice
        assertEquals("Cuisine", notice.banner.title)
        assertEquals("", notice.banner.message)
        assertEquals(15_000L, notice.banner.durationMs)
    }

    @Test
    fun `mode auto, TV pas en usage - comme full`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, screenOn = true)
        c.onForegroundApp("com.google.android.apps.tv.launcherx")
        factory.send(TvCommand.Show(1, "p2", 30, mode = ShowMode.Auto))
        runCurrent()
        assertEquals(Overlay.Panel("p2", 30), c.state.value.overlay)
    }

    @Test
    fun `mode full - comportement actuel, meme TV en usage`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory, screenOn = true)
        c.onForegroundApp("com.netflix.ninja")
        c.onMediaPlaying(true)
        factory.send(TvCommand.Show(1, "p2", 30))
        runCurrent()
        assertEquals(Overlay.Panel("p2", 30), c.state.value.overlay)

        val noOverlay = FakeDriverFactory()
        val d = started(noOverlay, screenOn = true, overlay = false)
        d.onMediaPlaying(true)
        noOverlay.send(TvCommand.Show(1, "p2", 30))
        runCurrent()
        assertTrue(d.state.value.foregroundRequested)
        assertEquals(Screen.Pages, d.state.value.screen)
    }

    @Test
    fun `show wake + sleepAfter - reveil, page, puis veille a la fin`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power, overlay = false)
        factory.send(TvCommand.Show(1, "p2", 30, mode = ShowMode.Auto, wake = true, sleepAfter = true))
        runCurrent()
        assertEquals(1, power.wakes)
        screenComesOn(c)
        assertTrue("plein écran : la TV n'était pas en usage", c.state.value.foregroundRequested)
        c.onUiVisibilityChanged(true)
        c.onForegroundApp("be.jeedomtv")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, power.sleeps)
        assertTrue("retour à l'application d'avant", c.state.value.exitRequested)
    }

    @Test
    fun `show sleepAfter - une touche entre-temps - pas de veille`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power)
        factory.send(TvCommand.Show(1, "p2", 30, wake = true, sleepAfter = true))
        runCurrent()
        screenComesOn(c)
        assertEquals(Overlay.Panel("p2", 30), c.state.value.overlay)
        advanceTimeBy(10_000)
        c.onRemoteKeyObserved()
        advanceTimeBy(25_000)
        assertEquals(0, power.sleeps)
    }

    @Test
    fun `show sleepAfter sur le panneau - veille a la fin si personne n'a touche`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power)
        factory.send(TvCommand.Show(1, "p2", 30, wake = true, sleepAfter = true))
        runCurrent()
        screenComesOn(c)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(Overlay.None, c.state.value.overlay)
        assertEquals(1, power.sleeps)
    }

    @Test
    fun `show sleepAfter, TV allumee par un humain - retour normal, pas de veille`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power)
        c.onScreenChanged(true)
        advanceTimeBy(70_000)
        factory.send(TvCommand.Show(1, "p2", 30, sleepAfter = true))
        runCurrent()
        advanceTimeBy(31_000)
        assertEquals(0, power.sleeps)
        assertEquals(Overlay.None, c.state.value.overlay)
    }

    @Test
    fun `retour de l'application d'avant apres un show - pas une prise en main`() = runTest {
        val factory = FakeDriverFactory()
        val power = FakeScreenPower()
        val c = started(factory, power, overlay = false)
        factory.send(TvCommand.Show(1, "p2", 10, wake = true))
        runCurrent()
        screenComesOn(c)
        c.onUiVisibilityChanged(true)
        c.onForegroundApp("be.jeedomtv")
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(c.state.value.exitRequested)
        c.onExitHandled()
        c.onUiVisibilityChanged(false)
        advanceTimeBy(1_000)
        c.onForegroundApp("com.tcl.tv")
        assertFalse(c.presence.humanTookOver)
        advanceTimeBy(10_000)
        c.onForegroundApp("com.netflix.ninja")
        assertTrue(c.presence.humanTookOver)
    }
}
