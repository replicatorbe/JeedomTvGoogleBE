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
