package be.jeedomtv.model.driver

import be.jeedomtv.model.Corner
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.StatusBar
import be.jeedomtv.model.StatusItem
import be.jeedomtv.model.StatusShape
import be.jeedomtv.model.TRANSPARENT
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.VideoUrl
import be.jeedomtv.model.WHITE
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Barre d'état (`status`), notifications riches, `dismiss` et vidéo des questions, côté pilote. */
class JeedomHttpDriverStatusTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun driver() = JeedomHttpDriver(
        JeedomConfig(host = "192.168.1.10", key = "cle-de-test"),
        OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
        server.url("/"),
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** Exemple du contrat (docs/api.md, « Barre d'état »). */
    private val contractStatus = """
        {"corner": "bottom_start", "clock": true, "opacity": 85, "items": [
          {"id": "meteo", "icon": "mdi:weather-rainy", "text": "18°", "iconColor": "#FFFFFF",
           "textColor": "#FFFFFF", "borderColor": "#FFFFFF", "backgroundColor": "#00000000", "shape": "rounded"},
          {"id": "porte", "icon": "mdi:lock-open-variant", "text": "", "iconColor": "#FFA726",
           "textColor": "#FFFFFF", "borderColor": "#FFA726", "backgroundColor": "#00000000", "shape": "circle"}
        ]}
    """.trimIndent()

    private val expectedStatus = StatusBar(
        corner = Corner.BottomStart,
        clock = true,
        opacity = 85,
        items = listOf(
            StatusItem("meteo", "mdi:weather-rainy", "18°", WHITE, WHITE, WHITE, 0x00000000, StatusShape.Rounded),
            StatusItem("porte", "mdi:lock-open-variant", "", 0xFFFFA726.toInt(), WHITE, 0xFFFFA726.toInt(), 0, StatusShape.Circle),
        ),
    )

    @Test
    fun `layout - status de l'exemple du contrat`() = runBlocking {
        server.enqueue(json("""{"revision": "r", "pages": [], "status": $contractStatus}"""))
        assertEquals(expectedStatus, driver().layout().status)
    }

    @Test
    fun `layout - status absent ou null - pas de barre`() = runBlocking {
        server.enqueue(json("""{"revision": "r", "pages": []}"""))
        assertNull(driver().layout().status)
        server.enqueue(json("""{"revision": "r", "pages": [], "status": null}"""))
        assertNull(driver().layout().status)
    }

    @Test
    fun `status - valeurs par defaut et lecture tolerante`() {
        val parsed = parseStatus(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"corner": "milieu", "opacity": 250, "items": [
                  {"icon": "mdi:sans-id"},
                  {"id": "a", "iconColor": "rouge", "borderColor": "#80FF0000", "shape": "étoile"},
                  "pas un objet"
                ]}""",
            ),
        )!!
        assertEquals(Corner.BottomStart, parsed.corner)
        assertTrue("clock par défaut", parsed.clock)
        assertEquals(100, parsed.opacity)
        assertEquals(listOf(StatusItem("a", null, "", WHITE, WHITE, 0x80FF0000.toInt(), TRANSPARENT, StatusShape.Rounded)), parsed.items)
    }

    @Test
    fun `status - opacite 0 ou rien a montrer - barre invisible`() {
        assertFalse(StatusBar(opacity = 0).visible)
        assertFalse(StatusBar(clock = false).visible)
        assertTrue(StatusBar(clock = false, items = listOf(StatusItem("a", "x"))).visible)
        assertTrue(StatusBar(clock = true).visible)
    }

    @Test
    fun `changes - status present, null ou absent`() = runBlocking {
        server.enqueue(json("""{"since": 1, "changes": [], "commands": [], "status": $contractStatus}"""))
        val present = driver().changes("0")
        assertTrue(present.statusChanged)
        assertEquals(expectedStatus, present.status)

        server.enqueue(json("""{"since": 2, "changes": [], "commands": [], "status": null}"""))
        val removed = driver().changes("1")
        assertTrue("null : barre retirée", removed.statusChanged)
        assertNull(removed.status)

        server.enqueue(json("""{"since": 3, "changes": [], "commands": []}"""))
        assertFalse("absent : barre inchangée", driver().changes("2").statusChanged)
    }

    @Test
    fun `notify riche - tag, icone, coin et video`() = runBlocking {
        server.enqueue(
            json(
                """{"since": 1, "commands": [
                  {"id": 7, "type": "notify", "tag": "sonnette", "title": "Sonnette", "message": "On sonne",
                   "icon": "mdi:doorbell", "iconColor": "#FFA726", "corner": "bottom_end",
                   "video": "rtsp://utilisateur:motdepasse@camera.example/flux", "image": "i1", "duration": 30},
                  {"id": 8, "type": "notify", "title": "", "message": "Simple"}
                ]}""",
            ),
        )
        val (rich, simple) = driver().changes("0").commands.map { it as TvCommand.Notify }
        assertEquals(7L, rich.id)
        assertEquals("sonnette", rich.tag)
        assertEquals("mdi:doorbell", rich.icon)
        assertEquals(0xFFFFA726.toInt(), rich.iconColor)
        assertEquals(Corner.BottomEnd, rich.corner)
        assertEquals(VideoUrl("rtsp://utilisateur:motdepasse@camera.example/flux"), rich.video)
        assertEquals("i1", rich.image)
        assertEquals(30, rich.durationSec)
        assertFalse("l'URL n'apparaît jamais en clair", rich.toString().contains("motdepasse"))

        assertNull(simple.tag)
        assertNull(simple.video)
        assertNull(simple.icon)
        assertEquals("coin par défaut", Corner.TopEnd, simple.corner)
    }

    @Test
    fun `notify - id texte lu comme tag, sans id d'ordre`() = runBlocking {
        server.enqueue(json("""{"since": 1, "commands": [{"id": "portail", "type": "notify", "message": "Ouvert"}, {"id": "12", "type": "exit"}]}"""))
        val commands = driver().changes("0").commands
        val notify = commands[0] as TvCommand.Notify
        assertNull(notify.id)
        assertEquals("portail", notify.tag)
        assertEquals("id texte numérique : id d'ordre", 12L, commands[1].id)
    }

    @Test
    fun `dismiss - target obligatoire`() = runBlocking {
        server.enqueue(json("""{"since": 1, "commands": [{"id": 3, "type": "dismiss", "target": "sonnette"}, {"id": 4, "type": "dismiss"}]}"""))
        assertEquals(listOf(TvCommand.Dismiss(3, "sonnette")), driver().changes("0").commands)
    }

    @Test
    fun `video - schemas acceptes seulement, ask video`() = runBlocking {
        assertTrue(VideoUrl.of("rtsp://camera.example/flux")!!.isRtsp)
        assertFalse(VideoUrl.of("https://exemple.example/live/index.m3u8")!!.isRtsp)
        assertNull(VideoUrl.of("file:///sdcard/x.mp4"))
        assertNull(VideoUrl.of("  "))
        assertEquals("VideoUrl(***)", VideoUrl("rtsp://a:b@c").toString())

        server.enqueue(
            json("""{"since": 1, "commands": [{"id": 1, "type": "ask", "ask": "j", "message": "Ouvrir ?", "answers": ["Oui"], "timeout": 30, "video": "rtsp://camera.example/flux"}]}"""),
        )
        val ask = driver().changes("0").commands.single() as TvCommand.Ask
        assertEquals(VideoUrl("rtsp://camera.example/flux"), ask.video)
    }
}
