package be.jeedomtv.controller

import be.jeedomtv.model.Changes
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Page
import be.jeedomtv.model.PingInfo
import be.jeedomtv.model.SettingsRepository
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TileChange
import be.jeedomtv.model.TileIcon
import be.jeedomtv.model.TileType
import be.jeedomtv.model.driver.JeedomDriver
import be.jeedomtv.model.driver.JeedomDriverFactory
import kotlinx.coroutines.channels.Channel

class FakeSettings(var stored: JeedomConfig? = null) : SettingsRepository {
    val saved = mutableListOf<JeedomConfig>()
    override suspend fun load(): JeedomConfig? = stored
    override suspend fun save(config: JeedomConfig) {
        saved += config
        stored = config
    }
    override suspend fun clear() {
        stored = null
    }
}

/** Ordre reçu par le pilote factice. */
data class ExecCall(val tile: String, val action: TileAction, val value: Double? = null)

/**
 * Fabrique et pilote factices réunis : le comportement ([onPing], [onLayout], [onExec]) et les
 * appels consignés sont partagés par tous les pilotes créés.
 *
 * `changes` suspend jusqu'à ce que le test fournisse une réponse par [pushChanges] ou une erreur
 * par [failChanges] : la boucle des changements reste ainsi sous le contrôle du test.
 */
class FakeDriverFactory(
    var onLayout: suspend () -> Layout = { contractLayout() },
) : JeedomDriverFactory {
    val created = mutableListOf<JeedomConfig>()
    var onPing: suspend () -> PingInfo = { PingInfo(612, "TV salon", "4.6.1", "0.1") }
    var onExec: suspend (ExecCall) -> String? = { null }

    val pings = mutableListOf<JeedomConfig>()
    var layoutCount = 0
    val execCalls = mutableListOf<ExecCall>()
    val changesCalls = mutableListOf<String?>()
    private val changesResults = Channel<Result<Changes>>(Channel.UNLIMITED)

    fun pushChanges(changes: Changes) {
        changesResults.trySend(Result.success(changes))
    }

    fun failChanges(error: Exception) {
        changesResults.trySend(Result.failure(error))
    }

    override fun create(config: JeedomConfig): JeedomDriver {
        created += config
        return object : JeedomDriver {
            override suspend fun ping(): PingInfo {
                pings += config
                return onPing()
            }

            override suspend fun layout(): Layout {
                layoutCount++
                return onLayout()
            }

            override suspend fun exec(tile: String, action: TileAction, value: Double?): String? {
                val call = ExecCall(tile, action, value)
                execCalls += call
                return onExec(call)
            }

            override suspend fun changes(since: String?): Changes {
                changesCalls += since
                return changesResults.receive().getOrThrow()
            }
        }
    }
}

fun changes(since: String, revision: String = "9f2c1a", vararg values: Pair<String, String?>) =
    Changes(since, revision, values.map { (tile, value) -> TileChange(tile, value) })

/** Les tuiles de l'exemple du contrat (docs/api.md), sur une page « Salon ». */
fun contractTiles() = listOf(
    Tile("t1", TileType.Switch, "Plafond salon", TileIcon.Light, value = "1"),
    Tile("t2", TileType.Shutter, "Volets SUD séjour", TileIcon.Shutter, value = "100", unit = "%", min = 0.0, max = 100.0, step = 10.0),
    Tile("t3", TileType.Shutter, "volet 4", TileIcon.Shutter, value = null),
    Tile("t4", TileType.Slider, "Consigne salon", TileIcon.Thermostat, value = "20.5", unit = "°C", min = 15.0, max = 25.0, step = 0.5),
    Tile("t5", TileType.Info, "Température salon", TileIcon.Temperature, value = "24", unit = "°C"),
    Tile("t6", TileType.Scene, "Bonne nuit", TileIcon.Scene, confirm = true, value = null),
)

/** Deux pages : l'exemple du contrat, puis une page « Cuisine » de deux interrupteurs. */
fun contractLayout(revision: String = "9f2c1a") = Layout(
    revision = revision,
    pages = listOf(
        Page("p1", "Salon", contractTiles()),
        Page(
            "p2", "Cuisine", listOf(
                Tile("k1", TileType.Switch, "Plan de travail", TileIcon.Light, value = "0"),
                Tile("k2", TileType.Switch, "Hotte", TileIcon.Fan, value = "0"),
            )
        ),
        Page("p3", "Garage", listOf(Tile("g1", TileType.Info, "Porte", TileIcon.Lock, value = "1"))),
    ),
)
