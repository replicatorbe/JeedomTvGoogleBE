package be.jeedomtv.model.driver

import be.jeedomtv.model.ColorKey
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TileChange
import be.jeedomtv.model.TileIcon
import be.jeedomtv.model.TileType
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.TvState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class JeedomHttpDriverTest {

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

    private fun driver(readTimeoutMs: Long = 2_000) = JeedomHttpDriver(
        config,
        OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build(),
        server.url("/"),
    )

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private inline fun <reified T : Throwable> assertThrowsSuspend(crossinline block: suspend () -> Unit): T {
        try {
            runBlocking { block() }
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Exception inattendue : $e", e)
        }
        fail("${T::class.simpleName} attendue")
        throw IllegalStateException()
    }

    // --- Requêtes ------------------------------------------------------------------------------

    @Test
    fun `ping envoie la cle dans l'en-tete et lit la reponse`() = runBlocking {
        server.enqueue(json("""{"ok": true, "schema": 1, "tv": {"id": 612, "name": "TV salon"}, "jeedom": "4.6.1", "plugin": "0.1"}"""))
        val info = driver().ping()
        assertEquals(612L, info.tvId)
        assertEquals("TV salon", info.tvName)
        assertEquals("4.6.1", info.jeedomVersion)
        assertEquals("0.1", info.pluginVersion)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("cle-de-la-tv", request.getHeader("X-JEETVBE-KEY"))
        assertEquals("/plugins/jeetvbe/core/php/api.php", request.requestUrl!!.encodedPath)
        assertEquals("ping", request.requestUrl!!.queryParameter("action"))
        assertNull("la clé ne doit pas voyager dans l'URL", request.requestUrl!!.queryParameter("key"))
    }

    @Test
    fun `layout de l'exemple du contrat`() = runBlocking {
        server.enqueue(json(CONTRACT_LAYOUT))
        val layout = driver().layout()

        assertEquals("9f2c1a", layout.revision)
        assertEquals(1, layout.pages.size)
        val page = layout.pages[0]
        assertEquals("p1", page.id)
        assertEquals("Salon", page.name)
        assertEquals(
            listOf(
                Tile("t1", TileType.Switch, "Plafond salon", TileIcon.Light, false, "1", ""),
                Tile("t2", TileType.Shutter, "Volets SUD séjour", TileIcon.Shutter, false, "100", "%", 0.0, 100.0, 10.0),
                Tile("t3", TileType.Shutter, "volet 4", TileIcon.Shutter, false, null, ""),
                Tile("t4", TileType.Slider, "Consigne salon", TileIcon.Thermostat, false, "20.5", "°C", 15.0, 25.0, 0.5),
                Tile("t5", TileType.Info, "Température salon", TileIcon.Temperature, false, "24", "°C"),
                Tile("t6", TileType.Scene, "Bonne nuit", TileIcon.Scene, true, null, ""),
            ),
            page.tiles,
        )
        assertTrue(page.tiles[1].hasRange)
        assertTrue(!page.tiles[2].hasRange)
        assertEquals("layout", server.takeRequest().requestUrl!!.queryParameter("action"))
    }

    @Test
    fun `layout tolere champs absents ou nuls, type et icone inconnus`() = runBlocking {
        server.enqueue(
            json(
                """
                {"schema": 1, "revision": "r2", "futur": {"x": 1}, "pages": [
                  {"id": "p1", "name": null, "tiles": [
                    {"id": "a", "type": "camera", "name": "Inconnu", "icon": "robot", "confirm": null, "value": 12.5},
                    {"id": "b", "type": "switch", "name": "Prise", "unit": null, "min": null},
                    {"type": "info", "name": "Sans id"},
                    {"id": "c"}
                  ]},
                  {"id": "p2", "name": "Vide"},
                  {"name": "Sans id"}
                ]}
                """.trimIndent()
            )
        )
        val layout = driver().layout()
        assertEquals("r2", layout.revision)
        assertEquals(3, layout.pages.size)
        val tiles = layout.pages[0].tiles
        assertEquals("Page 1", layout.pages[0].name)
        assertEquals(listOf("a", "b", "c"), tiles.map { it.id })
        assertEquals(Tile("a", TileType.Info, "Inconnu", TileIcon.Generic, false, "12.5", ""), tiles[0])
        assertEquals(Tile("b", TileType.Switch, "Prise", TileIcon.Generic, false, null, ""), tiles[1])
        assertEquals(Tile("c", TileType.Info, "", TileIcon.Generic), tiles[2])
        assertTrue(layout.pages[1].tiles.isEmpty())
        assertEquals("page-2", layout.pages[2].id)
    }

    @Test
    fun `exec envoie le corps JSON du contrat et retourne la valeur`() = runBlocking {
        server.enqueue(json("""{"ok": true, "value": "40"}"""))
        assertEquals("40", driver().exec("t2", TileAction.Set, 40.0))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("exec", request.requestUrl!!.queryParameter("action"))
        assertEquals("cle-de-la-tv", request.getHeader("X-JEETVBE-KEY"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"tile":"t2","action":"set","value":40}""", request.body.readUtf8())
    }

    @Test
    fun `exec sans valeur et valeur decimale`() = runBlocking {
        server.enqueue(json("""{"ok": true, "value": null}"""))
        server.enqueue(json("""{"ok": true, "value": 21.5}"""))
        val d = driver()
        assertNull(d.exec("t1", TileAction.Toggle))
        assertEquals("21.5", d.exec("t4", TileAction.Set, 21.5))
        assertEquals("""{"tile":"t1","action":"toggle"}""", server.takeRequest().body.readUtf8())
        assertEquals("""{"tile":"t4","action":"set","value":21.5}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `changes sans curseur puis avec le curseur renvoye tel quel`() = runBlocking {
        server.enqueue(json("""{"since": 1791364425.381, "revision": "9f2c1a", "changes": []}"""))
        server.enqueue(
            json("""{"since": 1791364430.002, "revision": "9f2c1a", "changes": [{"tile": "t1", "value": "0"}, {"tile": "t4", "value": 21}, {"tile": "t3", "value": null}]}""")
        )
        val d = driver()
        val first = d.changes(null)
        assertEquals("1791364425.381", first.since)
        assertEquals("9f2c1a", first.revision)
        assertTrue(first.changes.isEmpty())

        val second = d.changes(first.since)
        assertEquals("1791364430.002", second.since)
        assertEquals(listOf(TileChange("t1", "0"), TileChange("t4", "21"), TileChange("t3", null)), second.changes)

        val r1 = server.takeRequest().requestUrl!!
        assertEquals("changes", r1.queryParameter("action"))
        assertNull(r1.queryParameter("since"))
        assertEquals("1791364425.381", server.takeRequest().requestUrl!!.queryParameter("since"))
    }

    @Test
    fun `changes attend plus longtemps que le delai de lecture normal`() = runBlocking {
        // Délai de lecture du client : 300 ms ; la réponse arrive après 600 ms.
        server.enqueue(json("""{"since": 2, "revision": "r", "changes": []}""").setBodyDelay(600, TimeUnit.MILLISECONDS))
        assertEquals("2", driver(readTimeoutMs = 300).changes("1").since)
    }

    @Test
    fun `changes avec les ordres du contrat, types inconnus ignores`() = runBlocking {
        server.enqueue(
            json(
                """
                {"since": 1791364425.381, "revision": "9f2c1a",
                 "changes": [{"tile": "t1", "value": "0"}],
                 "commands": [
                   {"id": 17, "type": "show", "page": "p2", "duration": 30},
                   {"id": 18, "type": "show", "page": "p1"},
                   {"id": 19, "type": "notify", "title": "", "message": "Lave-linge terminé"},
                   {"id": 20, "type": "exit"},
                   {"id": 21, "type": "reboot"},
                   {"id": 22, "type": "show"},
                   {"type": "notify", "message": "Sans id", "futur": true}
                 ]}
                """.trimIndent()
            )
        )
        val result = driver().changes("1")
        assertEquals(listOf(TileChange("t1", "0")), result.changes)
        assertEquals(
            listOf(
                TvCommand.Show(17, "p2", 30),
                TvCommand.Show(18, "p1", 0),
                TvCommand.Notify(19, "", "Lave-linge terminé"),
                TvCommand.Exit(20),
                TvCommand.Notify(null, "", "Sans id"),
            ),
            result.commands,
        )
    }

    @Test
    fun `changes sans champ commands`() = runBlocking {
        server.enqueue(json("""{"since": 3, "revision": "r", "changes": []}"""))
        assertTrue(driver().changes(null).commands.isEmpty())
    }

    @Test
    fun `state envoie le corps du contrat`() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        server.enqueue(json("""{"ok": true}"""))
        val d = driver()
        d.state(TvState(visible = true, screenOn = true, page = "p2"))
        d.state(TvState(visible = false, screenOn = false, page = null))

        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("state", first.requestUrl!!.queryParameter("action"))
        assertEquals("cle-de-la-tv", first.getHeader("X-JEETVBE-KEY"))
        assertEquals("""{"visible":true,"screenOn":true,"page":"p2"}""", first.body.readUtf8())
        assertEquals(
            "page null explicite hors écran des pages",
            """{"visible":false,"screenOn":false,"page":null}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `state envoie la version de l'application`() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        driver().state(TvState(visible = true, screenOn = true, page = "p1", appVersion = "0.4.0"))
        assertEquals(
            """{"visible":true,"screenOn":true,"page":"p1","appVersion":"0.4.0"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `state en erreur leve JeedomException`() {
        server.enqueue(json("""{"error": "JSON invalide"}""", code = 400))
        val e = assertThrowsSuspend<JeedomException> { driver().state(TvState(true, true, null)) }
        assertEquals("JSON invalide", e.message)
    }

    @Test
    fun `changes avec une question ask, questions inutilisables ignorees`() = runBlocking {
        server.enqueue(
            json(
                """
                {"since": 5, "revision": "r", "changes": [], "commands": [
                  {"id": 30, "type": "ask", "ask": "a1b2c3", "title": "Sonnette",
                   "message": "On sonne au portail. Ouvrir ?", "answers": ["Ouvrir", "Ignorer"], "timeout": 30},
                  {"id": 31, "type": "ask", "ask": "d4", "message": "Sans titre ni délai", "answers": ["Oui"]},
                  {"id": 32, "type": "ask", "ask": "e5", "message": "Sans réponse", "answers": []},
                  {"id": 33, "type": "ask", "message": "Sans jeton", "answers": ["Oui"]}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(
            listOf(
                TvCommand.Ask(30, "a1b2c3", "Sonnette", "On sonne au portail. Ouvrir ?", listOf("Ouvrir", "Ignorer"), 30),
                TvCommand.Ask(31, "d4", "", "Sans titre ni délai", listOf("Oui"), 0),
            ),
            driver().changes("4").commands,
        )
    }

    @Test
    fun `answer envoie le corps du contrat`() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        driver().answer("a1b2c3", "Ouvrir")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("answer", request.requestUrl!!.queryParameter("action"))
        assertEquals("cle-de-la-tv", request.getHeader("X-JEETVBE-KEY"))
        assertEquals("""{"ask":"a1b2c3","answer":"Ouvrir"}""", request.body.readUtf8())
    }

    @Test
    fun `answer - 404 et 422 avec leur code HTTP`() {
        server.enqueue(json("""{"error": "Question inconnue ou expirée"}""", code = 404))
        server.enqueue(json("""{"error": "Réponse non proposée"}""", code = 422))
        val d = driver()
        val expired = assertThrowsSuspend<JeedomException> { d.answer("x", "Oui") }
        assertEquals(404, expired.httpCode)
        assertEquals("Question inconnue ou expirée", expired.message)
        val refused = assertThrowsSuspend<JeedomException> { d.answer("x", "Peut-être") }
        assertEquals(422, refused.httpCode)
    }

    @Test
    fun `ids opaques - page dynamique et tuiles s34 repris tels quels`() = runBlocking {
        server.enqueue(
            json(
                """
                {"revision": "r", "pages": [{"id": "scenes", "name": "Scénarios", "tiles": [
                  {"id": "s34", "type": "scene", "name": "Cinéma", "icon": "scene", "value": null},
                  {"id": "eq-12/cmd 7", "type": "switch", "name": "Prise", "value": "0"}
                ]}]}
                """.trimIndent()
            )
        )
        server.enqueue(json("""{"ok": true, "value": null}"""))
        val d = driver()
        val page = d.layout().pages.single()
        assertEquals("scenes", page.id)
        assertEquals(listOf("s34", "eq-12/cmd 7"), page.tiles.map { it.id })
        d.exec("s34", TileAction.Run)
        server.takeRequest()
        assertEquals("""{"tile":"s34","action":"run"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `ordres avec image, image vide ignoree`() = runBlocking {
        server.enqueue(
            json(
                """
                {"since": 1, "revision": "r", "changes": [], "commands": [
                  {"id": 40, "type": "notify", "title": "Portier", "message": "On sonne", "image": "a3f9c2"},
                  {"id": 41, "type": "ask", "ask": "t", "message": "Ouvrir ?", "answers": ["Ignorer", "Ouvrir"],
                   "timeout": 45, "image": "b4e1"},
                  {"id": 42, "type": "notify", "message": "Sans image", "image": null},
                  {"id": 43, "type": "notify", "message": "Image vide", "image": ""}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(
            listOf(
                TvCommand.Notify(40, "Portier", "On sonne", "a3f9c2"),
                TvCommand.Ask(41, "t", "", "Ouvrir ?", listOf("Ignorer", "Ouvrir"), 45, "b4e1"),
                TvCommand.Notify(42, "", "Sans image", null),
                TvCommand.Notify(43, "", "Image vide", null),
            ),
            driver().changes("0").commands,
        )
    }

    @Test
    fun `image - octets, cle et identifiant`() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)))
        assertArrayEquals(jpeg, driver().image("a3f9c2"))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("image", request.requestUrl!!.queryParameter("action"))
        assertEquals("a3f9c2", request.requestUrl!!.queryParameter("id"))
        assertEquals("cle-de-la-tv", request.getHeader("X-JEETVBE-KEY"))
    }

    @Test
    fun `image - 404 inconnue ou expiree`() {
        server.enqueue(json("""{"error": "Image inconnue"}""", code = 404))
        val e = assertThrowsSuspend<JeedomException> { driver().image("x") }
        assertEquals(404, e.httpCode)
        assertEquals("Image inconnue", e.message)
    }

    @Test
    fun `image - plus de 5 Mo refusee`() {
        val big = Buffer().write(ByteArray((5 * 1024 * 1024) + 1))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(big))
        val e = assertThrowsSuspend<JeedomException> { driver(readTimeoutMs = 10_000).image("x") }
        assertEquals("Image trop grande", e.message)
    }

    @Test
    fun `image - taille annoncee trop grande refusee sans tout lire`() {
        val big = Buffer().write(ByteArray((5 * 1024 * 1024) + 10))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "image/jpeg").setBody(big)
                .throttleBody(64 * 1024, 1, TimeUnit.SECONDS)
        )
        val e = assertThrowsSuspend<JeedomException> { driver().image("x") }
        assertEquals("Image trop grande", e.message)
    }

    // --- Erreurs -------------------------------------------------------------------------------

    @Test
    fun `401 leve AuthenticationException`() {
        server.enqueue(json("""{"error": "Clé invalide"}""", code = 401))
        val e = assertThrowsSuspend<AuthenticationException> { driver().ping() }
        assertEquals("Clé refusée par Jeedom", e.message)
    }

    @Test
    fun `le message error du JSON est repris`() {
        server.enqueue(json("""{"error": "Tuile inconnue"}""", code = 404))
        val e = assertThrowsSuspend<JeedomException> { driver().exec("zz", TileAction.Toggle) }
        assertEquals("Tuile inconnue", e.message)
        assertEquals(404, e.httpCode)
    }

    @Test
    fun `sans champ error le code HTTP est cite`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>Fatal error</html>"))
        val e = assertThrowsSuspend<JeedomException> { driver().layout() }
        assertEquals("Erreur de Jeedom (HTTP 500)", e.message)
    }

    @Test
    fun `reponse illisible`() {
        server.enqueue(json("pas du json"))
        val e = assertThrowsSuspend<JeedomException> { driver().layout() }
        assertEquals("Réponse de Jeedom illisible", e.message)
    }

    @Test
    fun `delai depasse`() {
        server.enqueue(json("{}").setBodyDelay(1, TimeUnit.SECONDS))
        val e = assertThrowsSuspend<JeedomException> { driver(readTimeoutMs = 200).ping() }
        assertTrue(e.message!!, e.message!!.startsWith("Jeedom ne répond pas"))
    }

    @Test
    fun `connexion coupee`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val e = assertThrowsSuspend<JeedomException> { driver().ping() }
        assertTrue(e.message!!, e.message!!.startsWith("Jeedom injoignable"))
    }

    @Test
    fun `adresse invalide`() {
        val d = JeedomHttpDriver(JeedomConfig("bad host", "k"), OkHttpClient(), null)
        val e = assertThrowsSuspend<JeedomException> { d.ping() }
        assertEquals("Adresse de Jeedom invalide (bad host)", e.message)
    }

    @Test
    fun `layout sans keys - null, la touche rouge ouvrira la premiere page`() = runBlocking {
        server.enqueue(json(CONTRACT_LAYOUT))
        assertNull(driver().layout().keys)
        server.enqueue(json("""{"revision": "r", "pages": [], "keys": null}"""))
        assertNull(driver().layout().keys)
    }

    @Test
    fun `layout avec keys de l'exemple du contrat`() = runBlocking {
        server.enqueue(
            json("""{"revision": "r", "pages": [], "keys": {"red": "p1", "green": "scenes", "yellow": "p2", "blue": "p6"}}""")
        )
        assertEquals(
            mapOf(ColorKey.Red to "p1", ColorKey.Green to "scenes", ColorKey.Yellow to "p2", ColorKey.Blue to "p6"),
            driver().layout().keys,
        )
    }

    @Test
    fun `layout keys - couleurs inconnues, pages vides ou nulles ignorees`() = runBlocking {
        server.enqueue(
            json("""{"revision": "r", "pages": [], "keys": {"red": "", "green": null, "purple": "p1", "blue": "p6", "yellow": {"x": 1}}}""")
        )
        assertEquals(mapOf(ColorKey.Blue to "p6"), driver().layout().keys)
        server.enqueue(json("""{"revision": "r", "pages": [], "keys": {}}"""))
        assertEquals("keys présent mais vide : toutes les couleurs inactives", emptyMap<ColorKey, String>(), driver().layout().keys)
        server.enqueue(json("""{"revision": "r", "pages": [], "keys": ["p1"]}"""))
        assertNull("keys illisible : comme absent", driver().layout().keys)
    }

    @Test
    fun `layout - tuile button et icone camera`() = runBlocking {
        server.enqueue(
            json(
                """
                {"revision": "r", "pages": [{"id": "cams", "name": "Caméras", "tiles": [
                  {"id": "b1", "type": "button", "name": "Portail", "icon": "camera", "confirm": false, "value": null, "unit": ""},
                  {"id": "b2", "type": "button", "name": "Projecteur", "icon": "light", "confirm": true, "value": "1", "unit": ""}
                ]}]}
                """.trimIndent()
            )
        )
        assertEquals(
            listOf(
                Tile("b1", TileType.Button, "Portail", TileIcon.Camera, false, null, ""),
                Tile("b2", TileType.Button, "Projecteur", TileIcon.Light, true, "1", ""),
            ),
            driver().layout().pages.single().tiles,
        )
    }

    @Test
    fun `exec press d'un button, sans valeur`() = runBlocking {
        server.enqueue(json("""{"ok": true, "value": null}"""))
        assertNull(driver().exec("b1", TileAction.Press))
        val request = server.takeRequest()
        assertEquals("exec", request.requestUrl!!.queryParameter("action"))
        assertEquals("""{"tile":"b1","action":"press"}""", request.body.readUtf8())
    }

    @Test
    fun `construction de l'URL de base`() {
        assertEquals("http://192.168.1.10/", JeedomHttpDriver.buildBaseUrl("192.168.1.10").toString())
        assertEquals("http://jeedom.local:8080/", JeedomHttpDriver.buildBaseUrl(" jeedom.local:8080/ ").toString())
        assertEquals("https://jeedom.example/", JeedomHttpDriver.buildBaseUrl("https://jeedom.example").toString())
        assertNull(JeedomHttpDriver.buildBaseUrl(""))
        assertNull(JeedomHttpDriver.buildBaseUrl("bad host"))
    }

    private companion object {
        /** Exemple de `layout` copié du contrat (docs/api.md). */
        const val CONTRACT_LAYOUT = """
{
  "schema": 1,
  "revision": "9f2c1a",
  "pages": [
    {
      "id": "p1",
      "name": "Salon",
      "tiles": [
        {"id": "t1", "type": "switch", "name": "Plafond salon", "icon": "light", "confirm": false,
         "value": "1", "unit": ""},
        {"id": "t2", "type": "shutter", "name": "Volets SUD séjour", "icon": "shutter", "confirm": false,
         "value": "100", "unit": "%", "min": 0, "max": 100, "step": 10},
        {"id": "t3", "type": "shutter", "name": "volet 4", "icon": "shutter", "confirm": false,
         "value": null, "unit": ""},
        {"id": "t4", "type": "slider", "name": "Consigne salon", "icon": "thermostat", "confirm": false,
         "value": "20.5", "unit": "°C", "min": 15, "max": 25, "step": 0.5},
        {"id": "t5", "type": "info", "name": "Température salon", "icon": "temperature", "confirm": false,
         "value": "24", "unit": "°C"},
        {"id": "t6", "type": "scene", "name": "Bonne nuit", "icon": "scene", "confirm": true,
         "value": null, "unit": ""}
      ]
    }
  ]
}
"""
    }
}
