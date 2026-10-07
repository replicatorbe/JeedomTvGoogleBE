package be.jeedomtv.view

import be.jeedomtv.controller.contractLayout
import be.jeedomtv.model.AppState
import be.jeedomtv.model.FocusZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aide en bas de l'écran et du panneau : onglets et première rangée de tuiles. */
class HelpTextTest {

    private val pages = AppState(pages = contractLayout().pages)

    @Test
    fun `premiere rangee - rappel de la montee aux onglets a la place de CH+ CH-`() {
        for (text in listOf(helpText(pages.copy(focusedIndex = 2)), panelHelpText(pages.copy(focusedIndex = 0)))) {
            assertTrue(text, text.contains("▲ : pages"))
            assertFalse(text, text.contains("CH+/CH-"))
        }
        assertEquals(
            "Flèches : choisir · OK : allumer / éteindre · 1-9 : tuile · ▲ : pages · Menu : réglages · Retour : quitter",
            helpText(pages),
        )
    }

    @Test
    fun `deuxieme rangee - CH+ CH- comme avant`() {
        for (text in listOf(helpText(pages.copy(focusedIndex = 4)), panelHelpText(pages.copy(focusedIndex = 5)))) {
            assertFalse(text, text.contains("▲ : pages"))
            assertTrue(text, text.contains("CH+/CH- : page"))
        }
    }

    @Test
    fun `onglets - l'aide des onglets, avec CH+ CH-`() {
        val tabs = pages.copy(focusZone = FocusZone.Tabs)
        val expected = "◀ ▶ : changer de page · ▼ / OK : tuiles · Retour : tuiles · CH+/CH- : page"
        assertEquals(expected, helpText(tabs))
        assertEquals(expected, panelHelpText(tabs))
    }
}
