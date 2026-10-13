package be.jeedomtv.model.driver

import be.jeedomtv.model.Board
import be.jeedomtv.model.BoardSection
import be.jeedomtv.model.BoardWeather
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.PageType
import be.jeedomtv.model.Train
import be.jeedomtv.model.TrainStatus
import be.jeedomtv.model.WeatherHour
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

/** Page « tableau des trains » (`board`), `hidden`, type de page et `changes.boards`. */
class JeedomHttpDriverBoardTest {

    private lateinit var server: MockWebServer
    private val config = JeedomConfig(host = "192.168.1.10", key = "cle-de-la-tv")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun driver() = JeedomHttpDriver(
        config,
        OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build(),
        server.url("/"),
    )

    private fun json(body: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    /** Le tableau de l'exemple du contrat (docs/api.md). */
    private val contractBoard = Board(
        listOf(
            BoardSection(
                "b1", "Soignies → Bruxelles", "", "07:12", listOf("Grève nationale le 12/10"),
                listOf(
                    Train("07:09", "07:13", 4, "IC 1706", "Tongres", "1", false, 0, TrainStatus.Delayed, next = true),
                    Train("07:38", "07:38", 0, "IC 3707", "Brussels Airport - Zaventem", "", false, 0, TrainStatus.Canceled, next = false),
                ),
            ),
            BoardSection("b2", "Braine-le-Comte → Soignies", "Demain", "19:21", emptyList(), emptyList()),
        )
    )

    @Test
    fun `layout - page board de l'exemple du contrat, cachee, sans tuile`() = runBlocking {
        server.enqueue(json("""{"schema": 1, "revision": "r", "pages": [
            {"id": "p1", "name": "Salon", "tiles": [{"id": "t1", "type": "switch", "name": "Plafond"}]},
            $CONTRACT_BOARD_PAGE
        ]}"""))
        val layout = driver().layout()
        assertEquals(listOf("p1", "p7"), layout.pages.map { it.id })
        val tiles = layout.pages[0]
        assertEquals(PageType.Tiles, tiles.type)
        assertFalse(tiles.hidden)
        assertNull(tiles.board)
        val board = layout.pages[1]
        assertEquals(PageType.Board, board.type)
        assertTrue(board.isBoard)
        assertTrue(board.hidden)
        assertEquals("Trains", board.name)
        assertTrue(board.tiles.isEmpty())
        assertEquals(contractBoard, board.board)
    }

    @Test
    fun `layout - type inconnu ignore, tiles explicite, hidden tolerant, board sans contenu`() = runBlocking {
        server.enqueue(json("""{"revision": "r", "pages": [
            {"id": "a", "name": "Futur", "type": "carte", "tiles": [{"id": "x", "type": "info"}]},
            {"id": "b", "name": "Tuiles", "type": "tiles", "hidden": 1, "tiles": [{"id": "y", "type": "info"}]},
            {"id": "c", "name": "Trains", "type": "board", "hidden": "0", "tiles": [{"id": "z", "type": "info"}]},
            {"id": "d", "name": "Trains 2", "type": "board", "board": "illisible"},
            {"name": "Sans id"}
        ]}"""))
        val pages = driver().layout().pages
        assertEquals("la page de type inconnu disparaît", listOf("b", "c", "d", "page-4"), pages.map { it.id })
        assertTrue(pages[0].hidden)
        assertEquals(listOf("y"), pages[0].tiles.map { it.id })
        assertFalse(pages[1].hidden)
        assertTrue("une page board n'a jamais de tuile", pages[1].tiles.isEmpty())
        assertEquals(Board(), pages[1].board)
        assertEquals(Board(), pages[2].board)
    }

    @Test
    fun `board - champs absents, etat inconnu a l'heure, nombres en texte, limites du contrat`() {
        val board = parseBoard(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"sections": [
                  {"trains": [
                    {"time": "07:21", "status": "bientot", "delay": "3", "transfers": 1.0, "platformChanged": 1, "next": "true"},
                    {"vehicle": "Sans heure"},
                    {"time": "07:50", "delay": -2, "transfers": null, "status": null},
                    "pas un objet",
                    {"time": "08:01"}, {"time": "08:02"}, {"time": "08:03"}, {"time": "08:04"}
                  ], "notes": ["a", "", null, "b", "c"]},
                  42,
                  {"id": "s3"}, {"id": "s4"}, {"id": "s5"}
                ]}"""
            )
        )!!
        assertEquals("3 sections au plus, les entrées illisibles ignorées", listOf("section-0", "s3", "s4"), board.sections.map { it.id })
        val section = board.sections[0]
        assertEquals("", section.title)
        assertEquals("", section.day)
        assertEquals("", section.updated)
        assertEquals("2 notes au plus, vides ignorées", listOf("a", "b"), section.notes)
        assertEquals("6 trains au plus, sans heure ignorés", listOf("07:21", "07:50", "08:01", "08:02", "08:03", "08:04"), section.trains.map { it.time })
        assertEquals(
            Train("07:21", "07:21", 3, "", "", "", platformChanged = true, transfers = 1, status = TrainStatus.OnTime, next = true),
            section.trains[0],
        )
        assertEquals(Train("07:50"), section.trains[1])
        assertTrue(board.sections[1].trains.isEmpty())
    }

    @Test
    fun `changes - boards remplaces par page, absent ou illisible vide`() = runBlocking {
        server.enqueue(json("""{"since": 2, "revision": "r", "changes": [], "commands": [],
            "boards": {"p7": ${CONTRACT_BOARD}, "p8": "illisible", "p9": {"sections": []}}}"""))
        server.enqueue(json("""{"since": 3, "revision": "r", "changes": [], "commands": []}"""))
        server.enqueue(json("""{"since": 4, "revision": "r", "changes": [], "commands": [], "boards": null}"""))
        val first = driver().changes("1")
        assertEquals(mapOf("p7" to contractBoard, "p9" to Board()), first.boards)
        assertTrue(driver().changes("2").boards.isEmpty())
        assertTrue(driver().changes("3").boards.isEmpty())
    }

    private fun weather(json: String) = parseWeather(kotlinx.serialization.json.Json.parseToJsonElement(json))

    @Test
    fun `meteo - exemple du contrat, dans le tableau et dans changes`() = runBlocking {
        val expected = BoardWeather(
            city = "Soignies", temp = 14, icon = "mdi:weather-cloudy", condition = "Couvert", tmin = 7, tmax = 21,
            rain = "Risque de précipitations dans 2h40", warning = "Vigilance jaune — Brouillard", warningLevel = 1,
            updated = "06:40", note = "",
            hours = listOf(
                WeatherHour("09", "mdi:weather-cloudy", 15, 0),
                WeatherHour("12", "mdi:weather-cloudy", 18, 30),
                WeatherHour("15", "mdi:weather-rainy", 20, 90),
            ),
        )
        assertEquals(expected, weather(CONTRACT_WEATHER))
        val board = CONTRACT_BOARD.trimEnd().removeSuffix("}") + """, "weather": $CONTRACT_WEATHER}"""
        server.enqueue(json("""{"since": 2, "revision": "r", "changes": [], "commands": [], "boards": {"p7": $board}}"""))
        assertEquals(mapOf("p7" to contractBoard.copy(weather = expected)), driver().changes("1").boards)
        assertNull("sans weather : pas de météo", contractBoard.weather)
    }

    @Test
    fun `meteo - note seule, pas un objet, champs illisibles, bornes du contrat`() {
        assertEquals(BoardWeather(note = "Météo non relue depuis 06:40"), weather("""{"note": "Météo non relue depuis 06:40"}"""))
        assertNull(weather("\"illisible\""))
        assertNull(weather("[]"))
        assertNull(parseWeather(null))
        val parsed = weather("""{"temp": "14.6", "icon": "weather-cloudy", "tmin": "froid", "tmax": null,
            "warningLevel": 7, "rain": 12, "condition": ["?"], "note": "${"x".repeat(150)}",
            "hours": [
              {"h": "09", "icon": "https://x/y.png", "temp": "15", "rain": 140},
              {"icon": "mdi:weather-sunny"}, "pas un objet",
              {"h": "10", "rain": -5}, {"h": "11", "rain": "NaN"}, {"h": "12"}, {"h": "13"}, {"h": "14"}, {"h": "15"}
            ]}""")!!
        assertEquals(15, parsed.temp)
        assertEquals("une icône qui n'est pas mdi: est ignorée", "", parsed.icon)
        assertNull(parsed.tmin)
        assertNull(parsed.tmax)
        assertEquals(3, parsed.warningLevel)
        assertEquals("nombre en texte accepté", "12", parsed.rain)
        assertEquals("", parsed.condition)
        assertEquals(120, parsed.note.length)
        assertEquals("6 colonnes au plus, sans heure ignorées", listOf("09", "10", "11", "12", "13", "14"), parsed.hours.map { it.h })
        assertEquals(WeatherHour("09", "", 15, 100), parsed.hours[0])
        assertEquals(0, parsed.hours[1].rain)
        assertNull(parsed.hours[2].rain)
        assertEquals(0, weather("""{"warningLevel": -2}""")!!.warningLevel)
        assertEquals(BoardWeather(), weather("{}"))
    }

    private companion object {
        const val CONTRACT_WEATHER = """{"city": "Soignies", "temp": 14, "icon": "mdi:weather-cloudy", "condition": "Couvert",
            "tmin": 7, "tmax": 21, "rain": "Risque de précipitations dans 2h40",
            "warning": "Vigilance jaune — Brouillard", "warningLevel": 1, "updated": "06:40", "note": "",
            "hours": [{"h": "09", "icon": "mdi:weather-cloudy", "temp": 15, "rain": 0},
                      {"h": "12", "icon": "mdi:weather-cloudy", "temp": 18, "rain": 30},
                      {"h": "15", "icon": "mdi:weather-rainy", "temp": 20, "rain": 90}]}"""

        const val CONTRACT_BOARD = """{
           "sections": [
             {"id": "b1", "title": "Soignies → Bruxelles", "day": "", "updated": "07:12",
              "notes": ["Grève nationale le 12/10"],
              "trains": [
                {"time": "07:09", "real": "07:13", "delay": 4, "vehicle": "IC 1706", "direction": "Tongres",
                 "platform": "1", "platformChanged": false, "transfers": 0, "status": "delayed", "next": true},
                {"time": "07:38", "real": "07:38", "delay": 0, "vehicle": "IC 3707",
                 "direction": "Brussels Airport - Zaventem", "platform": "", "platformChanged": false,
                 "transfers": 0, "status": "canceled", "next": false}
              ]},
             {"id": "b2", "title": "Braine-le-Comte → Soignies", "day": "Demain", "updated": "19:21",
              "notes": [], "trains": []}
           ]
         }"""

        const val CONTRACT_BOARD_PAGE =
            """{"id": "p7", "name": "Trains", "type": "board", "hidden": true, "tiles": [], "board": $CONTRACT_BOARD}"""
    }
}
