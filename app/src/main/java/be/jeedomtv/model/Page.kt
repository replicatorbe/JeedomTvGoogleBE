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
)
