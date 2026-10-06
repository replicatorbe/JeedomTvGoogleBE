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
}
