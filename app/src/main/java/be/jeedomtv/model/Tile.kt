package be.jeedomtv.model

/** Type de tuile du contrat ; un type inconnu est affiché comme [Info]. */
enum class TileType(val apiName: String) {
    Switch("switch"),
    Shutter("shutter"),
    Slider("slider"),
    Info("info"),
    Scene("scene"),

    /** Bouton : exécute une commande action choisie dans le plugin (CameraOnTv, portail…). */
    Button("button"),

    /** Liste de choix : mode de clim, source de chauffe… (`choices`, action `set` avec une valeur texte). */
    Select("select");

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
    Camera("camera"),
    Sun("sun"),
    Rain("rain"),
    Trash("trash"),
    Power("power"),
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
    Press("press"),
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
    /** Choix d'une tuile `select`, dans l'ordre du plugin ; vide pour les autres types. */
    val choices: List<Choice> = emptyList(),
) {
    /** Bornes connues : réglage d'une position (volet) ou d'une consigne (curseur). */
    val hasRange: Boolean
        get() = min != null && max != null && max > min

    /** Interrupteur : "1" ou tout nombre > 0 = allumé. */
    val isOn: Boolean
        get() = (value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: 0.0) > 0.0

    /** Index du choix correspondant à la valeur actuelle, ou -1. */
    val choiceIndex: Int
        get() = choices.indexOfFirst { it.value == value }

    /** Libellé du choix courant ; à défaut la valeur brute, null sans valeur. */
    val choiceLabel: String?
        get() = choices.getOrNull(choiceIndex)?.label ?: value

    /** Valeur numérique, si elle en est une. */
    val numericValue: Double?
        get() = value?.trim()?.replace(',', '.')?.toDoubleOrNull()
}

/** Un choix d'une tuile `select` : [value] est envoyée au plugin, [label] est affiché. */
data class Choice(val value: String, val label: String)

/** Nouvelle valeur d'une tuile, reçue par `changes`. */
data class TileChange(val tile: String, val value: String?)

/** Réponse de `changes` : curseur suivant, révision actuelle, valeurs modifiées et ordres pour la TV. */
data class Changes(
    /** Curseur opaque à renvoyer tel quel au prochain appel. */
    val since: String,
    val revision: String?,
    val changes: List<TileChange>,
    val commands: List<TvCommand> = emptyList(),
    /** `status` présent dans la réponse : la barre d'état est remplacée par [status] (null : retirée). */
    val statusChanged: Boolean = false,
    val status: StatusBar? = null,
)

/** Ordre de Jeedom pour la TV (« Commandes Jeedom → TV » du contrat). */
sealed interface TvCommand {
    /** Entier croissant par TV ; un id déjà traité est ignoré. Null si le plugin ne l'a pas fourni. */
    val id: Long?

    /** Affiche la page [page] ; retour à l'écran précédent après [durationSec] s (0 = sans retour). */
    data class Show(override val id: Long?, val page: String, val durationSec: Int = 0) : TvCommand

    /** Bandeau d'environ 8 s si l'application est visible. */
    data class Notify(
        override val id: Long?,
        val title: String,
        val message: String,
        /** Identifiant d'une image jointe ; null sans image. */
        val image: String? = null,
        /** Durée du bandeau en secondes (3 à 120) ; null : durée par défaut de la TV. */
        val durationSec: Int? = null,
        /** Identifiant de la notification : même `tag` = remplace ; `dismiss` le retire. */
        val tag: String? = null,
        /** Icône `mdi:` et sa couleur (ARGB), à gauche du titre quand il n'y a ni image ni vidéo. */
        val icon: String? = null,
        val iconColor: Int? = null,
        /** Coin du bandeau par-dessus une autre application. */
        val corner: Corner = Corner.TopEnd,
        /** Flux joué en direct, sans le son, dans le bandeau ; l'image sert d'attente et de repli. */
        val video: VideoUrl? = null,
    ) : TvCommand

    /** Retire tout de suite la notification de `tag` [target], si elle est encore affichée. */
    data class Dismiss(override val id: Long?, val target: String) : TvCommand

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
        /** Identifiant d'une image jointe (photo du portier…) ; null sans image. */
        val image: String? = null,
        /** Flux en direct à la place de la photo ; l'image sert d'attente et de repli. */
        val video: VideoUrl? = null,
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
