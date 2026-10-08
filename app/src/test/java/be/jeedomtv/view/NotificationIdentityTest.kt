package be.jeedomtv.view

import be.jeedomtv.model.Banner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Identité stable des cartes : l'image qui arrive ne rejoue ni l'entrée ni la barre. */
class NotificationIdentityTest {

    @Test
    fun `image arrivee - meme cle de carte, alors que l'egalite de data class change`() {
        val avant = Banner("Portier", "On sonne", image = "i1", id = 7, endsAtMs = 10_000)
        val apres = avant.copy(imageBytes = byteArrayOf(1, 2, 3))
        assertNotEquals("ByteArray : égalité par référence", avant, apres)
        assertEquals(avant.cardKey, apres.cardKey)
        assertEquals(apres.cardKey, apres.copy(imageBytes = byteArrayOf(1, 2, 3)).cardKey)
    }

    @Test
    fun `image en echec - carte texte`() {
        val banner = Banner("Portier", "On sonne", image = "i1", id = 1)
        assertFalse(banner.copy(imageFailed = true).isMediaCard(videoAllowed = true))
    }

    @Test
    fun `barre du temps restant - part de ce qu'il reste`() {
        assertEquals(1f, remainingFraction(8_000, 8_000))
        assertEquals(0.5f, remainingFraction(4_000, 8_000))
        assertEquals(0f, remainingFraction(-50, 8_000))
    }

    @Test
    fun `compte a rebours de la question - deduit de l'echeance`() {
        assertEquals(10, remainingSeconds(deadlineMs = 20_000, nowMs = 10_000))
        assertEquals(10, remainingSeconds(deadlineMs = 20_000, nowMs = 10_001))
        assertEquals(1, remainingSeconds(deadlineMs = 20_000, nowMs = 19_999))
        assertEquals(0, remainingSeconds(deadlineMs = 20_000, nowMs = 21_000))
    }
}
