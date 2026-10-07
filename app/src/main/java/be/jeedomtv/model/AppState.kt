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

/**
 * Mode de choix d'une tuile `select` : [selected] est l'index du choix en attente, envoyé par OK.
 * La valeur actuelle de la tuile reste mise en évidence dans la liste.
 */
data class ChoiceMode(
    val tileId: String,
    val selected: Int,
)

/** Ordre en attente de confirmation (tuile `confirm: true`). */
data class PendingAction(
    val tileId: String,
    val action: TileAction,
    val value: Double? = null,
    /** Description lisible de l'ordre, affichée dans la boîte de confirmation. */
    val label: String,
    /** Valeur texte d'un `set` sur une tuile `select`. */
    val choice: String? = null,
)

/** Bandeau d'un ordre `notify` de Jeedom. */
data class Banner(
    val title: String,
    val message: String,
    /** Identifiant de l'image jointe (`GET ?action=image`) ; null sans image. */
    val image: String? = null,
    /** Octets de l'image, une fois téléchargée (décodée par la vue) ; null tant qu'elle ne l'est pas. */
    val imageBytes: ByteArray? = null,
    /** Identifiant de la notification (`tag`), pour `dismiss`. */
    val tag: String? = null,
    /** Icône `mdi:` et sa couleur (ARGB), à gauche du titre quand il n'y a ni image ni vidéo. */
    val icon: String? = null,
    val iconColor: Int? = null,
    /** Coin du bandeau par-dessus une autre application. */
    val corner: Corner = Corner.TopEnd,
    /** Flux en direct dans le bandeau, sans le son. */
    val video: VideoUrl? = null,
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

/** Étape d'une question : choix, envoi de la réponse, puis résultat affiché ~2 s. */
sealed interface QuestionStatus {
    data object Choosing : QuestionStatus
    data object Sending : QuestionStatus
    data class Sent(val answer: String) : QuestionStatus
    data class Failed(val message: String) : QuestionStatus
}

/**
 * Question de Jeedom en cours (ordre `ask`). Elle passe au-dessus de tout (pages, réglage,
 * panneau, bandeau) ; à sa fermeture, ce qui était affiché derrière revient tel quel.
 */
data class Question(
    /** Jeton à renvoyer avec la réponse. */
    val ask: String,
    val title: String,
    val message: String,
    val answers: List<String>,
    val timeoutSec: Int,
    /** Secondes restantes avant la fermeture automatique (compte à rebours affiché). */
    val remainingSec: Int,
    /** Index de la réponse sélectionnée ; la première par défaut. */
    val selected: Int = 0,
    val status: QuestionStatus = QuestionStatus.Choosing,
    /** Affichée en superposition, par-dessus une autre application (sinon dans l'application). */
    val inOverlay: Boolean = false,
    /** Identifiant de l'image jointe (photo du portier…) ; null sans image. */
    val image: String? = null,
    /** Octets de l'image, une fois téléchargée (décodée par la vue) ; null tant qu'elle ne l'est pas. */
    val imageBytes: ByteArray? = null,
    /** Flux en direct à la place de la photo, joué tant qu'on n'a pas répondu. */
    val video: VideoUrl? = null,
)

/**
 * Zone qui reçoit les flèches sur l'écran des pages et dans le panneau : la grille de tuiles,
 * ou la rangée d'onglets (◀ ▶ y changent de page, pour les télécommandes sans CH+ / CH-).
 */
enum class FocusZone { Tiles, Tabs }

/** État complet de l'application : la seule chose que les vues observent. */
data class AppState(
    val screen: Screen = Screen.Loading,
    val config: JeedomConfig? = null,
    /** Nom de la TV donné par Jeedom (réponse de `ping`). */
    val tvName: String? = null,
    val revision: String? = null,
    val pages: List<Page> = emptyList(),
    /** Raccourcis des touches de couleur (`keys` du layout) ; null si le plugin n'en envoie pas. */
    val colorKeys: Map<ColorKey, String>? = null,
    /** Bandeau d'infos (`header` du layout) ; vide : pas de bandeau. */
    val header: List<HeaderItem> = emptyList(),
    /** Barre d'état permanente (`status`) ; null : pas de barre. */
    val status: StatusBar? = null,
    /** Notre écran de veille est affiché : la barre d'état s'efface (il a sa propre horloge). */
    val dreaming: Boolean = false,
    /** Page affichée (onglet sélectionné). */
    val pageIndex: Int = 0,
    /** Index (dans la page) de la tuile sélectionnée : porté par l'état, pas par le focus Compose. */
    val focusedIndex: Int = 0,
    /** Tuiles ou onglets ; dans les onglets, aucune tuile n'est mise en avant. */
    val focusZone: FocusZone = FocusZone.Tiles,
    val adjust: Adjust? = null,
    /** Mode de choix d'une tuile `select`. */
    val choice: ChoiceMode? = null,
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
    /** Question de Jeedom en cours. */
    val question: Question? = null,
) {
    val currentPage: Page?
        get() = pages.getOrNull(pageIndex)

    val focusedTile: Tile?
        get() = currentPage?.tiles?.getOrNull(focusedIndex)

    /** Tuile en mode de choix, s'il y en a une. */
    val choiceTile: Tile?
        get() = choice?.let { c -> findTile(c.tileId) }

    /** Tuile en mode réglage, s'il y en a une. */
    val adjustTile: Tile?
        get() = adjust?.let { a -> findTile(a.tileId) }

    /** État signalé à Jeedom (`POST ?action=state`). */
    val tvState: TvState
        get() {
            // Le panneau en superposition compte comme un affichage : on y regarde une page.
            val panel = overlay is Overlay.Panel
            val questionOverlay = question?.inOverlay == true
            return TvState(
                visible = uiVisible || panel || questionOverlay,
                screenOn = screenOn,
                page = if (screen == Screen.Pages || panel) currentPage?.id else null,
            )
        }

    /**
     * Les fenêtres de l'application reçoivent elles-mêmes les touches : écran affiché, panneau
     * ou question en superposition. Sinon, une autre application a le focus de la télécommande.
     */
    val ownsRemoteKeys: Boolean
        get() = uiVisible || overlay is Overlay.Panel || question?.inOverlay == true

    /**
     * Index de la page associée à la touche [key], ou null si la touche est inactive.
     * Sans `keys`, la touche rouge ouvre la première page et les autres ne font rien.
     */
    fun pageIndexFor(key: ColorKey): Int? {
        val keys = colorKeys
            ?: return if (key == ColorKey.Red && pages.isNotEmpty()) 0 else null
        val id = keys[key] ?: return null
        return pages.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }

    fun findTile(id: String): Tile? =
        pages.asSequence().flatMap { it.tiles }.firstOrNull { it.id == id }

    companion object {
        /** Colonnes de la grille de tuiles. */
        const val GRID_COLUMNS = 4
    }
}
