package be.jeedomtv.view

import be.jeedomtv.model.AppState
import be.jeedomtv.model.Question
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mise en page de la question : bandeau compact sans image par-dessus la vidéo, boîte au centre sinon. */
class QuestionLayoutTest {

    private fun question(image: String? = null, inOverlay: Boolean = true, answers: List<String> = listOf("Ignorer", "Ouvrir")) =
        Question("jeton", "", "Ouvrir le portail ?", answers, 30, 30, inOverlay = inOverlay, image = image)

    @Test
    fun `sans question - pas de fenetre`() {
        assertEquals(QuestionWindowKind.None, questionWindowKind(AppState()))
    }

    @Test
    fun `question dans l'application - pas de fenetre de superposition`() {
        assertEquals(QuestionWindowKind.None, questionWindowKind(AppState(question = question(inOverlay = false))))
    }

    @Test
    fun `sans image par-dessus la video - bandeau compact en bas, la camera reste visible`() {
        assertEquals(QuestionWindowKind.Banner, questionWindowKind(AppState(question = question())))
    }

    @Test
    fun `avec image - boite au centre, meme avant le telechargement de la photo`() {
        assertEquals(QuestionWindowKind.Dialog, questionWindowKind(AppState(question = question(image = "a3f9"))))
        assertEquals(
            QuestionWindowKind.Dialog,
            questionWindowKind(AppState(question = question(image = "a3f9").copy(imageBytes = byteArrayOf(1)))),
        )
    }

    @Test
    fun `rappel des touches - chiffres seulement jusqu'a 9 reponses`() {
        assertEquals("◀ ▶ ou 1-2 : choisir · OK : répondre · Retour : fermer", questionHelpText(question()))
        assertEquals("OK : répondre · Retour : fermer", questionHelpText(question(answers = listOf("OK"))))
        assertEquals(
            "◀ ▶ : choisir · OK : répondre · Retour : fermer",
            questionHelpText(question(answers = (1..12).map { "R$it" })),
        )
    }
}
