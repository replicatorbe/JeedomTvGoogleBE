package be.jeedomtv.model

/** Une page d'onglet : un ensemble de tuiles affiché en grille. */
data class Page(
    val id: String,
    val name: String,
    val tiles: List<Tile>,
)

/** Pages de la TV et révision de leur configuration (change dès que les pages changent dans Jeedom). */
data class Layout(
    val revision: String,
    val pages: List<Page>,
    /** Page (id) de chaque touche de couleur ; null si le plugin n'envoie pas `keys`. */
    val keys: Map<ColorKey, String>? = null,
    /** Bandeau d'infos (`header`) ; vide si le plugin n'en envoie pas. */
    val header: List<HeaderItem> = emptyList(),
)

/**
 * Info de la maison affichée en permanence en haut des pages et du panneau (`header` du contrat).
 * Aucune action possible ; sa valeur suit `changes`, comme celle d'une tuile.
 */
data class HeaderItem(
    val id: String,
    val label: String,
    val icon: TileIcon = TileIcon.Generic,
    /** Valeur brute ; null si inconnue. */
    val value: String? = null,
    val unit: String = "",
)

/** Touche de couleur de la télécommande, raccourci vers une page (`keys` du contrat). */
enum class ColorKey(val apiName: String) {
    Red("red"),
    Green("green"),
    Yellow("yellow"),
    Blue("blue");

    companion object {
        fun fromApi(name: String?): ColorKey? = entries.firstOrNull { it.apiName == name }
    }
}
