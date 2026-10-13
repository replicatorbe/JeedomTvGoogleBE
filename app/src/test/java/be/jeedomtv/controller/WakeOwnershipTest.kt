package be.jeedomtv.controller

import be.jeedomtv.model.WokeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Propriété de l'allumage : règles 1 à 4 du contrat et règle d'or. */
class WakeOwnershipTest {

    /** Écran observé éteint au départ (TV en veille au démarrage de l'application). */
    private fun asleep() = WakeOwnership().apply { onScreen(false, 0) }

    @Test
    fun `demarrage ecran allume - wokeBy inconnu, pas de veille d'elle-meme`() {
        val o = WakeOwnership().apply { onScreen(true, 0) }
        assertNull(o.wokeBy)
        assertFalse(o.humanTookOver)
        assertFalse(o.mayAutoSleep(questionShown = false))
    }

    @Test
    fun `regle 1 - l'application reveille l'ecran - jeedom`() {
        val o = asleep()
        o.onWakeRequested(1_000)
        o.onScreen(true, 3_000)
        assertEquals(WokeBy.Jeedom, o.wokeBy)
        assertFalse(o.humanTookOver)
        assertTrue(o.mayAutoSleep(questionShown = false))
    }

    @Test
    fun `regle 1 - demande trop ancienne - l'allumage n'est pas le notre`() {
        val o = asleep()
        o.onWakeRequested(1_000)
        o.onScreen(true, 1_000 + WakeOwnership.OWN_WAKE_WINDOW_MS + 1)
        assertEquals(WokeBy.Human, o.wokeBy)
    }

    @Test
    fun `regle 2 - allumage sans demande - human, pas de veille d'elle-meme`() {
        val o = asleep()
        o.onScreen(true, 5_000)
        assertEquals(WokeBy.Human, o.wokeBy)
        assertFalse("sans objet", o.humanTookOver)
        assertFalse(o.mayAutoSleep(questionShown = false))
    }

    @Test
    fun `regle 3 - ordre wake dans les 60 s sans touche - attribue a jeedom`() {
        val o = asleep()
        o.onScreen(true, 10_000)
        o.onWakeOrder(10_000 + 59_000)
        assertEquals(WokeBy.Jeedom, o.wokeBy)
        assertFalse(o.humanTookOver)
        assertTrue(o.mayAutoSleep(questionShown = false))
    }

    @Test
    fun `regle 3 - ordre wake apres 60 s - reste human`() {
        val o = asleep()
        o.onScreen(true, 10_000)
        o.onWakeOrder(10_000 + 60_001)
        assertEquals(WokeBy.Human, o.wokeBy)
    }

    @Test
    fun `regle 3 - une touche entre l'allumage et l'ordre - reste human`() {
        val o = asleep()
        o.onScreen(true, 10_000)
        o.onKey(15_000)
        o.onWakeOrder(20_000)
        assertEquals(WokeBy.Human, o.wokeBy)
        assertFalse(o.mayAutoSleep(questionShown = false))
    }

    @Test
    fun `regle 3 - touche dans les 2 s parasites du CEC - ignoree, attribution a jeedom`() {
        val o = asleep()
        o.onScreen(true, 10_000)
        o.onKey(11_500)
        o.onWakeOrder(20_000)
        assertEquals(WokeBy.Jeedom, o.wokeBy)
    }

    @Test
    fun `regle 3 - sans effet sur un ecran allume au demarrage`() {
        val o = WakeOwnership().apply { onScreen(true, 0) }
        o.onWakeOrder(1_000)
        assertNull(o.wokeBy)
    }

    @Test
    fun `regle 4 - premiere touche apres un reveil par jeedom - humanTookOver`() {
        val o = asleep()
        o.onWakeRequested(0)
        o.onScreen(true, 1_000)
        o.onKey(1_000 + 2_000)
        assertTrue(o.humanTookOver)
        assertFalse(o.mayAutoSleep(questionShown = false))
    }

    @Test
    fun `regle 4 - les 2 s qui suivent le reveil sont ignorees`() {
        val o = asleep()
        o.onWakeRequested(0)
        o.onScreen(true, 1_000)
        o.onKey(2_999)
        o.onForegroundChangedByHuman(2_500)
        o.onPlaybackStarted(2_000)
        assertFalse(o.humanTookOver)
        assertTrue(o.mayAutoSleep(questionShown = false))
    }

    @Test
    fun `regle 4 - changement d'application ou lecture d'un media - humanTookOver`() {
        val a = asleep().apply { onWakeRequested(0); onScreen(true, 0) }
        a.onForegroundChangedByHuman(5_000)
        assertTrue(a.humanTookOver)
        val b = asleep().apply { onWakeRequested(0); onScreen(true, 0) }
        b.onPlaybackStarted(5_000)
        assertTrue(b.humanTookOver)
    }

    @Test
    fun `humanTookOver reste vrai jusqu'a la veille, puis tout est remis a zero`() {
        val o = asleep()
        o.onWakeRequested(0)
        o.onScreen(true, 0)
        o.onKey(10_000)
        o.onScreen(true, 20_000) // même état répété : rien ne change
        assertTrue(o.humanTookOver)
        o.onScreen(false, 30_000)
        assertNull(o.wokeBy)
        assertFalse(o.humanTookOver)
        o.onScreen(true, 40_000)
        assertEquals("nouvel allumage sans demande", WokeBy.Human, o.wokeBy)
    }

    @Test
    fun `touche ecran eteint - ignoree`() {
        val o = asleep()
        o.onKey(1_000)
        o.onWakeRequested(2_000)
        o.onScreen(true, 3_000)
        assertFalse(o.humanTookOver)
    }

    @Test
    fun `regle d'or fausse pendant une question`() {
        val o = asleep()
        o.onWakeRequested(0)
        o.onScreen(true, 0)
        assertFalse(o.mayAutoSleep(questionShown = true))
    }
}
