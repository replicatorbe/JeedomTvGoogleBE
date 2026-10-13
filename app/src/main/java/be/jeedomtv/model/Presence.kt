package be.jeedomtv.model

/** Qui a allumé l'écran la dernière fois (`wokeBy` du contrat). */
enum class WokeBy(val apiName: String) {
    Jeedom("jeedom"),
    Human("human"),
}

/**
 * Ce que la TV sait réellement mesurer ou faire, selon les permissions accordées
 * (`capabilities` du contrat). Un signal sans permission est déclaré absent, jamais deviné.
 */
data class Capabilities(
    /** Touches de la télécommande observées (service d'accessibilité actif). */
    val keys: Boolean = false,
    /** Application au premier plan connue (accessibilité, à défaut statistiques d'utilisation). */
    val foreground: Boolean = false,
    /** Sessions média lisibles (écouteur de notifications). */
    val media: Boolean = false,
    /** Allumer l'écran. */
    val wake: Boolean = false,
    /** Mettre l'écran en veille. */
    val sleep: Boolean = false,
)

/**
 * Présence humaine et propriété de l'allumage, telles que signalées à Jeedom dans `state`.
 * `idleSeconds` n'en fait pas partie : il change à chaque seconde et se calcule à l'envoi.
 */
data class PresenceReport(
    val inUse: Boolean,
    /** Paquet de l'application au premier plan ; null si inconnu. */
    val foreground: String?,
    /** Une session média est en lecture ; null si inconnu. */
    val playing: Boolean?,
    /** L'écran de veille (Ambiant ou le nôtre) est actif. */
    val dreaming: Boolean,
    val wokeBy: WokeBy?,
    val humanTookOver: Boolean,
    val capabilities: Capabilities,
)

/** Une permission accordée par adb, pour l'écran de diagnostic. */
data class PermissionStatus(
    val label: String,
    /** null : sans objet sur cette version d'Android. */
    val granted: Boolean?,
)

/** `mode` d'un ordre `show`. */
enum class ShowMode {
    /** Comportement historique : la page s'affiche. */
    Full,

    /** Plein écran si la TV n'est pas en usage, sinon une notification discrète. */
    Auto,
}

/** Version discrète d'un `show` en `mode: auto` (`fallback`) ; champs absents = valeurs par défaut. */
data class ShowFallback(
    val title: String? = null,
    val message: String? = null,
    val icon: String? = null,
    val durationSec: Int? = null,
)
