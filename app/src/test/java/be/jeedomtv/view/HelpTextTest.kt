package be.jeedomtv.view

import be.jeedomtv.controller.contractLayout
import be.jeedomtv.model.Adjust
import be.jeedomtv.model.AppState
import be.jeedomtv.model.FocusZone
import be.jeedomtv.model.PendingAction
import be.jeedomtv.model.TileAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rappel des touches, court et à jour : écran des pages, panneau, onglets, modes qui gardent la main. */
class HelpTextTest {

    private val pages = AppState(pages = contractLayout().pages)

    @Test
    fun `premiere rangee - naviguer, action de la tuile, montee aux onglets`() {
        assertEquals(
            "◀▶▲▼ naviguer · OK allumer / éteindre · 1-9 tuile · ▲ pages · Menu réglages · Retour quitter",
            helpText(pages),
        )
        assertEquals(
            "◀▶▲▼ naviguer · OK allumer / éteindre · 1-9 tuile · ▲ pages · Menu ouvrir l'app · Retour fermer",
            panelHelpText(pages),
        )
    }

    @Test
    fun `deuxieme rangee - pas de rappel des onglets, tuile info sans action`() {
        val text = helpText(pages.copy(focusedIndex = 4))
        assertEquals("◀▶▲▼ naviguer · 1-9 tuile · Menu réglages · Retour quitter", text)
    }

    @Test
    fun `onglets - aide propre aux onglets`() {
        val tabs = pages.copy(focusZone = FocusZone.Tabs)
        assertEquals("◀▶ changer de page · ▼ / OK tuiles · Retour tuiles", helpText(tabs))
        assertEquals("◀▶ changer de page · ▼ / OK tuiles · Retour tuiles", panelHelpText(tabs))
    }

    @Test
    fun `modes - confirmation, reglage d'un volet avec ou sans position, curseur`() {
        assertEquals("OK confirmer · Retour annuler", helpText(pages.copy(confirm = PendingAction("t6", TileAction.Run, label = "Lancer"))))
        assertEquals(
            "▲▼ régler · ◀▶ fermé / ouvert · OK envoyer · Retour annuler",
            panelHelpText(pages.copy(adjust = Adjust("t2", 40.0))),
        )
        assertEquals("▲ monter · ▼ descendre · OK stop · Retour sortir", helpText(pages.copy(adjust = Adjust("t3", null))))
        assertEquals("▲▼ régler · ◀▶ min / max · OK envoyer · Retour annuler", helpText(pages.copy(adjust = Adjust("t4", 20.5))))
    }

    @Test
    fun `plus aucune mention de CH+ CH-`() {
        val states = listOf(
            pages,
            pages.copy(focusedIndex = 5),
            pages.copy(focusZone = FocusZone.Tabs),
            pages.copy(adjust = Adjust("t2", 40.0)),
            pages.copy(adjust = Adjust("t3", null)),
        )
        for (state in states) {
            assertFalse(helpText(state), helpText(state).contains("CH"))
            assertFalse(panelHelpText(state), panelHelpText(state).contains("CH"))
            assertTrue(helpText(state).length <= 100)
        }
    }
}
