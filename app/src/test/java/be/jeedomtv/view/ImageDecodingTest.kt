package be.jeedomtv.view

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDecodingTest {

    @Test
    fun `photo de portier en 1280x720 - decodee telle quelle`() {
        assertEquals(1, sampleSizeFor(1280, 720, 1280, 720))
        assertEquals(1, sampleSizeFor(640, 480, 1280, 720))
    }

    @Test
    fun `photo de 4000x3000 - divisee par 2 au plus pres sans descendre sous la cible`() {
        assertEquals(2, sampleSizeFor(4000, 3000, 1280, 720))
        assertEquals(4, sampleSizeFor(4000, 3000, 960, 540))
    }

    @Test
    fun `vignette - forte reduction`() {
        assertEquals(8, sampleSizeFor(2560, 1440, 320, 180))
    }

    @Test
    fun `photo 1920x1080 pour une carte - decodee a 960 de large`() {
        val sample = sampleSizeFor(1920, 1080, CARD_IMAGE_WIDTH_PX, CARD_IMAGE_HEIGHT_PX)
        assertEquals(2, sample)
        assertEquals(960, 1920 / sample)
        // Même photo pour la question : 1280 × 720 visés, la photo reste nette (pas sous la cible).
        assertEquals(1, sampleSizeFor(1920, 1080, PHOTO_WIDTH_PX, PHOTO_HEIGHT_PX))
        assertEquals(2, sampleSizeFor(2560, 1440, PHOTO_WIDTH_PX, PHOTO_HEIGHT_PX))
    }
}
