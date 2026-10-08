package be.jeedomtv.controller

import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.driver.JeedomException
import be.jeedomtv.view.UNREACHABLE_ALPHA
import be.jeedomtv.view.statusBarAlpha
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jeedom injoignable : barre d'état grisée après 3 erreurs d'affilée ou une minute sans réponse. */
class AppControllerReachableTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    private fun TestScope.started(factory: FakeDriverFactory): AppController {
        val c = AppController(AppModel(AppState(uiVisible = true)), FakeSettings(stored = config), factory, backgroundScope)
        c.start()
        runCurrent()
        return c
    }

    private val AppController.reachable get() = state.value.jeedomReachable

    @Test
    fun `trois erreurs d'affilee - injoignable, la reponse suivante retablit aussitot`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        factory.failChanges(JeedomException("Jeedom injoignable"))
        runCurrent()
        assertTrue("une erreur : seulement « hors ligne »", c.state.value.offline)
        assertTrue(c.reachable)
        factory.failChanges(JeedomException("Jeedom injoignable"))
        advanceTimeBy(3_100)
        assertTrue("deux erreurs : encore joignable", c.reachable)
        factory.failChanges(JeedomException("Jeedom injoignable"))
        advanceTimeBy(6_100)
        assertFalse("troisième erreur", c.reachable)
        advanceTimeBy(12_100)
        factory.pushChanges(changes("200"))
        runCurrent()
        assertTrue("retour immédiat", c.reachable)
        assertFalse(c.state.value.offline)
    }

    @Test
    fun `aucune reponse pendant une minute - injoignable`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        // L'attente longue reste bloquée (réseau mort sans erreur) : aucune réponse.
        advanceTimeBy(59_900)
        assertTrue(c.reachable)
        advanceTimeBy(200)
        assertFalse(c.reachable)
        factory.pushChanges(changes("300"))
        runCurrent()
        assertTrue(c.reachable)
    }

    @Test
    fun `reponses regulieres - toujours joignable`() = runTest {
        val factory = FakeDriverFactory()
        val c = started(factory)
        repeat(6) {
            advanceTimeBy(25_000)
            factory.pushChanges(changes("s$it"))
            runCurrent()
        }
        assertTrue(c.reachable)
    }

    @Test
    fun `opacite de la barre - reduite a 40 % quand Jeedom est injoignable`() {
        assertEquals(0.85f, statusBarAlpha(85, unreachable = false), 0.001f)
        assertEquals(0.85f * UNREACHABLE_ALPHA, statusBarAlpha(85, unreachable = true), 0.001f)
        assertEquals(0.4f, statusBarAlpha(100, unreachable = true), 0.001f)
    }
}
