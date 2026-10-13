package be.jeedomtv.model.driver

import be.jeedomtv.model.Capabilities
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.PresenceReport
import be.jeedomtv.model.ShowFallback
import be.jeedomtv.model.ShowMode
import be.jeedomtv.model.SleepOutcome
import be.jeedomtv.model.SleepResult
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.TvState
import be.jeedomtv.model.WokeBy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Contrat, « Présence humaine et allumage » : champs de `state` et des ordres. */
class JeedomHttpDriverPresenceTest {

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
        JeedomConfig(host = "192.168.1.10", key = "cle-de-la-tv"),
        OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
        server.url("/"),
    )

    private fun json(body: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `state envoie les champs de presence du contrat`() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        driver().state(
            TvState(
                visible = false, screenOn = true, page = null,
                presence = PresenceReport(
                    inUse = true, foreground = "com.netflix.ninja", playing = true, dreaming = false,
                    wokeBy = WokeBy.Jeedom, humanTookOver = true,
                    capabilities = Capabilities(keys = true, foreground = true, media = true, wake = true, sleep = true),
                ),
                idleSeconds = 12,
            )
        )
        assertEquals(
            """{"visible":false,"screenOn":true,"page":null,"inUse":true,"idleSeconds":12,""" +
                """"foreground":"com.netflix.ninja","playing":true,"dreaming":false,"wokeBy":"jeedom",""" +
                """"humanTookOver":true,"capabilities":{"keys":true,"foreground":true,"media":true,"wake":true,"sleep":true}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `state - lastSleep apres un ordre sleep`() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        driver().state(
            TvState(
                visible = false, screenOn = true, page = null,
                presence = PresenceReport(
                    false, null, null, false, WokeBy.Jeedom, false, Capabilities(),
                    lastSleep = SleepResult(42, SleepOutcome.Failed),
                ),
            )
        )
        val body = server.takeRequest().body.readUtf8()
        assertEquals(true, body.endsWith(""""lastSleep":{"id":42,"result":"failed"}}"""))
    }

    @Test
    fun `state - inconnus en null explicite`() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        driver().state(
            TvState(
                visible = true, screenOn = false, page = "p1",
                presence = PresenceReport(false, null, null, false, null, false, Capabilities()),
            )
        )
        assertEquals(
            """{"visible":true,"screenOn":false,"page":"p1","inUse":false,"idleSeconds":null,"foreground":null,""" +
                """"playing":null,"dreaming":false,"wokeBy":null,"humanTookOver":false,""" +
                """"capabilities":{"keys":false,"foreground":false,"media":false,"wake":false,"sleep":false}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `ordres wake, sleep et champs ajoutes a show, notify, ask`() = runBlocking {
        server.enqueue(
            json(
                """
                {"since": 2, "revision": "r", "changes": [], "commands": [
                  {"id": 1, "type": "show", "page": "p7", "duration": 60, "mode": "auto", "wake": true, "sleepAfter": true,
                   "fallback": {"title": "Trains", "message": "IC 1706 +4 min", "icon": "mdi:train", "duration": 20}},
                  {"id": 2, "type": "show", "page": "p1", "duration": 30},
                  {"id": 3, "type": "show", "page": "p1", "mode": "inconnu", "fallback": "pas un objet"},
                  {"id": 4, "type": "notify", "title": "", "message": "Bonjour", "wake": 1},
                  {"id": 5, "type": "ask", "ask": "j", "title": "", "message": "Ouvrir ?", "answers": ["Non", "Oui"], "timeout": 30, "wake": "true"},
                  {"id": 6, "type": "wake"},
                  {"id": 7, "type": "sleep"},
                  {"id": 8, "type": "sleep", "force": true},
                  {"id": 9, "type": "show", "page": "p1", "mode": "auto", "fallback": {"duration": 500}}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(
            listOf(
                TvCommand.Show(
                    1, "p7", 60, mode = ShowMode.Auto, fallback = ShowFallback("Trains", "IC 1706 +4 min", "mdi:train", 20),
                    wake = true, sleepAfter = true,
                ),
                TvCommand.Show(2, "p1", 30),
                TvCommand.Show(3, "p1", 0),
                TvCommand.Notify(4, "", "Bonjour", wake = true),
                TvCommand.Ask(5, "j", "", "Ouvrir ?", listOf("Non", "Oui"), 30, wake = true),
                TvCommand.Wake(6),
                TvCommand.Sleep(7),
                TvCommand.Sleep(8, force = true),
                TvCommand.Show(9, "p1", 0, mode = ShowMode.Auto, fallback = ShowFallback(durationSec = 120)),
            ),
            driver().changes(null).commands,
        )
    }
}
