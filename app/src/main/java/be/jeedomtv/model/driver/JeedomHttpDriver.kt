package be.jeedomtv.model.driver

import be.jeedomtv.model.Changes
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Page
import be.jeedomtv.model.PingInfo
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TileChange
import be.jeedomtv.model.TileIcon
import be.jeedomtv.model.TileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Pilote HTTP du plugin `jeetvbe` (contrat : docs/api.md), avec OkHttp et kotlinx.serialization.
 * La clé de la TV voyage dans l'en-tête `X-JEETVBE-KEY`.
 */
class JeedomHttpDriver internal constructor(
    private val config: JeedomConfig,
    private val client: OkHttpClient,
    /** null si l'adresse saisie est invalide : chaque appel réseau échouera proprement. */
    private val baseUrl: HttpUrl?,
) : JeedomDriver {

    constructor(config: JeedomConfig) : this(config, sharedClient, buildBaseUrl(config.host))

    /** Attente longue : le plugin répond au plus tard après 25 s, on attend jusqu'à 40 s. */
    private val longPollClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(CHANGES_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
    }

    override suspend fun ping(): PingInfo {
        val dto = decode<PingDto>(get("ping"))
        return PingInfo(
            tvId = dto.tv?.id,
            tvName = dto.tv?.name,
            jeedomVersion = dto.jeedom,
            pluginVersion = dto.plugin,
        )
    }

    override suspend fun layout(): Layout = decode<LayoutDto>(get("layout")).toLayout()

    override suspend fun exec(tile: String, action: TileAction, value: Double?): String? {
        val body = buildJsonObject {
            put("tile", tile)
            put("action", action.apiName)
            if (value != null) put("value", numberOf(value))
        }
        return decode<ExecDto>(post("exec", body)).value.asText()
    }

    override suspend fun changes(since: String?): Changes {
        val dto = decode<ChangesDto>(
            call("changes", longPollClient, extraQuery = since?.let { "since" to it })
        )
        return Changes(
            since = dto.since.asText() ?: "0",
            revision = dto.revision,
            changes = dto.changes.orEmpty().mapNotNull { change ->
                change.tile?.let { TileChange(it, change.value.asText()) }
            },
        )
    }

    // --- HTTP -----------------------------------------------------------------------------------

    private suspend fun get(action: String): String = call(action, client)

    private suspend fun post(action: String, body: JsonObject): String =
        call(action, client, body = body.toString())

    /** Appel authentifié ; traduit les erreurs en exceptions du contrat [JeedomDriver]. */
    private suspend fun call(
        action: String,
        httpClient: OkHttpClient,
        extraQuery: Pair<String, String>? = null,
        body: String? = null,
    ): String {
        val base = baseUrl ?: throw JeedomException("Adresse de Jeedom invalide (${config.host})")
        val url = base.newBuilder()
            .addPathSegments(API_PATH)
            .addQueryParameter("action", action)
            .apply { extraQuery?.let { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .header(KEY_HEADER, config.key)
            .header("Accept", "application/json")
            .apply { if (body != null) post(body.toRequestBody(JSON_MEDIA_TYPE)) }
            .build()
        val call = httpClient.newCall(request)
        return runInterruptible(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.code == 401 -> throw AuthenticationException("Clé refusée par Jeedom")
                        !response.isSuccessful -> throw JeedomException(
                            errorMessage(text) ?: "Erreur de Jeedom (HTTP ${response.code})",
                            httpCode = response.code,
                        )
                        else -> text
                    }
                }
            } catch (e: SocketTimeoutException) {
                throw JeedomException("Jeedom ne répond pas (${url.host})", e)
            } catch (e: IOException) {
                throw JeedomException("Jeedom injoignable (${url.host})", e)
            }
        }
    }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw JeedomException("Réponse de Jeedom illisible", e)
    } catch (e: IllegalArgumentException) {
        throw JeedomException("Réponse de Jeedom illisible", e)
    }

    /** Champ `error` du corps JSON, s'il y en a un. */
    private fun errorMessage(text: String): String? = try {
        (json.parseToJsonElement(text).jsonObject["error"] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    internal companion object {
        const val API_PATH = "plugins/jeetvbe/core/php/api.php"
        const val KEY_HEADER = "X-JEETVBE-KEY"
        const val CHANGES_READ_TIMEOUT_S = 40L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
            explicitNulls = false
        }

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        /** `192.168.1.10`, `jeedom.local:8080` ou `http://…/` → URL de base de Jeedom. */
        fun buildBaseUrl(host: String): HttpUrl? {
            val trimmed = host.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return "$withScheme/".toHttpUrlOrNull()
        }

        /** 40.0 → 40 : le plugin reçoit un entier quand la valeur en est un. */
        private fun numberOf(value: Double): JsonPrimitive =
            if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) {
                JsonPrimitive(value.toLong())
            } else {
                JsonPrimitive(value)
            }

        /** Valeur brute : chaîne telle quelle, nombre en texte, null sinon. */
        private fun JsonElement?.asText(): String? = when (this) {
            null, JsonNull -> null
            is JsonPrimitive -> contentOrNull
            else -> null
        }
    }
}

// --- Corps JSON du contrat (tous les champs optionnels : le plugin évolue en parallèle) -----------

@Serializable
private data class PingDto(
    val ok: Boolean? = null,
    val schema: Int? = null,
    val tv: TvDto? = null,
    val jeedom: String? = null,
    val plugin: String? = null,
)

@Serializable
private data class TvDto(val id: Long? = null, val name: String? = null)

@Serializable
private data class LayoutDto(
    val schema: Int? = null,
    val revision: String? = null,
    val pages: List<PageDto>? = null,
) {
    fun toLayout() = Layout(
        revision = revision.orEmpty(),
        pages = pages.orEmpty().mapIndexedNotNull { index, page -> page.toPage(index) },
    )
}

@Serializable
private data class PageDto(
    val id: String? = null,
    val name: String? = null,
    val tiles: List<TileDto>? = null,
) {
    fun toPage(index: Int) = Page(
        id = id ?: "page-$index",
        name = name ?: "Page ${index + 1}",
        tiles = tiles.orEmpty().mapNotNull { it.toTile() },
    )
}

@Serializable
private data class TileDto(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val confirm: Boolean? = null,
    val value: JsonElement? = null,
    val unit: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
) {
    /** Une tuile sans id est inutilisable (aucun ordre possible) : elle est ignorée. */
    fun toTile(): Tile? {
        val tileId = id?.takeIf { it.isNotBlank() } ?: return null
        return Tile(
            id = tileId,
            type = TileType.fromApi(type),
            name = name.orEmpty(),
            icon = TileIcon.fromApi(icon),
            confirm = confirm ?: false,
            value = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
            unit = unit.orEmpty(),
            min = min,
            max = max,
            step = step,
        )
    }
}

@Serializable
private data class ExecDto(val ok: Boolean? = null, val value: JsonElement? = null)

@Serializable
private data class ChangesDto(
    val since: JsonElement? = null,
    val revision: String? = null,
    val changes: List<ChangeDto>? = null,
)

@Serializable
private data class ChangeDto(val tile: String? = null, val value: JsonElement? = null)
