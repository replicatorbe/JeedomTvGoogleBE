package be.jeedomtv.model

/*
 * Tableau des trains (page `board` du contrat) : les prochains départs d'un ou plusieurs trajets
 * SNCB, calculés par le plugin. La TV n'interprète rien : elle affiche.
 */

/** Le contrat limite un tableau à 3 sections, 6 trains et 2 notes par section. */
const val MAX_BOARD_SECTIONS = 3
const val MAX_BOARD_TRAINS = 6
const val MAX_BOARD_NOTES = 2

/** Contenu d'une page `board`, remplacé tel quel par `changes.boards`. */
data class Board(val sections: List<BoardSection> = emptyList())

/** Un trajet : titre (« Soignies → Bruxelles »), jour affiché, heure de lecture et départs. */
data class BoardSection(
    val id: String,
    val title: String,
    /** Vide pour aujourd'hui, sinon le jour des trains (`Demain`, `12/10`). */
    val day: String = "",
    /** Heure (`HH:MM`) de la dernière lecture réussie d'iRail ; vide si inconnue. */
    val updated: String = "",
    /** Perturbations du réseau qui concernent le trajet. */
    val notes: List<String> = emptyList(),
    val trains: List<Train> = emptyList(),
)

/** Un départ. [time] est l'heure prévue, [real] l'heure réelle, [delay] le retard en minutes. */
data class Train(
    val time: String,
    val real: String = time,
    val delay: Int = 0,
    val vehicle: String = "",
    val direction: String = "",
    /** Vide si la voie est inconnue. */
    val platform: String = "",
    /** Voie inhabituelle : affichée en couleur d'alerte. */
    val platformChanged: Boolean = false,
    val transfers: Int = 0,
    val status: TrainStatus = TrainStatus.OnTime,
    /** Prochain train à prendre (au plus un par section). */
    val next: Boolean = false,
)

/** État d'un départ ; inconnu → [OnTime]. */
enum class TrainStatus(val apiName: String) {
    OnTime("ontime"),

    /** Retard sous le seuil du trajet. */
    Slight("slight"),

    /** Retard au seuil ou plus. */
    Delayed("delayed"),
    Canceled("canceled");

    companion object {
        fun fromApi(name: String?): TrainStatus = entries.firstOrNull { it.apiName == name } ?: OnTime
    }
}
