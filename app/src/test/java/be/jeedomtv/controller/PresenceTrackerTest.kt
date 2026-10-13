package be.jeedomtv.controller

import be.jeedomtv.model.Capabilities
import be.jeedomtv.model.ShowMode
import be.jeedomtv.model.SleepOutcome
import be.jeedomtv.model.SleepResult
import be.jeedomtv.model.WokeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calcul de `inUse`, choix de `mode: auto`, règle de `sleep` et envoi de l'état sur une touche. */
class PresenceTrackerTest {

    private val allCapabilities = Capabilities(keys = true, foreground = true, media = true, wake = true, sleep = true)

    private fun tracker() = PresenceTracker("be.jeedomtv").apply {
        onCapabilities(allCapabilities)
        onScreen(true, 0)
    }

    // --- inUse ------------------------------------------------------------------------------

    @Test
    fun `sans aucun signal - pas en usage`() {
        assertFalse(tracker().inUse(0))
    }

    @Test
    fun `touche depuis moins de 10 min - en usage, puis plus apres 10 min`() {
        val t = tracker()
        t.onKey(1_000)
        assertTrue(t.inUse(1_000 + PresenceTracker.KEY_IN_USE_MS - 1))
        assertFalse(t.inUse(1_000 + PresenceTracker.KEY_IN_USE_MS))
    }

    @Test
    fun `media en lecture - en usage`() {
        val t = tracker()
        t.onPlaying(true, 0)
        assertTrue(t.inUse(0))
        t.onPlaying(null, 0)
        assertFalse("inconnu : pas deviné", t.inUse(0))
    }

    @Test
    fun `application au premier plan - chaine TV ou HDMI en usage, pas nous ni le lanceur ni l'ecran de veille`() {
        val t = tracker()
        t.onForeground("com.tcl.tv")
        assertTrue("chaîne TV / entrée HDMI", t.inUse(0))
        t.onForeground("com.netflix.ninja")
        assertTrue("application en pause", t.inUse(0))
        t.onForeground("be.jeedomtv")
        assertFalse(t.inUse(0))
        t.onForeground("com.google.android.apps.tv.launcherx")
        assertFalse(t.inUse(0))
        t.onForeground("com.google.android.apps.tv.dreamx")
        assertFalse(t.inUse(0))
        t.onForeground(null)
        assertFalse("inconnu", t.inUse(0))
    }

    @Test
    fun `liste des lanceurs configurable`() {
        val t = tracker()
        t.onForeground("com.tcl.tv.tclhome_passive")
        assertTrue(t.inUse(0))
        t.ignoredPackages = PresenceTracker.parsePackages("com.google.android.apps.tv.launcherx, com.tcl.tv.tclhome_passive")
        assertFalse(t.inUse(0))
    }

    @Test
    fun `ecran eteint ou ecran de veille - jamais en usage`() {
        val t = tracker()
        t.onKey(0)
        t.onPlaying(true, 0)
        t.onForeground("com.netflix.ninja")
        t.onDreaming(true)
        assertFalse(t.inUse(0))
        t.onDreaming(false)
        assertTrue(t.inUse(0))
        t.onScreen(false, 0)
        assertFalse(t.inUse(0))
    }

    @Test
    fun `idleSeconds - depuis l'allumage avant la premiere touche, null sans touches observables`() {
        val t = tracker()
        assertEquals("pas de touche depuis l'allumage", 5, t.idleSeconds(5_000))
        t.onKey(5_000)
        assertEquals(12, t.idleSeconds(17_400))
        t.onScreen(false, 20_000)
        t.onScreen(true, 30_000)
        assertEquals("réallumée sans touche : depuis l'allumage", 3, t.idleSeconds(33_000))
        t.onCapabilities(allCapabilities.copy(keys = false))
        assertNull("service d'accessibilité désactivé", t.idleSeconds(40_000))
        assertFalse(t.inUse(40_000))
    }

    @Test
    fun `allumee par jeedom sans reprise en main - pas en usage, quelle que soit l'application`() {
        val t = PresenceTracker("be.jeedomtv").apply { onCapabilities(allCapabilities); onScreen(false, 0) }
        t.onWakeRequested(0)
        t.onScreen(true, 1_000)
        t.onForeground("com.netflix.ninja")
        t.onForeground("com.tcl.tv")
        val report = t.report(10_000)
        assertEquals(WokeBy.Jeedom, report.wokeBy)
        assertFalse("un changement d'application seul n'est pas une reprise en main", report.humanTookOver)
        assertFalse(report.inUse)
        t.onKey(12_000)
        assertTrue(t.report(12_000).humanTookOver)
        assertTrue("touche : en usage", t.inUse(12_000))
    }

