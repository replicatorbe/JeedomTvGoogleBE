package be.jeedomtv.model

/**
 * Une page : un ensemble de tuiles affiché en grille ([PageType.Tiles]), ou un tableau des trains
 * ([PageType.Board], sans tuile). Une page cachée n'est ni dans les onglets ni dans la navigation
 * ◀ ▶ : seuls `show` et les touches de couleur l'ouvrent.
 */
data class Page(
    val id: String,
    val name: String,
    val tiles: List<Tile>,
    val type: PageType = PageType.Tiles,
    val hidden: Boolean = false,
    /** Contenu du tableau d'une page [PageType.Board] ; null pour une page de tuiles. */
    val board: Board? = null,
) {
    val isBoard: Boolean
        get() = type == PageType.Board
}

/** Type de page du contrat ; un type inconnu n'a pas d'équivalent : la page est ignorée. */
enum class PageType(val apiName: String) {
    Tiles("tiles"),
    Board("board");

    companion object {
        /** Champ absent : `tiles` ; type inconnu : null. */
        fun fromApi(name: String?): PageType? =
            if (name == null) Tiles else entries.firstOrNull { it.apiName == name }
    }
}

/** Pages de la TV et révision de leur configuration (change dès que les pages changent dans Jeedom). */
data class Layout(
    val revision: String,
    val pages: List<Page>,
    /** Page (id) de chaque touche de couleur ; null si le plugin n'envoie pas `keys`. */
    val keys: Map<ColorKey, String>? = null,
    /** Bandeau d'infos (`header`) ; vide si le plugin n'en envoie pas. */
    val header: List<HeaderItem> = emptyList(),
    /** Barre d'état (`status`) ; null : pas de barre. */
    val status: StatusBar? = null,
)

/** Le contrat limite le bandeau d'infos (`header`) à 6 éléments. */
const val MAX_HEADER_ITEMS = 6

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
