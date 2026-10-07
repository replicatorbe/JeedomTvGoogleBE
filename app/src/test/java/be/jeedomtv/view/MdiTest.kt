package be.jeedomtv.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Correspondance nom → code des icônes Material Design (fichier généré par tools/mdi/update_mdi.py). */
class MdiTest {

    /** Le fichier réellement embarqué dans l'APK. */
    private val codes by lazy { parseMdiCodepoints(File("src/main/assets/mdi/codepoints.txt").readText()) }

    @Test
    fun `fichier embarque - toutes les icones, codes connus`() {
        assertTrue("${codes.size} icônes", codes.size > 7000)
        assertEquals(0xF0597, codes["weather-rainy"])
        assertEquals(0xF0FC6, codes["lock-open-variant"])
        assertEquals(0xF0625, codes[GENERIC_ICON])
        listOf("lightbulb", "gate-open", "trash-can", "doorbell", "cctv", "weather-cloudy", mdiName(DEFAULT_NOTIFICATION_ICON)!!, "check-circle-outline", "alert-circle-outline").forEach {
            assertTrue(it, codes.containsKey(it))
        }
    }

    @Test
    fun `police embarquee - un TrueType`() {
        val ttf = File("src/main/assets/mdi/materialdesignicons-webfont.ttf").readBytes()
        assertEquals(listOf<Byte>(0, 1, 0, 0), ttf.take(4))
    }

    @Test
    fun `nom - prefixe mdi facultatif, casse et espaces ignores`() {
        assertEquals("weather-rainy", mdiName("mdi:weather-rainy"))
        assertEquals("weather-rainy", mdiName("weather-rainy"))
        assertEquals("weather-rainy", mdiName("  MDI:Weather-Rainy "))
        assertNull(mdiName(""))
        assertNull(mdiName("mdi:"))
        assertNull(mdiName(null))
    }

    @Test
    fun `lecture tolerante - commentaires, lignes vides ou mal formees ignorees`() {
        val parsed = parseMdiCodepoints("# entête\n\nabacus F16E0\nmauvaise\nsans-code zz\n x 1\nlightbulb F0335\n")
        assertEquals(mapOf("abacus" to 0xF16E0, "lightbulb" to 0xF0335), parsed)
    }
}
