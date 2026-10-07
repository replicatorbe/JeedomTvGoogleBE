package be.jeedomtv.view

import be.jeedomtv.model.Banner
import be.jeedomtv.model.VideoUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carte « image dans l'image » ou bandeau texte, selon ce que porte la notification. */
class NotificationCardTest {

    private val camera = VideoUrl("rtsp://camera.example/flux")

    @Test
    fun `video - carte, si le decodeur est libre`() {
        assertTrue(Banner("Sonnette", "On sonne", video = camera).isMediaCard(videoAllowed = true))
        assertFalse("question avec vidéo en cours, pas d'image : texte", Banner("Sonnette", "On sonne", video = camera).isMediaCard(videoAllowed = false))
    }

    @Test
    fun `image - carte, avec ou sans video`() {
        assertTrue(Banner("Portier", "Photo", image = "i1").isMediaCard(videoAllowed = true))
        assertTrue("vidéo refusée : la carte montre l'image", Banner("Portier", "Photo", image = "i1", video = camera).isMediaCard(videoAllowed = false))
    }

    @Test
    fun `ni image ni video - bandeau texte`() {
        assertFalse(Banner("Buanderie", "Lave-linge terminé", icon = "mdi:washing-machine").isMediaCard(videoAllowed = true))
    }
}
