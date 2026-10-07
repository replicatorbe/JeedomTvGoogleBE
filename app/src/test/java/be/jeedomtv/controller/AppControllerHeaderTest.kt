package be.jeedomtv.controller

import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.HeaderItem
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.TileIcon
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bandeau d'infos (`header`) : chargé avec le layout, mis à jour par `changes` comme les tuiles. */
class AppControllerHeaderTest {

    private val config = JeedomConfig(host = "192.168.1.10", key = "cle")

    private val header = listOf(
        HeaderItem("h1", "Extérieur", TileIcon.Temperature, "17", "°C"),
        HeaderItem("h2", "Poubelles", TileIcon.Trash, "demain : Déchets organiques", ""),
    )

    private fun TestScope.started(factory: FakeDriverFactory): AppController {
        val c = AppController(AppModel(AppState(uiVisible = true)), FakeSettings(stored = config), factory, backgroundScope)
        c.start()
        runCurrent()
        return c
    }

    @Test
    fun `le bandeau suit le layout`() = runTest {
        val c = started(FakeDriverFactory(onLayout = { contractLayout().copy(header = header) }))
        assertEquals(header, c.state.value.header)
    }

    @Test
    fun `sans header - pas de bandeau`() = runTest {
        val c = started(FakeDriverFactory())
        assertTrue(c.state.value.header.isEmpty())
    }

    @Test
    fun `changes met a jour l'element du bandeau dont l'id correspond, et les tuiles`() = runTest {
        val factory = FakeDriverFactory(onLayout = { contractLayout().copy(header = header) })
        val c = started(factory)
        factory.pushChanges(changes("c1", "9f2c1a", "h1" to "18", "t5" to "25", "h9" to "x"))
        runCurrent()
        val state = c.state.value
        assertEquals("18", state.header[0].value)
        assertEquals("autre élément inchangé", "demain : Déchets organiques", state.header[1].value)
        assertEquals("25", state.findTile("t5")?.value)
        assertEquals(2, state.header.size)

        factory.pushChanges(changes("c2", "9f2c1a", "h2" to null))
        runCurrent()
        assertEquals(null, c.state.value.header[1].value)
    }

    @Test
    fun `nouvelle revision sans header - le bandeau disparait`() = runTest {
        var layout = contractLayout().copy(header = header)
        val factory = FakeDriverFactory(onLayout = { layout })
        val c = started(factory)
        layout = contractLayout(revision = "r2")
        factory.pushChanges(changes("c1", revision = "r2"))
        runCurrent()
        assertTrue(c.state.value.header.isEmpty())
    }
}
