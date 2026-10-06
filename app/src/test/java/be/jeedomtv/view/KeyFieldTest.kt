package be.jeedomtv.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFieldTest {

    private val saved = "8724000b26a7863220293fb3ba5a5381"

    @Test
    fun `cle enregistree affichee masquee et conservee sans modification`() {
        val field = KeyField(saved)
        assertTrue(field.masked)
        assertEquals("••••••••5381", field.shown)
        assertEquals(saved, field.value)
    }

    @Test
    fun `un caractere tape - champ vide et en clair, le caractere garde`() {
        val field = KeyField(saved).edit("••••••••5381a")
        assertFalse(field.masked)
        assertEquals("a", field.shown)
        assertEquals("a", field.value)
        assertEquals("ab", field.edit("ab").value)
    }

    @Test
    fun `caractere tape avec le curseur au debut du masque`() {
        assertEquals("x", KeyField(saved).edit("x••••••••5381").value)
    }

    @Test
    fun `un caractere efface - champ vide`() {
        val field = KeyField(saved).edit("••••••••538")
        assertEquals("", field.shown)
        assertEquals("", field.value)
    }

    @Test
    fun `premier lancement - pas de masque`() {
        val field = KeyField("")
        assertFalse(field.masked)
        assertEquals("", field.shown)
        assertEquals("abc", field.edit("abc").value)
    }

    @Test
    fun `cle courte entierement masquee`() {
        assertEquals("••••", KeyField.mask("1234"))
        assertEquals("•••", KeyField.mask("123"))
        assertEquals("••••••••2345", KeyField.mask("12345"))
    }
}
