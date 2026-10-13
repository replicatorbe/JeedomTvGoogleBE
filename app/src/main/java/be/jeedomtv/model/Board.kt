package be.jeedomtv.model

/*
 * Tableau des trains (page `board` du contrat) : les prochains départs d'un ou plusieurs trajets
 * SNCB, calculés par le plugin. La TV n'interprète rien : elle affiche.
 */

/** Le contrat limite un tableau à 3 sections, 6 trains et 2 notes par section. */
const val MAX_BOARD_SECTIONS = 3
const val MAX_BOARD_TRAINS = 6
const val MAX_BOARD_NOTES = 2

/** Météo : 6 colonnes horaires au plus, textes de 120 caractères au plus. */
const val MAX_WEATHER_HOURS = 6
const val MAX_WEATHER_TEXT = 120

/** Contenu d'une page `board`, remplacé tel quel par `changes.boards`. [weather] : null sans météo. */
data class Board(val sections: List<BoardSection> = emptyList(), val weather: BoardWeather? = null)

/**
 * Météo du matin en tête du tableau, lue par le plugin dans le cache du plugin IRM. Avec une
 * [note] seule (données périmées, équipement absent), les autres champs peuvent être vides.
 */
data class BoardWeather(
    val city: String = "",
    /** Température observée (°C) ; null si inconnue. */
    val temp: Int? = null,
    /** Icône `mdi:…` du temps qu'il fait ; vide si inconnue. */
    val icon: String = "",
    val condition: String = "",
    /** Températures prévues du jour (°C). */
    val tmin: Int? = null,
    val tmax: Int? = null,
    /** La pluie à venir en une phrase ; vide s'il n'y a rien à signaler. */
    val rain: String = "",
    /** Vigilance en cours (libellé) ; vide sinon. */
    val warning: String = "",
    /** 0 (aucune) à 3 (rouge). */
    val warningLevel: Int = 0,
    /** Heure (`HH:MM`) de la dernière lecture réussie de l'IRM ; vide si inconnue. */
    val updated: String = "",
    /** Avertissement à afficher (« Météo non relue depuis 06:40 ») ; vide sinon. */
    val note: String = "",
    val hours: List<WeatherHour> = emptyList(),
)

/** Une colonne horaire : heure de début (`HH`), icône, température et risque de pluie (%). */
data class WeatherHour(
    val h: String = "",
    val icon: String = "",
    val temp: Int? = null,
    /** Risque de pluie 0 à 100 ; null si inconnu. */
    val rain: Int? = null,
)

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
