package be.jeedomtv.model

/** Coin de l'écran : barre d'état (`status.corner`) et bandeau d'une notification (`notify.corner`). */
enum class Corner(val apiName: String) {
    BottomStart("bottom_start"),
    BottomEnd("bottom_end"),
    TopStart("top_start"),
    TopEnd("top_end");

    val isTop: Boolean get() = this == TopStart || this == TopEnd
    val isStart: Boolean get() = this == BottomStart || this == TopStart

    companion object {
        fun fromApi(name: String?, default: Corner): Corner =
            entries.firstOrNull { it.apiName == name?.trim()?.lowercase() } ?: default
    }
}

/** Forme d'un indicateur de la barre d'état. */
enum class StatusShape(val apiName: String) {
    Circle("circle"),
    Rounded("rounded"),
    Rectangular("rectangular");

    companion object {
        fun fromApi(name: String?): StatusShape =
            entries.firstOrNull { it.apiName == name?.trim()?.lowercase() } ?: Rounded
    }
}

/**
 * Indicateur de la barre d'état : icône `mdi:` et texte facultatif, couleurs en ARGB.
 * Le plugin calcule tout ; la TV ne fait qu'afficher.
 */
data class StatusItem(
    val id: String,
    val icon: String?,
    val text: String = "",
    val iconColor: Int = WHITE,
    val textColor: Int = WHITE,
    val borderColor: Int = TRANSPARENT,
    val backgroundColor: Int = TRANSPARENT,
    val shape: StatusShape = StatusShape.Rounded,
)

/**
 * Barre d'état permanente (`status` du contrat) : l'heure et des indicateurs, dans un coin,
 * par-dessus toutes les applications. [opacity] : 0 à 100 (0 = masquée).
 */
data class StatusBar(
    val corner: Corner = Corner.BottomStart,
    val clock: Boolean = true,
    val opacity: Int = 100,
    val items: List<StatusItem> = emptyList(),
) {
    /** Quelque chose à montrer : opacité non nulle, et l'heure ou au moins un indicateur. */
    val visible: Boolean
        get() = opacity > 0 && (clock || items.isNotEmpty())
}

const val WHITE: Int = -0x1
const val TRANSPARENT: Int = 0

/** `#RRGGBB` (opaque) ou `#AARRGGBB` → ARGB ; null si la valeur n'est pas une couleur. */
fun parseColor(value: String?): Int? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    if (hex.length != 6 && hex.length != 8) return null
    val parsed = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
}

/**
 * URL d'un flux vidéo (RTSP, HLS). Elle contient souvent des identifiants : elle n'apparaît
 * jamais en clair dans un `toString` (journaux, rapports d'erreur).
 */
class VideoUrl(val url: String) {
    override fun toString(): String = "VideoUrl(***)"
    override fun equals(other: Any?): Boolean = other is VideoUrl && other.url == url
    override fun hashCode(): Int = url.hashCode()

    /** RTSP (caméras) ; sinon HTTP(S), HLS si l'URL désigne une liste `.m3u8`. */
    val isRtsp: Boolean get() = url.startsWith("rtsp://", ignoreCase = true) || url.startsWith("rtsps://", ignoreCase = true)

    companion object {
        /** Seuls les flux RTSP et HTTP(S) sont lus ; autre chose (ou vide) : pas de vidéo. */
        fun of(value: String?): VideoUrl? {
            val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val scheme = url.substringBefore("://", "").lowercase()
            return if (scheme in setOf("rtsp", "rtsps", "http", "https")) VideoUrl(url) else null
        }
    }
}