    @Test
    fun `allumee par un humain - application au premier plan en usage`() {
        val t = PresenceTracker("be.jeedomtv").apply { onCapabilities(allCapabilities); onScreen(false, 0) }
        t.onScreen(true, 1_000)
        t.onForeground("com.tcl.tv")
        assertEquals(WokeBy.Human, t.report(5_000).wokeBy)
        assertTrue(t.inUse(5_000))
    }

    @Test
    fun `sleep echoue - capabilities sleep false jusqu'au redemarrage, lastSleep`() {
        val t = tracker()
        assertTrue(t.report(0).capabilities.sleep)
        t.onSleepOutcome(7, SleepOutcome.Refused)
        assertEquals(SleepResult(7, SleepOutcome.Refused), t.report(0).lastSleep)
        assertTrue(t.report(0).capabilities.sleep)
        t.onSleepOutcome(8, SleepOutcome.Failed)
        assertEquals(SleepResult(8, SleepOutcome.Failed), t.report(0).lastSleep)
        assertFalse(t.report(0).capabilities.sleep)
        t.onCapabilities(allCapabilities)
        assertFalse("relevé des permissions : reste false", t.report(0).capabilities.sleep)
    }

    @Test
    fun `premiere lecture d'un media - prise en main, pas une lecture qui continue`() {
        val t = PresenceTracker("be.jeedomtv").apply { onCapabilities(allCapabilities); onScreen(false, 0) }
        t.onPlaying(true, 0)
        t.onWakeRequested(0)
        t.onScreen(true, 0)
        t.onPlaying(true, 10_000)
        assertFalse("déjà en lecture avant le réveil", t.report(10_000).humanTookOver)
        t.onPlaying(false, 11_000)
        t.onPlaying(true, 12_000)
        assertTrue(t.report(12_000).humanTookOver)
    }

    // --- mode auto et sleep -----------------------------------------------------------------

    @Test
    fun `mode auto - discret seulement si la TV est en usage, full jamais`() {
        assertTrue(PresenceRules.showDiscreetly(ShowMode.Auto, inUse = true))
        assertFalse(PresenceRules.showDiscreetly(ShowMode.Auto, inUse = false))
        assertFalse(PresenceRules.showDiscreetly(ShowMode.Full, inUse = true))
        assertFalse(PresenceRules.showDiscreetly(ShowMode.Full, inUse = false))
    }

    @Test
    fun `sleep - regle d'or, ou force`() {
        assertTrue(PresenceRules.sleepAllowed(force = false, mayAutoSleep = true))
        assertFalse(PresenceRules.sleepAllowed(force = false, mayAutoSleep = false))
        assertTrue(PresenceRules.sleepAllowed(force = true, mayAutoSleep = false))
    }

    // --- Envoi de l'état sur une touche -------------------------------------------------------

    @Test
    fun `touches - premiere touche envoyee, puis au plus une fois par minute`() {
        val throttle = KeyReportThrottle()
        assertTrue("première touche", throttle.onKey(0))
        val sent = (1..119).count { throttle.onKey(it * 1_000L) }
        assertEquals("touches chaque seconde pendant 2 min : une par minute", 1, sent)
        assertTrue(throttle.onKey(120_000))
    }

    @Test
    fun `touches - premiere touche apres 60 s de calme envoyee aussitot`() {
        val throttle = KeyReportThrottle()
        throttle.onKey(0)
        assertFalse(throttle.onKey(30_000))
        assertTrue("60 s sans touche", throttle.onKey(90_000))
        assertFalse(throttle.onKey(95_000))
    }

    @Test
    fun `touches - un envoi pour une autre raison compte dans la minute`() {
        val throttle = KeyReportThrottle()
        throttle.onKey(0)
        throttle.onKey(40_000)
        throttle.onSent(50_000)
        assertFalse("10 s après l'envoi", throttle.onKey(60_000))
        assertFalse(throttle.onKey(100_000))
        assertTrue("60 s après l'envoi", throttle.onKey(110_000))
    }

    @Test
    fun `liste de paquets saisie`() {
        assertEquals(
            setOf("a.b", "c.d", "e.f"),
            PresenceTracker.parsePackages(" a.b, c.d;\ne.f ,, "),
        )
    }
}
