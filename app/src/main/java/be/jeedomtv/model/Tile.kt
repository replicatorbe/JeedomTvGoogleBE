package be.jeedomtv.model

/** Type de tuile du contrat ; un type inconnu est affiché comme [Info]. */
enum class TileType(val apiName: String) {
    Switch("switch"),
    Shutter("shutter"),
    Slider("slider"),
    Info("info"),
    Scene("scene");

    companion object {
        fun fromApi(name: String?): TileType = entries.firstOrNull { it.apiName == name } ?: Info
    }
}

/** Icône de tuile du contrat ; une icône inconnue devient [Generic]. */
enum class TileIcon(val apiName: String) {
    Light("light"),
    Plug("plug"),
    Shutter("shutter"),
    Thermostat("thermostat"),
    Temperature("temperature"),
    Scene("scene"),
    Fan("fan"),
    Lock("lock"),
    Alarm("alarm"),
    Generic("generic");

    companion object {
        fun fromApi(name: String?): TileIcon = entries.firstOrNull { it.apiName == name } ?: Generic
    }
}

/** Ordre envoyé au plugin pour une tuile (`action` de `POST ?action=exec`). */
enum class TileAction(val apiName: String) {
    On("on"),
    Off("off"),
    Toggle("toggle"),
    Up("up"),
    Down("down"),
    Stop("stop"),
    Set("set"),
    Run("run"),
}

/** Une tuile telle que la décrit le plugin. La TV ne connaît jamais les commandes Jeedom derrière. */
data class Tile(
    val id: String,
    val type: TileType,
    val name: String,
    val icon: TileIcon = TileIcon.Generic,
    val confirm: Boolean = false,
    /** Valeur brute ; null si la tuile n'a pas de retour d'état. */
    val value: String? = null,
    val unit: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
) {
    /** Bornes connues : réglage d'une position (volet) ou d'une consigne (curseur). */
    val hasRange: Boolean
        get() = min != null && max != null && max > min

    /** Interrupteur : "1" ou tout nombre > 0 = allumé. */
    val isOn: Boolean
        get() = (value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0) > 0.0

    /** Valeur numérique, si elle en est une. */
    val numericValue: Double?
        get() = value?.trim()?.replace(',', '.')?.toDoubleOrNull()
}

/** Nouvelle valeur d'une tuile, reçue par `changes`. */
data class TileChange(val tile: String, val value: String?)

/** Réponse de `changes` : curseur suivant, révision actuelle, valeurs modifiées et ordres pour la TV. */
data class Changes(
    /** Curseur opaque à renvoyer tel quel au prochain appel. */
    val since: String,
    val revision: String?,
    val changes: List<TileChange>,
    val commands: List<TvCommand> = emptyList(),
)

/** Ordre de Jeedom pour la TV (« Commandes Jeedom → TV » du contrat). */
sealed interface TvCommand {
    /** Entier croissant par TV ; un id déjà traité est ignoré. Null si le plugin ne l'a pas fourni. */
    val id: Long?

    /** Affiche la page [page] ; retour à l'écran précédent après [durationSec] s (0 = sans retour). */
    data class Show(override val id: Long?, val page: String, val durationSec: Int = 0) : TvCommand

    /** Bandeau d'environ 8 s si l'application est visible. */
    data class Notify(override val id: Long?, val title: String, val message: String) : TvCommand

    /** Passe l'application en arrière-plan. */
    data class Exit(override val id: Long?) : TvCommand

    /**
     * Question d'un bloc « Demander » : [ask] est le jeton à renvoyer avec la réponse choisie
     * parmi [answers] (au moins une) ; fermeture d'elle-même après [timeoutSec] s.
     */
    data class Ask(
        override val id: Long?,
        val ask: String,
        val title: String,
        val message: String,
        val answers: List<String>,
        val timeoutSec: Int,
    ) : TvCommand
}

/** État de la TV signalé à Jeedom par `POST ?action=state`. */
data class TvState(
    val visible: Boolean,
    val screenOn: Boolean,
    /** Id de la page affichée ; null hors écran des pages. */
    val page: String?,
    /** Version de l'application (`versionName`) ; null si inconnue (tests). */
    val appVersion: String? = null,
)

/** Réponse de `ping`. */
data class PingInfo(
    val tvId: Long?,
    val tvName: String?,
    val jeedomVersion: String?,
    val pluginVersion: String?,
)
