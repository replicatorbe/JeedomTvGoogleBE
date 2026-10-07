package be.jeedomtv.model.driver

import be.jeedomtv.model.Changes
import be.jeedomtv.model.Choice
import be.jeedomtv.model.ColorKey
import be.jeedomtv.model.Corner
import be.jeedomtv.model.HeaderItem
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.MAX_HEADER_ITEMS
import be.jeedomtv.model.Page
import be.jeedomtv.model.PingInfo
import be.jeedomtv.model.StatusBar
import be.jeedomtv.model.StatusItem
import be.jeedomtv.model.StatusShape
import be.jeedomtv.model.TRANSPARENT
import be.jeedomtv.model.WHITE
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TileChange
import be.jeedomtv.model.TileIcon
import be.jeedomtv.model.TileType
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.TvState
import be.jeedomtv.model.VideoUrl
import be.jeedomtv.model.parseColor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

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

    override suspend fun exec(tile: String, action: TileAction, value: Double?, choice: String?): String? {
        val body = buildJsonObject {
            put("tile", tile)
            put("action", action.apiName)
            // Un choix part en chaîne, telle que le plugin l'a donnée (`choices[].value`).
            when {
                choice != null -> put("value", choice)
                value != null -> put("value", numberOf(value))
            }
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
            changes = dto.changes.decodeEach<ChangeDto>().mapNotNull { change ->
                change.tile?.let { TileChange(it, change.value.asText()) }
            },
            commands = dto.commands.decodeEach<CommandDto>().mapNotNull { it.toCommand() },
            // `status` présent (même null) : état complet de la barre, à remplacer tel quel.
            statusChanged = dto.status !== ABSENT,
            status = if (dto.status === ABSENT) null else parseStatus(dto.status),
        )
    }

    override suspend fun state(state: TvState) {
        val body = buildJsonObject {
            put("visible", state.visible)
            put("screenOn", state.screenOn)
            // null explicite : « hors écran des pages ».
            put("page", state.page?.let { JsonPrimitive(it) } ?: JsonNull)
            state.appVersion?.let { put("appVersion", it) }
        }
        post("state", body)
    }

    override suspend fun answer(ask: String, answer: String) {
        post("answer", buildJsonObject {
            put("ask", ask)
            put("answer", answer)
        })
    }

    override suspend fun image(id: String): ByteArray =
        call("image", client, extraQuery = "id" to id, accept = "image/jpeg, image/png") { body ->
            // Limite de 5 Mo : une TV de 2 Go ne doit pas avaler une image démesurée.
            if (body.contentLength() > MAX_IMAGE_BYTES) throw JeedomException("Image trop grande")
            val source = body.source()
            if (source.request(MAX_IMAGE_BYTES + 1)) throw JeedomException("Image trop grande")
            source.buffer.readByteArray()
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
    ): String = call(action, httpClient, extraQuery, body) { it.string() }

    /**
     * Appel authentifié ; [read] lit le corps d'une réponse réussie. Les erreurs sont traduites
     * en exceptions du contrat [JeedomDriver] (corps JSON `error` pour les codes d'erreur).
     *
     * Appel asynchrone d'OkHttp, annulé avec la coroutine : une attente longue abandonnée (réveil,
     * nouvelle configuration) ferme aussitôt sa connexion au lieu de bloquer un thread jusqu'à 40 s,
     * et sa réponse tardive, qui pourrait porter des ordres, n'arrive pas dans le vide.
     */
    private suspend fun <T> call(
        action: String,
        httpClient: OkHttpClient,
        extraQuery: Pair<String, String>? = null,
        body: String? = null,
        accept: String = "application/json",
        read: (ResponseBody) -> T,
    ): T {
        val base = baseUrl ?: throw JeedomException("Adresse de Jeedom invalide (${config.host})")
        val url = base.newBuilder()
            .addPathSegments(API_PATH)
            .addQueryParameter("action", action)
            .apply { extraQuery?.let { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .header(KEY_HEADER, config.key)
            .header("Accept", accept)
            .apply { if (body != null) post(body.toRequestBody(JSON_MEDIA_TYPE)) }
            .build()
        val call = httpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(networkError(e, url))
                }

                // Sur un thread d'OkHttp : le corps y est lu, jamais sur le thread principal.
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        Result.success(response.use { handle(it, read) })
                    } catch (e: IOException) {
                        Result.failure(networkError(e, url))
                    } catch (e: JeedomException) {
                        Result.failure(e)
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun <T> handle(response: Response, read: (ResponseBody) -> T): T {
        val responseBody = response.body
        return when {
            response.code == 401 -> throw AuthenticationException("Clé refusée par Jeedom")
            !response.isSuccessful -> {
                val text = responseBody?.string().orEmpty()
                throw JeedomException(
                    errorMessage(text) ?: "Erreur de Jeedom (HTTP ${response.code})",
                    httpCode = response.code,
                )
            }
            responseBody == null -> throw JeedomException("Réponse de Jeedom vide")
            else -> read(responseBody)
        }
    }

    private fun networkError(e: IOException, url: HttpUrl): JeedomException =
        if (e is SocketTimeoutException) {
            JeedomException("Jeedom ne répond pas (${url.host})", e)
        } else {
            JeedomException("Jeedom injoignable (${url.host})", e)
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

        /** Taille maximale d'une image jointe (contrat : 5 Mo). */
        const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
    }
}

/** Lecture tolérante : le plugin évolue en parallèle, un champ inattendu ne doit rien casser. */
private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}

