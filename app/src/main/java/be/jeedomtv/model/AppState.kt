package be.jeedomtv.model

/** Écran affiché. Les vues se contentent de dessiner l'écran courant. */
sealed interface Screen {
    data object Setup : Screen
    data object Loading : Screen
    data object Pages : Screen
}

/**
 * Mode réglage d'une tuile (curseur, volet). [pending] est la valeur en attente, envoyée par OK ;
 * null pour un volet sans position (▲ / ▼ / OK envoient directement monter, descendre, arrêter).
 */
data class Adjust(
    val tileId: String,
    val pending: Double?,
)

/** Ordre en attente de confirmation (tuile `confirm: true`). */
data class PendingAction(
    val tileId: String,
    val action: TileAction,
    val value: Double? = null,
    /** Description lisible de l'ordre, affichée dans la boîte de confirmation. */
    val label: String,
)

/** Bandeau d'un ordre `notify` de Jeedom. */
data class Banner(
    val title: String,
    val message: String,
)

/**
 * Superposition par-dessus une autre application (vidéo), quand l'application est cachée :
 * elle s'affiche sans faire passer la vidéo en arrière-plan.
 */
sealed interface Overlay {
    data object None : Overlay

    /** Bandeau d'un ordre `notify` : ni focusable ni tactile, la vidéo garde la main. */
    data class Notice(val banner: Banner) : Overlay

    /**
     * Panneau d'un ordre `show` : la page [pageId] en grille compacte, pilotable à la télécommande.
     * [durationSec] : fermeture automatique (0 = après une minute sans touche).
     */
    data class Panel(val pageId: String, val durationSec: Int) : Overlay
}

/** État complet de l'application : la seule chose que les vues observent. */
data class AppState(
    val screen: Screen = Screen.Loading,
    val config: JeedomConfig? = null,
    /** Nom de la TV donné par Jeedom (réponse de `ping`). */
    val tvName: String? = null,
    val revision: String? = null,
    val pages: List<Page> = emptyList(),
    /** Page affichée (onglet sélectionné). */
    val pageIndex: Int = 0,
    /** Index (dans la page) de la tuile sélectionnée : porté par l'état, pas par le focus Compose. */
    val focusedIndex: Int = 0,
    val adjust: Adjust? = null,
    val confirm: PendingAction? = null,
    /** Erreur de connexion, affichée sur l'écran de configuration. */
    val error: String? = null,
    /** Message temporaire (ordre refusé, Jeedom injoignable…). */
    val notice: String? = null,
    /** Tuile qui vient de recevoir un ordre : bref retour visuel (scénario). */
    val flashTileId: String? = null,
    /** La boucle des changements en direct est en erreur. */
    val offline: Boolean = false,
    /** L'écran de l'application est visible (sinon la TV affiche une autre application). */
    val uiVisible: Boolean = false,
    /** Écran de la TV allumé (false : TV en veille). */
    val screenOn: Boolean = true,
    /** Bandeau `notify` affiché quelques secondes. */
    val banner: Banner? = null,
    /** Demande de passage en arrière-plan (ordre `exit`) ; la vue l'exécute puis acquitte. */
    val exitRequested: Boolean = false,
    /** Un ordre `show` veut afficher l'application alors qu'elle est en arrière-plan. */
    val foregroundRequested: Boolean = false,
    /** Superposition affichée par-dessus une autre application. */
    val overlay: Overlay = Overlay.None,
) {
    val currentPage: Page?
        get() = pages.getOrNull(pageIndex)

    val focusedTile: Tile?
        get() = currentPage?.tiles?.getOrNull(focusedIndex)

    /** Tuile en mode réglage, s'il y en a une. */
    val adjustTile: Tile?
        get() = adjust?.let { a -> findTile(a.tileId) }

    /** État signalé à Jeedom (`POST ?action=state`). */
    val tvState: TvState
        get() {
            // Le panneau en superposition compte comme un affichage : on y regarde une page.
            val panel = overlay is Overlay.Panel
            return TvState(
                visible = uiVisible || panel,
                screenOn = screenOn,
                page = if (screen == Screen.Pages || panel) currentPage?.id else null,
            )
        }

    fun findTile(id: String): Tile? =
        pages.asSequence().flatMap { it.tiles }.firstOrNull { it.id == id }

    companion object {
        /** Colonnes de la grille de tuiles. */
        const val GRID_COLUMNS = 4
    }
}