/**
 * Éléments d'une liste décodés un à un : un élément mal formé (tuile, ordre, info…) est ignoré
 * au lieu de faire échouer toute la réponse, et avec elle les ordres livrés une seule fois.
 */
private inline fun <reified T> List<JsonElement>?.decodeEach(): List<T> = orEmpty().mapNotNull {
    try {
        json.decodeFromJsonElement<T>(it)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Booléen tolérant : `true`, `1`, `"1"` ou `"true"` ; null sinon. */
private fun JsonElement?.asFlag(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    primitive.booleanOrNull?.let { return it }
    return primitive.contentOrNull?.trim()?.toDoubleOrNull()?.let { it != 0.0 }
}

/** Valeur brute : chaîne telle quelle, nombre en texte, null sinon. */
private fun JsonElement?.asText(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}

// --- Corps JSON du contrat (tous les champs optionnels : le plugin évolue en parallèle) -----------

@Serializable
private data class PingDto(
    val ok: JsonElement? = null,
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
    val pages: List<JsonElement>? = null,
    val keys: JsonElement? = null,
    val header: List<JsonElement>? = null,
    val status: JsonElement? = null,
) {
    fun toLayout() = Layout(
        revision = revision.orEmpty(),
        pages = pages.decodeEach<PageDto>().mapIndexed { index, page -> page.toPage(index) },
        keys = colorKeys(),
        header = header.decodeEach<HeaderItemDto>().mapNotNull { it.toItem() }.take(MAX_HEADER_ITEMS),
        status = parseStatus(status),
    )

    /**
     * `keys` absent (ou qui n'est pas un objet) : null, la touche rouge ouvrira la première page.
     * Couleur inconnue ou page vide : ignorée, la touche reste inactive.
     */
    private fun colorKeys(): Map<ColorKey, String>? {
        val obj = keys as? JsonObject ?: return null
        return obj.entries.mapNotNull { (name, page) ->
            val key = ColorKey.fromApi(name) ?: return@mapNotNull null
            val id = page.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to id
        }.toMap()
    }
}


@Serializable
private data class HeaderItemDto(
    val id: String? = null,
    val label: String? = null,
    val icon: String? = null,
    val value: JsonElement? = null,
    val unit: String? = null,
) {
    /** Un élément sans id ne pourrait pas suivre `changes` : il est ignoré. */
    fun toItem(): HeaderItem? {
        val itemId = id?.takeIf { it.isNotBlank() } ?: return null
        return HeaderItem(
            id = itemId,
            label = label.orEmpty(),
            icon = TileIcon.fromApi(icon),
            value = value.asText(),
            unit = unit.orEmpty(),
        )
    }
}

@Serializable
private data class PageDto(
    val id: String? = null,
    val name: String? = null,
    val tiles: List<JsonElement>? = null,
) {
    fun toPage(index: Int) = Page(
        id = id ?: "page-$index",
        name = name ?: "Page ${index + 1}",
        tiles = tiles.decodeEach<TileDto>().mapNotNull { it.toTile() },
    )
}

@Serializable
private data class TileDto(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val confirm: JsonElement? = null,
    val value: JsonElement? = null,
    val unit: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val choices: List<JsonElement>? = null,
) {
    /** Une tuile sans id est inutilisable (aucun ordre possible) : elle est ignorée. */
    fun toTile(): Tile? {
        val tileId = id?.takeIf { it.isNotBlank() } ?: return null
        return Tile(
            id = tileId,
            type = TileType.fromApi(type),
            name = name.orEmpty(),
            icon = TileIcon.fromApi(icon),
            confirm = confirm.asFlag() ?: false,
            value = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
            unit = unit.orEmpty(),
            min = min,
            max = max,
            step = step,
            choices = choices.decodeEach<ChoiceDto>().mapNotNull { it.toChoice() },
        )
    }
}

@Serializable
private data class ChoiceDto(val value: JsonElement? = null, val label: String? = null) {
    /** Sans valeur, le choix ne peut pas être envoyé : ignoré. Sans libellé, la valeur s'affiche. */
    fun toChoice(): Choice? {
        val v = value.asText()?.takeIf { it.isNotEmpty() } ?: return null
        return Choice(v, label?.takeIf { it.isNotBlank() } ?: v)
    }
}

@Serializable
private data class ExecDto(val ok: JsonElement? = null, val value: JsonElement? = null)

@Serializable
private data class ChangesDto(
    val since: JsonElement? = null,
    val revision: String? = null,
    val changes: List<JsonElement>? = null,
    val commands: List<JsonElement>? = null,
    /** Valeur par défaut [ABSENT] : distingue « pas de `status` » de `"status": null`. */
    val status: JsonElement? = ABSENT,
)

/** Marqueur d'un champ absent de la réponse (jamais envoyé par le plugin). */
private val ABSENT: JsonElement = JsonPrimitive("\u0000absent")

/**
 * Barre d'état (`status`) ; null si absente, nulle ou illisible. Lecture tolérante : un
 * indicateur sans id est ignoré, une couleur illisible prend sa valeur par défaut.
 */
internal fun parseStatus(element: JsonElement?): StatusBar? {
    val obj = element as? JsonObject ?: return null
    val items = (obj["items"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { item ->
        val fields = item as? JsonObject ?: return@mapNotNull null
        val id = fields["id"].asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        StatusItem(
            id = id,
            icon = fields["icon"].asText()?.takeIf { it.isNotBlank() },
            text = fields["text"].asText().orEmpty(),
            iconColor = parseColor(fields["iconColor"].asText()) ?: WHITE,
            textColor = parseColor(fields["textColor"].asText()) ?: WHITE,
            borderColor = parseColor(fields["borderColor"].asText()) ?: TRANSPARENT,
            backgroundColor = parseColor(fields["backgroundColor"].asText()) ?: TRANSPARENT,
            shape = StatusShape.fromApi(fields["shape"].asText()),
        )
    }
    return StatusBar(
        corner = Corner.fromApi(obj["corner"].asText(), Corner.BottomStart),
        clock = obj["clock"].asFlag() ?: true,
        opacity = (obj["opacity"].asText()?.toDoubleOrNull()?.toInt() ?: 100).coerceIn(0, 100),
        items = items,
    )
}

@Serializable
private data class CommandDto(
    /** Entier croissant de l'ordre ; un texte non numérique est lu comme `tag` (tolérance). */
    val id: JsonElement? = null,
    val type: String? = null,
    val page: String? = null,
    val duration: Double? = null,
    val title: String? = null,
    val message: String? = null,
    val ask: String? = null,
    val answers: List<JsonElement>? = null,
    /** Réponse donnée sur une autre TV (`ask_close`). */
    val answer: JsonElement? = null,
    val by: String? = null,
    val timeout: Double? = null,
    val image: String? = null,
    val tag: JsonElement? = null,
    val target: JsonElement? = null,
    val icon: String? = null,
    val iconColor: String? = null,
    val corner: String? = null,
    val video: String? = null,
) {
    private val imageId: String?
        get() = image?.takeIf { it.isNotBlank() }

    /** Id d'ordre : un entier (ou un texte numérique). */
    private val orderId: Long?
        get() = id.asText()?.trim()?.toLongOrNull()

    /** Identifiant de notification : `tag`, sinon un `id` texte non numérique. */
    private val notificationTag: String?
        get() = tag.asText()?.takeIf { it.isNotBlank() }
            ?: id.asText()?.takeIf { it.isNotBlank() && it.trim().toLongOrNull() == null }

    /** Type inconnu ou `show` sans page : ignoré, comme le demande le contrat. */
    fun toCommand(): TvCommand? = when (type) {
        "show" -> page?.takeIf { it.isNotBlank() }?.let {
            TvCommand.Show(orderId, it, (duration ?: 0.0).toInt().coerceAtLeast(0))
        }
        "notify" -> TvCommand.Notify(
            orderId, title.orEmpty(), message.orEmpty(), imageId,
            // Contrat : 3 à 120 s ; une valeur hors bornes y est ramenée.
            durationSec = duration?.toInt()?.coerceIn(MIN_NOTIFY_S, MAX_NOTIFY_S),
            tag = notificationTag,
            icon = icon?.takeIf { it.isNotBlank() },
            iconColor = parseColor(iconColor),
            corner = Corner.fromApi(corner, Corner.TopEnd),
            video = VideoUrl.of(video),
        )
        "dismiss" -> target.asText()?.takeIf { it.isNotBlank() }?.let { TvCommand.Dismiss(orderId, it) }
        "exit" -> TvCommand.Exit(orderId)
        // Fermeture d'une question à plusieurs TV : le jeton est indispensable.
        "ask_close" -> ask?.takeIf { it.isNotBlank() }?.let {
            TvCommand.AskClose(
                id = orderId,
                ask = it,
                answer = answer.asText()?.takeIf { text -> text.isNotBlank() },
                by = by?.takeIf { name -> name.isNotBlank() },
            )
        }
        // Question sans jeton ou sans réponse possible : inutilisable, ignorée.
        "ask" -> {
            val choices = answers.orEmpty().mapNotNull { it.asText()?.takeIf { text -> text.isNotBlank() } }
            ask?.takeIf { it.isNotBlank() && choices.isNotEmpty() }?.let {
                TvCommand.Ask(
                    id = orderId,
                    ask = it,
                    title = title.orEmpty(),
                    message = message.orEmpty(),
                    answers = choices,
                    timeoutSec = (timeout ?: 0.0).toInt().coerceAtLeast(0),
                    image = imageId,
                    video = VideoUrl.of(video),
                )
            }
        }
        else -> null
    }
}

private const val MIN_NOTIFY_S = 3
private const val MAX_NOTIFY_S = 120

@Serializable
private data class ChangeDto(val tile: String? = null, val value: JsonElement? = null)
