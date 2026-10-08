package be.jeedomtv.controller

import be.jeedomtv.model.Adjust
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Banner
import be.jeedomtv.model.Board
import be.jeedomtv.model.ChoiceMode
import be.jeedomtv.model.ColorKey
import be.jeedomtv.model.FocusZone
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.Page
import be.jeedomtv.model.PendingAction
import be.jeedomtv.model.Question
import be.jeedomtv.model.QuestionStatus
import be.jeedomtv.model.Screen
import be.jeedomtv.model.SettingsRepository
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TileChange
import be.jeedomtv.model.TileType
import be.jeedomtv.model.TvCommand
import be.jeedomtv.model.TvState
import be.jeedomtv.model.driver.AuthenticationException
import be.jeedomtv.model.driver.JeedomDriver
import be.jeedomtv.model.driver.JeedomDriverFactory
import be.jeedomtv.model.driver.JeedomException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Le Contrôleur du MVC : interprète les commandes de la télécommande et les ordres de Jeedom,
 * pilote Jeedom et met à jour le [AppModel]. Il ne touche jamais à l'interface Android.
 *
 * Il vit dans l'Application (comme le service au premier plan) : la boucle des changements
 * tourne dès qu'une configuration existe, application visible ou non, écran allumé ou non.
 *
 * Tout son état interne est confiné au thread principal (celui de [scope]).
 */
class AppController(
    private val model: AppModel,
    private val settings: SettingsRepository,
    private val driverFactory: JeedomDriverFactory,
    private val scope: CoroutineScope,
    /** Sans la permission, un ordre reçu en arrière-plan ouvre l'activité (comportement d'avant). */
    private val overlayPermission: OverlayPermission = OverlayPermission { false },
    /** Version de l'application, envoyée avec chaque état (`appVersion`). */
    private val appVersion: String? = null,
    /** Horloge en ms, veille comprise (`SystemClock.elapsedRealtime` sur la TV). */
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    val state: StateFlow<AppState> = model.state

    /** Pilote de la configuration en service (null tant qu'aucune configuration n'est connue). */
    private var driver: JeedomDriver? = null

    /** Clé refusée alors que l'application était visible : la boucle attend une nouvelle saisie. */
    private var authBlocked = false

    /** Chargement ou connexion en cours ; annulé si une nouvelle connexion est demandée. */
    private var connectJob: Job? = null

    /** Boucle des changements en direct ; une seule à la fois. */
    private var changesJob: Job? = null

    /** Sans réponse de Jeedom pendant [UNREACHABLE_AFTER_MS] : il passe pour injoignable. */
    private var reachWatchdog: Job? = null

    /** Début de l'attente longue en cours ([elapsedMs]) : au-delà de [STALE_CALL_MS], elle est morte. */
    private var changesCallStartedAt = 0L

    /** Efface le message temporaire. */
    private var noticeTimer: Job? = null

    /** Efface le retour visuel d'une tuile. */
    private var flashTimer: Job? = null

    /** Efface le bandeau `notify`. */
    private var bannerTimer: Job? = null

    /**
     * Notifications en attente pendant qu'une autre est affichée (3 au plus, la plus ancienne
     * abandonnée au-delà). Hors de l'état : une vidéo en attente n'occupe aucun décodeur.
     */
    private val pendingNotifications = ArrayDeque<PendingNotification>()

    /** Dernière identité de notification attribuée ([Banner.id]). */
    private var lastBannerId = 0L

    /** Notification dont l'image est en cours de téléchargement ([Banner.id]). */
    private var bannerImageFor = 0L

    /** Efface la coche de confirmation d'une tuile. */
    private var confirmTimer: Job? = null

    /** Envoi différé (anti-rebond) de l'état de la TV à Jeedom. */
    private var stateTimer: Job? = null

    /** Dernier état programmé pour l'envoi : un état identique n'est pas renvoyé. */
    private var lastScheduledState: TvState? = null

    /** Jetons des questions fermées par `ask_close` (les plus récents seulement). */
    private val closedAsks = LinkedHashSet<String>()

    /** Ids des ordres déjà traités (les plus récents seulement). */
    private val handledCommandIds = LinkedHashSet<Long>()

    /** Écran à retrouver après un `show` temporaire, et le minuteur qui l'y ramène. */
    private var returnTarget: ReturnTarget? = null
    private var returnTimer: Job? = null

    /**
     * Écran à retrouver par Retour sur un tableau des trains (ouvert par `show` ou une touche de
     * couleur), même sans durée et même après une touche : un tableau n'a pas d'autre sortie.
     */
    private var boardReturn: BoardReturn? = null

    /** Quitte la page cachée affichée quand l'application reste derrière (voir [leaveHiddenPage]). */
    private var hiddenPageTimer: Job? = null

    /** Fermeture automatique de la superposition (durée de l'ordre, puis inactivité). */
    private var overlayTimer: Job? = null

    /** Sélection de l'application avant l'ouverture du panneau, rendue à sa fermeture. */
    private var panelReturn: PanelReturn? = null

    /** Compte à rebours de la question en cours. */
    private var questionTicker: Job? = null

    /** Affichage du résultat de la réponse, puis fermeture de la question. */
    private var questionResultTimer: Job? = null

    /** La question a ouvert l'activité (pas de superposition possible) : on la referme ensuite. */
    private var questionOpenedActivity = false

    /** Fermeture automatique du panneau suspendue le temps d'une question posée par-dessus. */
    private var panelPausedByQuestion = false

    /** Téléchargement de l'image jointe à la question en cours. */
    private var questionImageJob: Job? = null

    /** Téléchargement de l'image jointe au bandeau en cours (dans l'application ou par-dessus). */
    private var bannerImageJob: Job? = null

    /**
     * Horodatage logique de la dernière valeur connue de chaque tuile (optimiste, `changes`,
     * réponse d'`exec`) : la réponse tardive d'un ordre n'écrase pas une valeur plus récente.
     */
    private var valueClock = 0L
    private val tileStamps = HashMap<String, Long>()

    /** Horodatage du dernier layout : il vaut pour toutes les tuiles sans valeur plus récente. */
    private var layoutStamp = 0L

    /** Configuration dont viennent les ids d'ordres déjà traités (une autre TV a sa propre suite). */
    private var handledCommandsConfig: JeedomConfig? = null

    /** Au lancement : configuration enregistrée → connexion, sinon écran de configuration. */
    fun start() {
        launchExclusive {
            update { it.copy(screen = Screen.Loading, error = null) }
            val config = try {
                settings.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // Configuration illisible : on repart du formulaire.
            }
            if (config == null) {
                update { it.copy(screen = Screen.Setup) }
            } else {
                connectNow(config, userInitiated = false)
            }
        }
    }

    fun submitSetup(config: JeedomConfig) {
        launchExclusive { connectNow(config, userInitiated = true) }
    }

    /** Retourne true si la commande a été traitée (l'activité consomme alors la touche). */
    fun onCommand(command: RemoteCommand): Boolean {
        val current = state.value
        // Une question passe au-dessus de tout : elle reçoit les touches, où qu'elle s'affiche.
        current.question?.let { return onQuestionCommand(command, it) }
        // Panneau en superposition : c'est lui qui a le focus, pas l'activité.
        if (current.overlay is Overlay.Panel) return onPanelCommand(command, current)
        // Touche de couleur captée par le service d'accessibilité pendant une autre application.
        if (command is RemoteCommand.Color && !current.uiVisible) return onHiddenColor(command.key, current)
        // L'utilisateur a repris la main : un affichage temporaire devient définitif.
        cancelAutoReturn()
        return when (current.screen) {
            Screen.Pages -> onPagesCommand(command, current)
            Screen.Setup -> onSetupCommand(command, current)
            Screen.Loading -> false
        }
    }

    /** L'écran de l'application devient visible ou passe derrière une autre application. */
    fun onUiVisibilityChanged(visible: Boolean) {
        update { it.copy(uiVisible = visible, foregroundRequested = it.foregroundRequested && !visible) }
        // Application quittée sur une page cachée (Accueil sur le tableau des trains) : elle ne doit
        // pas être la page d'arrivée au prochain lancement. Après un court délai : une activité
        // recréée (changement de configuration) repasse aussitôt devant, le tableau doit rester.
        hiddenPageTimer?.cancel()
        hiddenPageTimer = if (visible) null else scope.launch {
            delay(HIDDEN_PAGE_DELAY_MS)
            hiddenPageTimer = null
            leaveHiddenPage()
        }
        // L'application complète est affichée : le panneau n'a plus lieu d'être.
        if (visible && state.value.overlay is Overlay.Panel) dismissOverlay(restoreSelection = false)
        // La notification affichée suit l'écran (application ou superposition), avec son temps restant.
        relocateNotification()
        // Une question suit l'écran : dans l'application si elle est affichée, sinon par-dessus.
        val question = state.value.question
        if (question != null) {
            val inOverlay = !visible && overlayPermission.granted()
            if (question.inOverlay != inOverlay && (visible || inOverlay)) {
                update { it.copy(question = it.question?.copy(inOverlay = inOverlay)) }
            }
        }
    }

    /**
     * La TV allume ou éteint son écran (sortie ou entrée en veille). Au réveil, la boucle repart
     * si la connexion d'avant la veille est probablement morte : attente en cours depuis plus
     * longtemps que ne le permet le plugin, ou boucle en erreur. Une attente encore valable
     * (écran éteint puis rallumé aussitôt) est gardée : abandonnée, elle resterait ouverte côté
     * Jeedom et pourrait y emporter un ordre, livré une seule fois.
     *
     * À la mise en veille, le tableau des trains se ferme (par-dessus la télé comme dans
     * l'application) : personne ne le regarde, et au réveil (le soir) il masquerait la télé avec
     * les trains du matin. Dans l'application, retour à l'écran ou à l'application d'avant.
     */
    fun onScreenChanged(on: Boolean) {
        update { it.copy(screenOn = on) }
        if (!on) {
            val current = state.value
            if (current.currentPage?.isBoard != true) return
            if (current.overlay is Overlay.Panel) {
                dismissOverlay(restoreSelection = true)
            } else if (current.screen == Screen.Pages) {
                val back = returnTarget
                cancelAutoReturn()
                if (back != null) restore(back) else leaveBoard(current)
            }
            return
        }
        val stale = elapsedMs() - changesCallStartedAt > STALE_CALL_MS
        if (changesJob?.isActive != true || state.value.offline || stale) onNetworkMaybeRestored()
    }

    /**
     * Rallumage de l'écran ou retour du réseau : la boucle des changements repart aussitôt,
     * sans attendre l'échec d'une attente longue sur une connexion morte.
     */
    fun onNetworkMaybeRestored() {
        if (driver != null && !authBlocked && connectJob?.isActive != true) startChangesLoop(reloadFirst = true)
    }

    /** La vue a mis l'application en arrière-plan suite à [AppState.exitRequested]. */
    fun onExitHandled() {
        update { it.copy(exitRequested = false) }
    }

    // --- Connexion ---------------------------------------------------------------------------

    /** Annule le travail en cours puis lance [block] : une seule connexion à la fois. */
    private fun launchExclusive(block: suspend CoroutineScope.() -> Unit) {
        stopChangesLoop()
        cancelAutoReturn()
        connectJob?.cancel()
        connectJob = scope.launch(block = block)
    }

    /**
     * [userInitiated] : configuration saisie à l'écran ; un échec y ramène avec le message.
     * Sinon (configuration enregistrée, typiquement au démarrage de la TV), un échec ne fait
     * qu'afficher « hors ligne » et la boucle réessaie, sauf clé refusée sous les yeux de l'utilisateur.
     */
    private suspend fun connectNow(config: JeedomConfig, userInitiated: Boolean) {
        update {
            it.copy(screen = Screen.Loading, config = config, error = null, adjust = null, choice = null, confirm = null)
        }
        val newDriver = driverFactory.create(config)
        try {
            val ping = newDriver.ping()
            currentCoroutineContext().ensureActive()
            // La configuration n'est enregistrée qu'après un ping réussi.
            saveConfig(config)
            val layout = newDriver.layout()
            // Une connexion annulée entre-temps ne doit pas écraser l'état.
            currentCoroutineContext().ensureActive()
            useDriver(newDriver, config)
            applyLayout(layout) { it.copy(tvName = ping.tvName, offline = false, error = null).copy(screen = Screen.Pages) }
            startChangesLoop(reloadFirst = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = (e as? JeedomException)?.message ?: GENERIC_ERROR
            if (userInitiated || (e is AuthenticationException && state.value.uiVisible)) {
                showSetupError(message)
                // L'ancienne configuration, s'il y en a une, reste en service.
                ensureChangesLoop()
            } else {
                // Jeedom injoignable au démarrage : la boucle réessaie et chargera les pages.
                useDriver(newDriver, config)
                update { it.copy(screen = Screen.Pages, offline = true, error = null) }
                startChangesLoop(reloadFirst = false)
            }
        }
    }

    /**
     * Nouveau pilote en service. Les ids d'ordres déjà traités ne valent que pour la TV qui les a
     * numérotés : avec une autre clé (autre équipement Jeedom), la suite repart de zéro.
     */
    private fun useDriver(newDriver: JeedomDriver, config: JeedomConfig) {
        driver = newDriver
        authBlocked = false
        if (config != handledCommandsConfig) {
            handledCommandIds.clear()
            handledCommandsConfig = config
        }
    }

    private suspend fun saveConfig(config: JeedomConfig) {
        try {
            settings.save(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Les tuiles s'affichent quand même ; la saisie sera redemandée au prochain lancement.
        }
    }

    private fun showSetupError(message: String) {
        update { it.copy(screen = Screen.Setup, error = message, adjust = null, choice = null, confirm = null) }
    }

    // --- Changements en direct ---------------------------------------------------------------

    private fun ensureChangesLoop() {
        if (changesJob?.isActive != true) startChangesLoop(reloadFirst = false)
    }

    /** (Re)démarre la boucle ; une seule tourne à la fois. */
    private fun startChangesLoop(reloadFirst: Boolean) {
        stopChangesLoop()
        val target = driver ?: return
        if (authBlocked) return
        changesJob = scope.launch { runChanges(target, reloadFirst) }
        armReachWatchdog()
    }

    private fun stopChangesLoop() {
        changesJob?.cancel()
        changesJob = null
        reachWatchdog?.cancel()
        reachWatchdog = null
    }

    /**
     * Relance l'attente : une attente longue répond au moins toutes les 25 s ; sans aucune réponse
     * pendant [UNREACHABLE_AFTER_MS] (appels en erreur ou bloqués), Jeedom est injoignable.
     */
    private fun armReachWatchdog() {
        reachWatchdog?.cancel()
        reachWatchdog = scope.launch {
            delay(UNREACHABLE_AFTER_MS)
            reachWatchdog = null
            setJeedomReachable(false)
        }
    }

    private fun setJeedomReachable(reachable: Boolean) {
        if (state.value.jeedomReachable != reachable) update { it.copy(jeedomReachable = reachable) }
    }

    /**
     * Attente longue des changements : applique les valeurs, recharge le layout si la révision
     * change, exécute les ordres de Jeedom. Après une erreur : « hors ligne », pause (de plus en
     * plus longue tant que Jeedom reste injoignable), rechargement du layout, reprise sans curseur.
     * L'écran ne repasse jamais par le chargement.
     */
    private suspend fun runChanges(target: JeedomDriver, reloadFirst: Boolean) {
        var since: String? = null
        var reload = reloadFirst
        var failures = 0
        while (true) {
            try {
                if (reload) {
                    reload = false
                    // Des changements ont pu être perdus : on repart d'un layout frais.
                    try {
                        applyLayout(target.layout())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Toujours injoignable : l'appel à changes échouera et réessaiera.
                    }
                }
                val restarted = since == null
                changesCallStartedAt = elapsedMs()
                val result = target.changes(since)
                currentCoroutineContext().ensureActive()
                since = result.since
                failures = 0
                update { it.copy(offline = false) }
                // Jeedom répond : la barre reprend aussitôt son aspect normal.
                setJeedomReachable(true)
                armReachWatchdog()
                applyChanges(result.changes)
                // Barre d'état : état complet, remplacé tel quel (hors révision : elle change souvent).
                if (result.statusChanged) update { it.copy(status = result.status) }
                // Tableaux des trains : contenu complet, remplacé tel quel (hors révision lui aussi).
                if (result.boards.isNotEmpty()) update { it.withBoards(result.boards) }
                val revision = result.revision
                // Les ordres ne sont livrés qu'une fois : un layout impossible à recharger ne doit
                // pas les perdre. Ils passent, puis l'erreur relance la boucle.
                val layoutError = if (revision != null && revision != state.value.revision) {
                    try {
                        applyLayout(target.layout())
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e
                    }
                } else {
                    null
                }
                // Démarrage ou reconnexion : Jeedom ne connaît peut-être pas encore notre état.
                if (restarted) resendState()
                result.commands.forEach { applyCommand(it) }
                if (layoutError != null) throw layoutError
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Boucle annulée pendant l'appel (réveil, nouvelle configuration) : pas de faux « hors ligne ».
                currentCoroutineContext().ensureActive()
                if (e is AuthenticationException && state.value.uiVisible) {
                    // Clé régénérée ou équipement désactivé dans Jeedom : il faut ressaisir la clé.
                    changesJob = null
                    authBlocked = true
                    showSetupError(e.message ?: GENERIC_ERROR)
                    return
                }
                update { it.copy(offline = true) }
                failures++
                if (failures >= UNREACHABLE_FAILURES) setJeedomReachable(false)
                delay(retryDelayMs(failures - 1))
                reload = true
                since = null
            }
        }
    }

    /** Valeurs reçues de Jeedom (`changes`, réponse d'`exec`) : elles deviennent les plus récentes. */
    private fun applyChanges(changes: List<TileChange>) {
        if (changes.isEmpty()) return
        changes.forEach { stamp(it.tile) }
        update { it.withChanges(changes) }
    }

    /** Nouvel horodatage de la valeur de [tileId]. */
    private fun stamp(tileId: String): Long {
        val now = ++valueClock
        tileStamps[tileId] = now
        return now
    }

    /** Horodatage de la valeur actuelle de [tileId]. */
    private fun stampOf(tileId: String): Long = tileStamps[tileId] ?: layoutStamp

    private suspend fun applyLayout(layout: Layout, extra: (AppState) -> AppState = { it }) {
        currentCoroutineContext().ensureActive()
        // Valeurs fraîches pour toutes les tuiles : une réponse d'`exec` en vol est périmée.
        tileStamps.clear()
        layoutStamp = ++valueClock
        update { extra(it).withLayout(layout) }
    }

    /** Recharge le layout hors de la boucle (tuile inconnue de Jeedom : la configuration a changé). */
    private fun reloadLayoutSoon() {
        val target = driver ?: return
        scope.launch {
            try {
                applyLayout(target.layout())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // La boucle des changements finira par le recharger.
            }
        }
    }

    // --- Ordres de Jeedom --------------------------------------------------------------------

    private fun applyCommand(command: TvCommand) {
        val id = command.id
        if (id != null) {
            if (!handledCommandIds.add(id)) return // Déjà traité.
            if (handledCommandIds.size > MAX_HANDLED_IDS) handledCommandIds.remove(handledCommandIds.first())
        }
        when (command) {
            is TvCommand.Show -> show(command)
            is TvCommand.Notify -> notify(command)
            is TvCommand.Dismiss -> dismissNotification(command.target)
            is TvCommand.Exit -> exit()
            is TvCommand.Ask -> ask(command)
            is TvCommand.AskClose -> askClose(command)
        }
    }

    /**
     * Affiche une page, en passant au premier plan si besoin. Avec une durée, retour ensuite à
     * l'écran d'avant (ou à l'application d'avant), sauf si la télécommande a servi entre-temps.
     */
    private fun show(command: TvCommand.Show) {
        val current = state.value
        // Formulaire en cours de saisie, ou connexion en cours : on ne l'interrompt pas.
        if (current.screen == Screen.Loading || (current.screen == Screen.Setup && current.uiVisible)) return
        val index = current.pages.indexOfFirst { it.id == command.page }
        if (index < 0) return
        val board = current.pages[index].isBoard
        // Application cachée (vidéo en cours) : panneau par-dessus, la vidéo reste au premier plan.
        // Sauf le tableau des trains : toujours l'application elle-même, en plein écran.
        if (!current.uiVisible && overlayPermission.granted() && !board) {
            openPanel(index, command)
            return
        }
        // Panneau ouvert par-dessus (touche de couleur) : l'application prend sa place, et l'écran
        // d'avant est celui d'avant le panneau.
        if (board && current.overlay is Overlay.Panel) dismissOverlay(restoreSelection = true)
        val before = state.value
        // Un affichage temporaire déjà en cours garde l'écran d'origine.
        val previous = returnTarget ?: ReturnTarget(
            screen = before.screen,
            pageId = before.currentPage?.id,
            focusedIndex = before.focusedIndex,
            background = !before.uiVisible,
        )
        cancelAutoReturn()
        if (board) rememberBoardReturn(command.page, previous)
        val changesSomething = previous.background || previous.screen != Screen.Pages || previous.pageId != command.page
        if (command.durationSec > 0 && changesSomething) {
            returnTarget = previous
            returnTimer = scope.launch {
                delay(command.durationSec * 1000L)
                returnTimer = null
                returnTarget = null
                restore(previous)
            }
        }
        update {
            it.copy(
                screen = Screen.Pages,
                pageIndex = index,
                focusedIndex = 0,
                focusZone = FocusZone.Tiles,
                adjust = null,
                choice = null,
                confirm = null,
                error = null,
                exitRequested = false,
                foregroundRequested = it.foregroundRequested || !it.uiVisible,
            )
        }
    }

    /** Fin d'un affichage temporaire : écran d'avant, et retour en arrière-plan s'il y était. */
    private fun restore(previous: ReturnTarget) {
        update { s ->
            val restored = when (previous.screen) {
                Screen.Pages -> {
                    // Page d'avant disparue (ou aucune) : la page d'accueil, jamais la page cachée affichée.
                    val index = s.pages.indexOfFirst { it.id == previous.pageId }.takeIf { it >= 0 }
                        ?: s.firstVisiblePageIndex
                        ?: AppState.NO_PAGE
                    val count = s.pages.getOrNull(index)?.tiles?.size ?: 0
                    s.copy(
                        screen = Screen.Pages,
                        pageIndex = index,
                        focusedIndex = previous.focusedIndex.coerceIn(0, (count - 1).coerceAtLeast(0)),
                        focusZone = FocusZone.Tiles,
                    )
                }
                // Retour suivant sur la configuration : les pages, pas le tableau qu'on vient de quitter.
                Screen.Setup -> s.awayFromHiddenPage().copy(screen = Screen.Setup)
                Screen.Loading -> s
            }
            if (previous.background) {
                restored.copy(exitRequested = s.uiVisible, foregroundRequested = false)
            } else {
                restored
            }
        }
    }

    private fun cancelAutoReturn() {
        returnTimer?.cancel()
        returnTimer = null
        returnTarget = null
    }

    /**
     * Page cachée affichée alors que l'application est derrière : retour à l'écran d'avant son
     * ouverture s'il était une page visible, sinon à la première page. Un affichage temporaire en
     * cours (`show` avec durée) s'en charge lui-même. Aussi à la fermeture d'un panneau, si la
     * page rendue à l'application est cachée.
     */
    private fun leaveHiddenPage() {
        val current = state.value
        val page = current.currentPage ?: return
        // Application revenue devant, ou panneau ouvert entre-temps (sa page est la sienne).
        if (current.uiVisible || current.overlay is Overlay.Panel) return
        if (!page.hidden || returnTarget != null) return
        val back = boardReturn?.takeIf { it.pageId == page.id }?.back
        boardReturn = null
        val index = back?.takeIf { it.screen == Screen.Pages }
            ?.let { b -> current.pages.indexOfFirst { it.id == b.pageId && !it.hidden }.takeIf { it >= 0 } }
            ?: current.firstVisiblePageIndex
            ?: AppState.NO_PAGE
        update { it.copy(pageIndex = index, focusedIndex = 0, focusZone = FocusZone.Tiles) }
    }

    /**
     * Un tableau des trains s'ouvre (page [pageId]) : Retour ramènera à [back]. Si un tableau est
     * déjà affiché, son propre retour est gardé (un tableau n'en ramène pas à un autre).
     */
    private fun rememberBoardReturn(pageId: String, back: ReturnTarget) {
        val current = state.value
        val keep = boardReturn?.takeIf {
            current.uiVisible && current.screen == Screen.Pages && it.pageId == current.currentPage?.id
        }?.back
        boardReturn = BoardReturn(pageId, keep ?: back)
    }

    /**
     * Touches du tableau des trains dans l'application : aucune action. Retour le ferme ; une
     * touche de couleur ouvre sa page (la sienne le ferme, comme sur le panneau) ; le reste est sans effet.
     */
    private fun onBoardCommand(command: RemoteCommand, current: AppState): Boolean {
        when (command) {
            RemoteCommand.Back -> return leaveBoard(current)
            is RemoteCommand.Color -> {
                val target = current.pageIndexFor(command.key) ?: return false
                if (target == current.pageIndex) return leaveBoard(current)
                val page = current.pages[target]
                // Vers un autre tableau : Retour ramènera toujours à l'écran d'avant le premier.
                if (page.isBoard) {
                    boardReturn = boardReturn?.takeIf { it.pageId == current.currentPage?.id }?.copy(pageId = page.id)
                } else {
                    boardReturn = null
                }
                update { it.copy(pageIndex = target, focusedIndex = 0, focusZone = FocusZone.Tiles) }
            }
            else -> Unit
        }
        return true
    }

    /**
     * Retour sur le tableau : écran (ou application) d'avant son ouverture, comme à la fin d'un
     * `show`. Sans écran connu : la première page ; si c'est le tableau lui-même, l'activité quitte
     * (false). Toutes les pages cachées : plus de page affichée, et retour à l'application d'avant.
     */
    private fun leaveBoard(current: AppState): Boolean {
        val pageId = current.currentPage?.id
        val back = boardReturn?.takeIf { it.pageId == pageId }?.back
        boardReturn = null
        val returnsHere = back != null && !back.background && back.screen == Screen.Pages && back.pageId == pageId
        if (back != null && !returnsHere) {
            restore(back)
            return true
        }
        val home = current.firstVisiblePageIndex
        if (home == null) {
            update { it.copy(pageIndex = AppState.NO_PAGE, focusedIndex = 0, focusZone = FocusZone.Tiles, exitRequested = it.uiVisible) }
            return true
        }
        if (home == current.pageIndex) return false
        update { it.copy(pageIndex = home, focusedIndex = 0, focusZone = FocusZone.Tiles) }
        return true
    }

    /**
     * Ordre `notify` : affiché tout de suite si rien n'est affiché, ou s'il porte le `tag` de la
     * notification affichée (il la remplace) ; sinon mis en file (même `tag` en attente : remplacé
     * à sa place ; 3 au plus, la plus ancienne en attente abandonnée). Les questions n'y passent pas.
     */
    private fun notify(command: TvCommand.Notify) {
        // Durée demandée par Jeedom (`duration`), sinon celle de la TV.
        val durationMs = command.durationSec?.let { it * 1000L } ?: BANNER_DURATION_MS
        val banner = Banner(
            command.title,
            command.message,
            image = command.image,
            tag = command.tag,
            icon = command.icon,
            iconColor = command.iconColor,
            corner = command.corner,
            video = command.video,
            durationMs = durationMs,
            id = ++lastBannerId,
        )
        val shown = displayedBanner()
        when {
            shown == null -> {
                if (!displayNotification(banner)) pumpNotifications()
            }
            banner.tag != null && shown.tag == banner.tag -> {
                clearDisplayedNotification()
                if (!displayNotification(banner)) pumpNotifications()
            }
            else -> {
                val waiting = PendingNotification(banner, elapsedMs())
                val index = pendingNotifications.indexOfFirst { banner.tag != null && it.banner.tag == banner.tag }
                if (index >= 0) {
                    pendingNotifications[index] = waiting
                } else {
                    pendingNotifications.addLast(waiting)
                    while (pendingNotifications.size > MAX_PENDING_NOTIFICATIONS) pendingNotifications.removeFirst()
                }
                syncWaitingCount()
            }
        }
    }

    /** Notification affichée (dans l'application, le panneau ou par-dessus la vidéo), ou null. */
    private fun displayedBanner(): Banner? =
        state.value.banner ?: (state.value.overlay as? Overlay.Notice)?.banner

    /**
     * Affiche la suivante en attente, si rien n'est affiché et qu'elle peut s'afficher quelque
     * part (sinon la file attend). Celle qui a attendu plus longtemps que sa propre durée, et plus
     * de [PENDING_STALE_MIN_MS], est périmée (« Le linge est sec » derrière une annonce de 2 min) :
     * abandonnée. Le plancher garde la file normale : deux notifications de 8 s coup sur coup
     * s'affichent bien l'une après l'autre.
     */
    private fun pumpNotifications() {
        while (displayedBanner() == null && pendingNotifications.isNotEmpty()) {
            if (notificationSurface(state.value) == null) break
            val next = pendingNotifications.removeFirst()
            val waited = elapsedMs() - next.receivedAt
            if (waited > maxOf(next.banner.durationMs, PENDING_STALE_MIN_MS)) continue
            if (displayNotification(next.banner)) break
        }
        syncWaitingCount()
    }

    private fun syncWaitingCount() {
        val count = pendingNotifications.size
        if (state.value.waitingNotifications != count) update { it.copy(waitingNotifications = count) }
    }

    /** Où une notification s'affiche maintenant, ou null si nulle part. */
    private fun notificationSurface(s: AppState): NotificationSurface? = when {
        // Quelqu'un regarde l'application, ou va la regarder : un `show` qui la ramène au premier
        // plan peut précéder le message dans la même réponse.
        s.uiVisible || s.foregroundRequested -> NotificationSurface.App
        // Le panneau affiche le bandeau en son sein (une ligne de texte).
        s.overlay is Overlay.Panel -> NotificationSurface.Panel
        // Bandeau en superposition, sans focus : la vidéo ne remarque rien.
        overlayPermission.granted() -> NotificationSurface.Overlay
        else -> null
    }

    /**
     * Affiche [banner] pour sa durée, comptée depuis cet affichage réel (ou pour ce qu'il lui
     * reste, s'il change de surface). Retourne false s'il ne peut pas s'afficher (application
     * cachée sans permission, ou temps écoulé).
     */
    private fun displayNotification(banner: Banner): Boolean {
        val now = elapsedMs()
        val shown = if (banner.endsAtMs == 0L) banner.copy(endsAtMs = now + banner.durationMs) else banner
        val remaining = shown.endsAtMs - now
        if (remaining <= 0) return false
        val surface = notificationSurface(state.value) ?: return false
        if (surface == NotificationSurface.Overlay) {
            update { it.copy(overlay = Overlay.Notice(shown), banner = null) }
        } else {
            update { it.copy(banner = shown) }
        }
        // Le panneau n'affiche que le texte : pas de téléchargement pour rien.
        if (surface != NotificationSurface.Panel) loadBannerImage(shown)
        bannerTimer?.cancel()
        bannerTimer = scope.launch {
            delay(remaining)
            bannerTimer = null
            clearDisplayedNotification()
            pumpNotifications()
        }
        return true
    }

    /** Retire la notification affichée, où qu'elle soit (sans afficher la suivante). */
    private fun clearDisplayedNotification() {
        bannerTimer?.cancel()
        bannerTimer = null
        bannerImageJob?.cancel()
        bannerImageJob = null
        update { it.copy(banner = null, overlay = if (it.overlay is Overlay.Notice) Overlay.None else it.overlay) }
    }

    /**
     * L'application passe devant ou derrière, le panneau s'ouvre ou se ferme : la notification
     * affichée suit, avec le temps qu'il lui reste (une sonnette dans l'application cachée ne doit
     * pas bloquer la file, invisible), ou disparaît si elle ne peut plus s'afficher nulle part.
     */
    private fun relocateNotification() {
        val current = state.value
        val shown = displayedBanner()
        if (shown == null) {
            pumpNotifications()
            return
        }
        val surface = notificationSurface(current)
        val inOverlay = current.overlay is Overlay.Notice
        val inPlace = when (surface) {
            NotificationSurface.Overlay -> inOverlay
            NotificationSurface.App, NotificationSurface.Panel -> !inOverlay
            null -> false
        }
        if (inPlace) {
            if (surface != NotificationSurface.Panel) loadBannerImage(shown)
            return
        }
        clearDisplayedNotification()
        if (!displayNotification(shown)) pumpNotifications()
    }

    /**
     * Ordre `dismiss` : retire la notification de `tag` [target], en attente ou affichée (dans
     * l'application ou par-dessus) ; la suivante en attente prend sa place.
     */
    private fun dismissNotification(target: String) {
        pendingNotifications.removeAll { it.banner.tag == target }
        if (displayedBanner()?.tag == target) clearDisplayedNotification()
        pumpNotifications()
    }

    /** Notre écran de veille s'affiche ou se ferme : la barre d'état s'efface pendant ce temps. */
    fun onDreamingChanged(dreaming: Boolean) {
        update { it.copy(dreaming = dreaming) }
    }

    // --- Images jointes -----------------------------------------------------------------------

    /**
     * Télécharge une image pendant que l'ordre est déjà affiché ; [apply] la pose une fois prête.
     * Un échec laisse simplement l'affichage sans image, sans message.
     */
    private fun loadImage(id: String, onFailure: () -> Unit = {}, apply: (ByteArray) -> Unit): Job? {
        val target = driver ?: return null
        return scope.launch {
            val bytes = try {
                target.image(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure()
                return@launch
            }
            apply(bytes)
        }
    }

    /**
     * Image du bandeau [banner] : posée seulement s'il est toujours affiché (par son identité).
     * Un échec le fait revenir à la carte texte. Rien à faire si elle est déjà là ou en cours.
     */
    private fun loadBannerImage(banner: Banner) {
        val id = banner.image ?: return
        if (banner.imageBytes != null || banner.imageFailed) return
        if (bannerImageFor == banner.id && bannerImageJob?.isActive == true) return
        bannerImageJob?.cancel()
        bannerImageFor = banner.id
        bannerImageJob = loadImage(
            id,
            onFailure = { updateDisplayedBanner(banner.id) { it.copy(imageFailed = true) } },
        ) { bytes -> updateDisplayedBanner(banner.id) { it.copy(imageBytes = bytes) } }
    }

    /** Modifie la notification affichée d'identité [id], où qu'elle soit ; sinon rien. */
    private fun updateDisplayedBanner(id: Long, transform: (Banner) -> Banner) {
        update { s ->
            val notice = s.overlay as? Overlay.Notice
            s.copy(
                banner = s.banner?.let { if (it.id == id) transform(it) else it },
                overlay = if (notice != null && notice.banner.id == id) Overlay.Notice(transform(notice.banner)) else s.overlay,
            )
        }
    }

    /** Image de la question [ask] : posée seulement si cette question est toujours affichée. */
    private fun loadQuestionImage(ask: String, id: String?) {
        questionImageJob?.cancel()
        questionImageJob = id?.let {
            loadImage(it) { bytes ->
                update { s -> s.copy(question = s.question?.takeIf { q -> q.ask == ask }?.copy(imageBytes = bytes) ?: s.question) }
            }
        }
    }

    private fun exit() {
        if (state.value.question != null) closeQuestion()
        cancelAutoReturn()
        if (state.value.overlay != Overlay.None) dismissOverlay(restoreSelection = true)
        // Déjà en arrière-plan : rien à quitter.
        update {
            it.copy(adjust = null, choice = null, confirm = null, exitRequested = it.uiVisible, foregroundRequested = false)
        }
    }

    // --- Superposition ------------------------------------------------------------------------

    /** Panneau par-dessus la vidéo, sur la page [index] ; il remplace la superposition courante. */
    private fun openPanel(index: Int, command: TvCommand.Show) {
        cancelAutoReturn()
        val current = state.value
        if (current.overlay !is Overlay.Panel) panelReturn = PanelReturn(current.currentPage?.id, current.focusedIndex)
        update {
            it.copy(
                pageIndex = index,
                focusedIndex = 0,
                focusZone = FocusZone.Tiles,
                adjust = null,
                choice = null,
                confirm = null,
                overlay = Overlay.Panel(command.page, command.durationSec),
                // Bandeau en superposition : il passe dans le panneau, avec son temps restant.
                banner = (it.overlay as? Overlay.Notice)?.banner ?: it.banner,
            )
        }
        if (command.durationSec > 0) restartOverlayTimer(command.durationSec * 1000L) else restartPanelIdleTimer()
        relocateNotification()
    }

    /**
     * Fermeture du panneau après une minute sans touche ; les 10 dernières secondes, une fine barre
     * l'annonce ([AppState.panelClosing]). Toute touche relance l'attente (et efface la barre).
     */
    private fun restartPanelIdleTimer() {
        overlayTimer?.cancel()
        if (state.value.panelClosing) update { it.copy(panelClosing = false) }
        // Un tableau des trains se lit sans toucher la télécommande : il reste plus longtemps.
        val idleMs = if (state.value.currentPage?.isBoard == true) BOARD_IDLE_MS else PANEL_IDLE_MS
        overlayTimer = scope.launch {
            delay(idleMs - PANEL_CLOSING_WARNING_MS)
            update { it.copy(panelClosing = true) }
            delay(PANEL_CLOSING_WARNING_MS)
            overlayTimer = null
            dismissOverlay(restoreSelection = true)
        }
    }

    /** Fermeture du panneau à la fin de la durée demandée par Jeedom (sans barre : pas d'inactivité). */
    private fun restartOverlayTimer(delayMs: Long) {
        overlayTimer?.cancel()
        if (state.value.panelClosing) update { it.copy(panelClosing = false) }
        overlayTimer = scope.launch {
            delay(delayMs)
            overlayTimer = null
            dismissOverlay(restoreSelection = true)
        }
    }

    /** Ferme la superposition ; [restoreSelection] rend à l'application sa page d'avant le panneau. */
    private fun dismissOverlay(restoreSelection: Boolean) {
        overlayTimer?.cancel()
        overlayTimer = null
        val closing = state.value.overlay
        if (closing is Overlay.Notice) {
            clearDisplayedNotification()
            pumpNotifications()
            return
        }
        val back = panelReturn
        panelReturn = null
        update { s ->
            val closed = if (s.overlay is Overlay.Panel) s.copy(adjust = null, choice = null, confirm = null) else s
            val restored = if (restoreSelection && back != null && s.overlay is Overlay.Panel) {
                // Par l'id : un nouveau layout a pu déplacer ou retirer la page entre-temps.
                val index = s.pages.indexOfFirst { it.id == back.pageId }.takeIf { it >= 0 }
                    ?: s.pageIndex.coerceIn(0, (s.pages.size - 1).coerceAtLeast(0))
                val count = s.pages.getOrNull(index)?.tiles?.size ?: 0
                closed.copy(
                    pageIndex = index,
                    focusedIndex = back.focusedIndex.coerceIn(0, (count - 1).coerceAtLeast(0)),
                    focusZone = FocusZone.Tiles,
                )
            } else {
                closed
            }
            restored.copy(overlay = Overlay.None, panelClosing = false)
        }
        // Panneau fermé, application cachée : sa notification passe en superposition.
        if (closing is Overlay.Panel) {
            relocateNotification()
            leaveHiddenPage()
        }
    }

    /**
     * Touches du panneau : comme sur l'écran des pages (flèches, OK, chiffres, CH+/CH-, réglage,
     * confirmation). Retour ferme le panneau, Menu ouvre l'application complète. Toute touche
     * annule la fermeture programmée par la durée de l'ordre ; reste la minute d'inactivité.
     */
    private fun onPanelCommand(command: RemoteCommand, current: AppState): Boolean {
        restartPanelIdleTimer()
        // Tableau des trains : aucune action ; Retour ferme, les touches de couleur restent actives.
        if (current.currentPage?.isBoard == true) {
            when (command) {
                RemoteCommand.Back -> dismissOverlay(restoreSelection = true)
                is RemoteCommand.Color -> return onPanelColor(command.key, current)
                else -> Unit
            }
            return true
        }
        if (command == RemoteCommand.Menu) {
            openFullApp()
            return true
        }
        if (command is RemoteCommand.Color) return onPanelColor(command.key, current)
        current.confirm?.let { return onConfirmCommand(command, it) }
        onChoiceCommandIfAny(command, current)?.let { return it }
        val adjust = current.adjust
        val adjustTile = current.adjustTile
        if (adjust != null && adjustTile != null) return onAdjustCommand(command, adjust, adjustTile)
        onTabsCommandIfAny(command, current)?.let { return it }
        if (command == RemoteCommand.Back) {
            dismissOverlay(restoreSelection = true)
            return true
        }
        onGridCommand(command, current)
        return true
    }

    /**
     * Touche de couleur sur le panneau : la page associée s'affiche ; si c'est déjà la page
     * affichée, le panneau se ferme. Elle passe avant un réglage ou une confirmation en cours.
     */
    private fun onPanelColor(key: ColorKey, current: AppState): Boolean {
        val index = current.pageIndexFor(key) ?: return false
        if (index == current.pageIndex) {
            dismissOverlay(restoreSelection = true)
        } else {
            update { it.copy(pageIndex = index, focusedIndex = 0, focusZone = FocusZone.Tiles, adjust = null, choice = null, confirm = null) }
            // Délai d'inactivité de la nouvelle page (plus long pour un tableau des trains).
            restartPanelIdleTimer()
        }
        return true
    }

    /**
     * Touche de couleur pendant qu'une autre application est affichée : le panneau s'ouvre sur
     * la page associée, comme un ordre `show` sans durée (fermeture après une minute sans touche).
     */
    private fun onHiddenColor(key: ColorKey, current: AppState): Boolean {
        val index = current.pageIndexFor(key) ?: return false
        val command = TvCommand.Show(id = null, page = current.pages[index].id, durationSec = 0)
        // Le panneau, même pour le tableau des trains (que `show` ouvre, lui, dans l'application).
        if (current.screen == Screen.Pages && overlayPermission.granted()) openPanel(index, command) else show(command)
        return true
    }

    /** Menu dans le panneau : l'application complète s'ouvre sur la même page (comme le `show` d'avant). */
    private fun openFullApp() {
        overlayTimer?.cancel()
        overlayTimer = null
        panelReturn = null
        update {
            it.copy(
                overlay = Overlay.None,
                panelClosing = false,
                screen = Screen.Pages,
                error = null,
                foregroundRequested = !it.uiVisible,
            )
        }
        relocateNotification()
    }

    // --- Questions de Jeedom -----------------------------------------------------------------

    /**
     * Question d'un bloc « Demander » : dans l'application si elle est affichée, sinon par-dessus
     * la vidéo (permission accordée), sinon en ouvrant l'activité. Elle remplace la précédente.
     */
    private fun ask(command: TvCommand.Ask) {
        // Déjà répondue sur une autre TV (ordre `ask_close` arrivé avant, même lot ou plus tôt) :
        // elle n'apparaît plus.
        if (command.ask in closedAsks) return
        cancelQuestionTimers()
        val current = state.value
        val hidden = !current.uiVisible
        val inOverlay = hidden && overlayPermission.granted()
        val opensActivity = hidden && !inOverlay
        questionOpenedActivity = opensActivity || (current.question != null && questionOpenedActivity)
        // Le panneau reste ouvert derrière la question, sans se fermer pendant qu'on y répond.
        if (current.overlay is Overlay.Panel) {
            overlayTimer?.cancel()
            overlayTimer = null
            panelPausedByQuestion = true
            if (current.panelClosing) update { it.copy(panelClosing = false) }
        }
        val timeout = command.timeoutSec.takeIf { it > 0 } ?: DEFAULT_QUESTION_TIMEOUT_S
        update {
            it.copy(
                question = Question(
                    ask = command.ask,
                    title = command.title,
                    message = command.message,
                    answers = command.answers,
                    timeoutSec = timeout,
                    deadlineMs = elapsedMs() + timeout * 1000L,
                    inOverlay = inOverlay,
                    image = command.image,
                    video = command.video,
                ),
                foregroundRequested = it.foregroundRequested || opensActivity,
            )
        }
        // La question s'affiche tout de suite ; la photo la rejoint dès qu'elle est téléchargée.
        loadQuestionImage(command.ask, command.image)
        startQuestionCountdown()
    }

    /**
     * À l'échéance, la question se ferme sans réponse. Le compte à rebours affiché se déduit de
     * [Question.deadlineMs] dans la vue : l'état ne change pas chaque seconde.
     */
    private fun startQuestionCountdown() {
        questionTicker?.cancel()
        val question = state.value.question ?: return
        questionTicker = scope.launch {
            delay((question.deadlineMs - elapsedMs()).coerceAtLeast(0))
            questionTicker = null
            val current = state.value.question
            if (current?.ask == question.ask && current.status == QuestionStatus.Choosing) closeQuestion()
        }
    }

    /**
     * ◀ ▶ (et ▲ ▼) changent de réponse, 1 à N la sélectionnent, seul OK envoie, Retour ferme
     * sans répondre. Les touches ne prolongent pas le délai. Un chiffre n'envoie jamais rien :
     * la question prend la main par-dessus la télé, et un numéro de chaîne tapé à ce moment
     * ne doit pas répondre « Ouvrir » au portail.
     */
    private fun onQuestionCommand(command: RemoteCommand, question: Question): Boolean {
        if (question.status != QuestionStatus.Choosing) {
            // Envoi en cours ou résultat affiché : seul Retour agit (fermeture immédiate).
            if (command == RemoteCommand.Back) closeQuestion()
            return true
        }
        val last = question.answers.lastIndex
        when (command) {
            RemoteCommand.Left, RemoteCommand.Up -> selectAnswer((question.selected - 1).coerceAtLeast(0))
            RemoteCommand.Right, RemoteCommand.Down -> selectAnswer((question.selected + 1).coerceAtMost(last))
            RemoteCommand.Ok -> sendAnswer(question, question.selected)
            is RemoteCommand.Digit -> if (command.value in 1..question.answers.size) selectAnswer(command.value - 1)
            RemoteCommand.Back -> closeQuestion()
            else -> Unit // CH+/CH-, Menu, couleurs : la question garde la main.
        }
        return true
    }

    private fun selectAnswer(index: Int) {
        update { it.copy(question = it.question?.copy(selected = index)) }
    }

    /** Envoie la réponse ; le résultat reste affiché ~2 s, puis la question se ferme. */
    private fun sendAnswer(question: Question, index: Int) {
        val target = driver ?: return
        val answer = question.answers[index]
        questionTicker?.cancel()
        questionTicker = null
        update { it.copy(question = question.copy(selected = index, status = QuestionStatus.Sending)) }
        // Envoi jamais annulé : une question remplacée entre-temps n'empêche pas la réponse d'arriver.
        scope.launch {
            val status = try {
                target.answer(question.ask, answer)
                QuestionStatus.Sent(answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JeedomException) {
                // 409 : question à plusieurs TV déjà répondue ailleurs (ton neutre, pas une erreur).
                if (e.httpCode == 409) QuestionStatus.AlreadyAnswered else QuestionStatus.Failed(
                    when (e.httpCode) {
                        404 -> "Question expirée"
                        422 -> "Réponse refusée"
                        else -> e.message ?: ANSWER_ERROR
                    }
                )
            } catch (e: Exception) {
                QuestionStatus.Failed(ANSWER_ERROR)
            }
            // Remplacée, fermée, ou fermée par `ask_close` pendant l'envoi : son message reste.
            val current = state.value.question
            if (current?.ask != question.ask || current.status != QuestionStatus.Sending) return@launch
            update { it.copy(question = it.question?.copy(status = status)) }
            questionResultTimer = scope.launch {
                delay(QUESTION_RESULT_MS)
                questionResultTimer = null
                if (state.value.question?.ask == question.ask) closeQuestion()
            }
        }
    }

    /**
     * Ordre `ask_close` (question à plusieurs TV répondue ailleurs) : la question de ce jeton se
     * ferme, après ~3 s de « Réponse donnée sur … » si la réponse est connue. Le lecteur vidéo est
     * rendu aussitôt (la vidéo ne joue qu'en cours de choix). Une autre question n'est pas touchée,
     * et ce jeton ne s'affichera plus s'il arrive ensuite.
     */
    private fun askClose(command: TvCommand.AskClose) {
        closedAsks.add(command.ask)
        if (closedAsks.size > MAX_CLOSED_ASKS) closedAsks.remove(closedAsks.first())
        val question = state.value.question?.takeIf { it.ask == command.ask } ?: return
        cancelQuestionTimers()
        val answer = command.answer
        if (answer == null) {
            closeQuestion()
            return
        }
        update { it.copy(question = question.copy(status = QuestionStatus.AnsweredElsewhere(answer, command.by))) }
        questionResultTimer = scope.launch {
            delay(ANSWERED_ELSEWHERE_MS)
            questionResultTimer = null
            if (state.value.question?.ask == command.ask) closeQuestion()
        }
    }

    /** Ferme la question ; ce qui était derrière (pages, panneau, réglage) revient tel quel. */
    private fun closeQuestion() {
        cancelQuestionTimers()
        val openedActivity = questionOpenedActivity
        questionOpenedActivity = false
        update {
            if (openedActivity) {
                // La question avait ouvert l'application : retour à l'application d'avant.
                it.copy(question = null, exitRequested = it.uiVisible, foregroundRequested = false)
            } else {
                it.copy(question = null)
            }
        }
        if (panelPausedByQuestion) {
            panelPausedByQuestion = false
            if (state.value.overlay is Overlay.Panel) restartPanelIdleTimer()
        }
    }

    private fun cancelQuestionTimers() {
        questionImageJob?.cancel()
        questionImageJob = null
        questionTicker?.cancel()
        questionTicker = null
        questionResultTimer?.cancel()
        questionResultTimer = null
    }

    // --- État signalé à Jeedom ---------------------------------------------------------------

    /** Toute modification du modèle passe par ici : l'état de la TV est renvoyé s'il a changé. */
    private fun update(transform: (AppState) -> AppState) {
        model.update(transform)
        val tvState = state.value.tvState
        if (tvState != lastScheduledState) {
            lastScheduledState = tvState
            scheduleStateSend()
        }
    }

    private fun resendState() {
        lastScheduledState = state.value.tvState
        scheduleStateSend()
    }

    /** Anti-rebond : seul l'état stable après [STATE_DEBOUNCE_MS] est envoyé. Erreurs ignorées. */
    private fun scheduleStateSend() {
        stateTimer?.cancel()
        stateTimer = scope.launch {
            delay(STATE_DEBOUNCE_MS)
            val target = driver ?: return@launch
            try {
                target.state(state.value.tvState.copy(appVersion = appVersion))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Jeedom l'aura au prochain changement ou à la prochaine reconnexion.
            }
        }
    }

    // --- Écran de configuration --------------------------------------------------------------

    /** Le formulaire Compose gère lui-même focus et saisie ; seul Retour nous intéresse. */
    private fun onSetupCommand(command: RemoteCommand, current: AppState): Boolean {
        if (driver == null || authBlocked || current.config == null) return false
        when (command) {
            RemoteCommand.Back -> update { it.copy(screen = Screen.Pages, error = null) }
            // Touche de couleur : la configuration en service reste, la page s'affiche.
            is RemoteCommand.Color -> {
                val index = current.pageIndexFor(command.key) ?: return false
                val page = current.pages[index]
                if (page.isBoard) rememberBoardReturn(page.id, ReturnTarget(Screen.Setup, null, 0, background = false))
                update { it.copy(screen = Screen.Pages, error = null, pageIndex = index, focusedIndex = 0, focusZone = FocusZone.Tiles) }
            }
            else -> return false
        }
        return true
    }

    // --- Écran des pages ---------------------------------------------------------------------

    private fun onPagesCommand(command: RemoteCommand, current: AppState): Boolean {
        if (current.currentPage?.isBoard == true) return onBoardCommand(command, current)
        // Application affichée : la page associée s'affiche directement, réglage ou confirmation abandonnés.
        if (command is RemoteCommand.Color) {
            val target = current.pageIndexFor(command.key) ?: return false
            val page = current.pages[target]
            if (page.isBoard) {
                rememberBoardReturn(page.id, ReturnTarget(Screen.Pages, current.currentPage?.id, current.focusedIndex, background = false))
            }
            update { it.copy(pageIndex = target, focusedIndex = 0, focusZone = FocusZone.Tiles, adjust = null, choice = null, confirm = null) }
            return true
        }
        current.confirm?.let { return onConfirmCommand(command, it) }
        onChoiceCommandIfAny(command, current)?.let { return it }
        val adjustTile = current.adjustTile
        val adjust = current.adjust
        if (adjust != null && adjustTile != null) return onAdjustCommand(command, adjust, adjustTile)
        if (adjust != null) update { it.copy(adjust = null, choice = null) } // Tuile disparue entre-temps.
        onTabsCommandIfAny(command, current)?.let { return it }
        return onGridCommand(command, current)
    }

    /**
     * Touches quand le focus est dans les onglets (null sinon), pour l'écran des pages comme pour
     * le panneau : ◀ ▶ changent de page aussitôt (en boucle, comme CH+ / CH-), ▼ ou OK
     * redescendent sur la première tuile, Retour revient aux tuiles. Sur une page sans tuile, le
     * focus reste dans les onglets et Retour garde son effet habituel (quitter, fermer le panneau).
     * Les chiffres agissent sur les tuiles comme depuis la grille ; CH+ / CH- ramènent aux tuiles.
     */
    private fun onTabsCommandIfAny(command: RemoteCommand, current: AppState): Boolean? {
        if (current.focusZone != FocusZone.Tabs) return null
        val hasTiles = current.currentPage?.tiles.orEmpty().isNotEmpty()
        when (command) {
            RemoteCommand.Left -> showPageInTabs(-1)
            RemoteCommand.Right -> showPageInTabs(1)
            RemoteCommand.Down, RemoteCommand.Ok -> if (hasTiles) {
                update { it.copy(focusZone = FocusZone.Tiles, focusedIndex = 0) }
            }
            RemoteCommand.Back -> {
                if (!hasTiles) return null
                update { it.copy(focusZone = FocusZone.Tiles) }
            }
            RemoteCommand.Up -> Unit // Déjà en haut.
            // Chiffres, CH+ / CH-, Menu : comme depuis les tuiles.
            else -> return null
        }
        return true
    }

    /** ◀ ▶ dans les onglets : page voisine (sans les pages cachées), en boucle ; le focus reste dans les onglets. */
    private fun showPageInTabs(step: Int) {
        val index = state.value.neighbourPageIndex(step) ?: return
        update { it.copy(pageIndex = index, focusedIndex = 0, focusZone = FocusZone.Tabs) }
    }

    private fun onGridCommand(command: RemoteCommand, current: AppState): Boolean {
        val tiles = current.currentPage?.tiles.orEmpty()
        val index = current.focusedIndex
        val columns = AppState.GRID_COLUMNS
        when (command) {
            // Haut depuis la première rangée (ou une page vide) : le focus monte dans les onglets.
            RemoteCommand.Up -> if (index < columns || tiles.isEmpty()) {
                update { it.copy(focusZone = FocusZone.Tabs) }
            } else {
                moveFocusTo(index - columns, tiles.size)
            }
            RemoteCommand.Down -> moveDown(index, tiles.size)
            RemoteCommand.Left -> moveFocusTo(index - 1, tiles.size)
            RemoteCommand.Right -> moveFocusTo(index + 1, tiles.size)
            RemoteCommand.ChannelUp -> showPage(1)
            RemoteCommand.ChannelDown -> showPage(-1)
            RemoteCommand.Ok -> tiles.getOrNull(index)?.let { activate(it) }
            is RemoteCommand.Digit -> {
                val target = command.value - 1
                if (command.value in 1..9 && target in tiles.indices) {
                    // Depuis les onglets aussi : le focus redescend sur la tuile N.
                    update { it.copy(focusedIndex = target, focusZone = FocusZone.Tiles) }
                    activate(tiles[target])
                }
            }
            RemoteCommand.Menu -> showSetup()
            RemoteCommand.Back -> return false // Comportement par défaut de l'activité : quitter l'app.
            is RemoteCommand.Color -> return false // Traitée par onPagesCommand et onPanelColor.
        }
        return true
    }

    /** Bas : ligne suivante ; sur une dernière ligne incomplète, la dernière tuile. */
    private fun moveDown(index: Int, size: Int) {
        val target = index + AppState.GRID_COLUMNS
        val lastRow = (size - 1) / AppState.GRID_COLUMNS
        when {
            target < size -> moveFocusTo(target, size)
            index / AppState.GRID_COLUMNS < lastRow -> moveFocusTo(size - 1, size)
        }
    }

    private fun moveFocusTo(target: Int, size: Int) {
        if (target in 0 until size) update { it.copy(focusedIndex = target) }
    }

    /** Page suivante / précédente (sans les pages cachées), en boucle ; la sélection revient sur la première tuile. */
    private fun showPage(step: Int) {
        val index = state.value.neighbourPageIndex(step) ?: return
        update { it.copy(pageIndex = index, focusedIndex = 0, focusZone = FocusZone.Tiles) }
    }

    /** Configuration : la boucle continue (les ordres de Jeedom restent reçus). */
    private fun showSetup() {
        update {
            it.copy(screen = Screen.Setup, error = null, adjust = null, choice = null, confirm = null, focusZone = FocusZone.Tiles)
        }
    }

    /** OK (ou chiffre) sur une tuile, selon son type. */
    private fun activate(tile: Tile) {
        when (tile.type) {
            TileType.Switch -> request(tile, TileAction.Toggle)
            TileType.Scene -> request(tile, TileAction.Run)
            TileType.Button -> request(tile, TileAction.Press)
            TileType.Select -> enterChoice(tile)
            TileType.Info -> Unit
            TileType.Shutter -> enterAdjust(tile, positional = tile.hasRange)
            TileType.Slider -> if (tile.hasRange) enterAdjust(tile, positional = true)
        }
    }

    // --- Mode de choix (tuile select) --------------------------------------------------------

    /** La sélection part du choix actuel (le premier si la valeur n'est pas dans la liste). */
    private fun enterChoice(tile: Tile) {
        if (tile.choices.isEmpty()) return
        update { it.copy(choice = ChoiceMode(tile.id, tile.choiceIndex.coerceAtLeast(0))) }
    }

    /** Commande du mode de choix s'il est actif ; null sinon. Tuile disparue : le mode est abandonné. */
    private fun onChoiceCommandIfAny(command: RemoteCommand, current: AppState): Boolean? {
        val mode = current.choice ?: return null
        val tile = current.choiceTile?.takeIf { it.choices.isNotEmpty() }
        if (tile == null) {
            update { it.copy(choice = null) }
            return null
        }
        val last = tile.choices.lastIndex
        when (command) {
            RemoteCommand.Left, RemoteCommand.Up -> selectChoice((mode.selected - 1).coerceAtLeast(0))
            RemoteCommand.Right, RemoteCommand.Down -> selectChoice((mode.selected + 1).coerceAtMost(last))
            RemoteCommand.Ok -> {
                update { it.copy(choice = null) }
                tile.choices.getOrNull(mode.selected)?.let { request(tile, TileAction.Set, choice = it.value) }
            }
            RemoteCommand.Back -> update { it.copy(choice = null) }
            RemoteCommand.Menu -> showSetup()
            else -> Unit // Chiffres, CH+/CH- : le mode de choix garde la main.
        }
        return true
    }

    private fun selectChoice(index: Int) {
        update { s -> s.choice?.let { s.copy(choice = it.copy(selected = index)) } ?: s }
    }

    // --- Mode réglage ------------------------------------------------------------------------

    private fun enterAdjust(tile: Tile, positional: Boolean) {
        val pending = if (positional) tile.clamp(tile.numericValue ?: tile.min!!) else null
        update { it.copy(adjust = Adjust(tile.id, pending)) }
    }

    private fun onAdjustCommand(command: RemoteCommand, adjust: Adjust, tile: Tile): Boolean {
        val pending = adjust.pending
        if (pending == null) {
            // Volet sans position : ordres directs.
            when (command) {
                RemoteCommand.Up, RemoteCommand.ChannelUp -> request(tile, TileAction.Up)
                RemoteCommand.Down, RemoteCommand.ChannelDown -> request(tile, TileAction.Down)
                RemoteCommand.Ok -> request(tile, TileAction.Stop)
                RemoteCommand.Back -> leaveAdjust()
                RemoteCommand.Menu -> showSetup()
                else -> Unit
            }
            return true
        }
        when (command) {
            RemoteCommand.Up -> setPending(tile, pending + tile.effectiveStep)
            RemoteCommand.Down -> setPending(tile, pending - tile.effectiveStep)
            RemoteCommand.Left -> setPending(tile, tile.min!!)
            RemoteCommand.Right -> setPending(tile, tile.max!!)
            RemoteCommand.Ok -> {
                leaveAdjust()
                request(tile, TileAction.Set, pending)
            }
            RemoteCommand.ChannelUp -> if (tile.type == TileType.Shutter) request(tile, TileAction.Up)
            RemoteCommand.ChannelDown -> if (tile.type == TileType.Shutter) request(tile, TileAction.Down)
            RemoteCommand.Back -> leaveAdjust()
            RemoteCommand.Menu -> showSetup()
            is RemoteCommand.Digit, is RemoteCommand.Color -> Unit
        }
        return true
    }

    private fun setPending(tile: Tile, value: Double) {
        val clamped = tile.clamp(value)
        update { s -> s.adjust?.let { s.copy(adjust = it.copy(pending = clamped)) } ?: s }
    }

    private fun leaveAdjust() {
        update { it.copy(adjust = null, choice = null) }
    }

    // --- Confirmation et ordres --------------------------------------------------------------

    private fun onConfirmCommand(command: RemoteCommand, pending: PendingAction): Boolean {
        when (command) {
            RemoteCommand.Ok -> {
                update { it.copy(confirm = null) }
                state.value.findTile(pending.tileId)?.let { execute(it, pending.action, pending.value, pending.choice) }
            }
            RemoteCommand.Back -> update { it.copy(confirm = null) }
            RemoteCommand.Menu -> showSetup()
            else -> Unit // La boîte de confirmation garde la main.
        }
        return true
    }

    /** Ordre demandé par l'utilisateur : confirmation d'abord si la tuile l'exige. */
    private fun request(tile: Tile, action: TileAction, value: Double? = null, choice: String? = null) {
        if (tile.confirm) {
            update {
                it.copy(confirm = PendingAction(tile.id, action, value, describe(tile, action, value, choice), choice))
            }
        } else {
            execute(tile, action, value, choice)
        }
    }

    private fun execute(tile: Tile, action: TileAction, value: Double?, choice: String? = null) {
        val target = driver ?: return
        when {
            action == TileAction.Toggle -> {
                val optimistic = if (state.value.findTile(tile.id)?.isOn == true) "0" else "1"
                sendOptimistic(target, tile, action, optimistic, choice = null)
            }
            choice != null -> sendOptimistic(target, tile, action, choice, choice)
            else -> {
                if (action == TileAction.Run || action == TileAction.Press) flash(tile.id)
                send(target, tile, action, value, choice = null, sentStamp = stampOf(tile.id), rollback = null)
            }
        }
    }

    /**
     * Ordre avec mise à jour optimiste (bascule d'un interrupteur, choix d'une liste) : la valeur
     * [optimistic] s'affiche tout de suite, puis la réponse (ou `changes`) la corrige. En cas
     * d'erreur, l'ancienne valeur revient.
     */
    private fun sendOptimistic(target: JeedomDriver, tile: Tile, action: TileAction, optimistic: String, choice: String?) {
        val old = state.value.findTile(tile.id)?.value
        val sentStamp = stamp(tile.id)
        update { it.withChanges(listOf(TileChange(tile.id, optimistic))) }
        send(target, tile, action, value = null, choice = choice, sentStamp = sentStamp, rollback = TileChange(tile.id, old))
    }

    /**
     * Envoie l'ordre. La valeur de la réponse, comme le retour en arrière [rollback] après une
     * erreur, ne s'applique que si la tuile n'a pas reçu de valeur plus récente depuis l'envoi
     * ([sentStamp]) : un `changes` ou un second appui arrivé entre-temps a raison.
     */
    private fun send(
        target: JeedomDriver,
        tile: Tile,
        action: TileAction,
        value: Double?,
        choice: String?,
        sentStamp: Long,
        rollback: TileChange?,
    ) {
        scope.launch {
            try {
                val newValue = target.exec(tile.id, action, value, choice)
                if (newValue != null && stampOf(tile.id) == sentStamp) applyChanges(listOf(TileChange(tile.id, newValue)))
                // Jeedom a accepté l'ordre : coche brève (scène et bouton ont déjà leur éclair).
                if (action != TileAction.Run && action != TileAction.Press) markConfirmed(tile.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (rollback != null && stampOf(tile.id) == sentStamp) applyChanges(listOf(rollback))
                showNotice(errorText(e))
                // Tuile inconnue : les pages ont changé dans Jeedom sans qu'on le sache encore.
                if ((e as? JeedomException)?.httpCode == 404) reloadLayoutSoon()
            }
        }
    }

    private fun showNotice(message: String) {
        update { it.copy(notice = message) }
        noticeTimer?.cancel()
        noticeTimer = scope.launch {
            delay(NOTICE_DURATION_MS)
            update { it.copy(notice = null) }
        }
    }

    private fun markConfirmed(tileId: String) {
        update { it.copy(confirmedTileId = tileId) }
        confirmTimer?.cancel()
        confirmTimer = scope.launch {
            delay(CONFIRM_DURATION_MS)
            update { s -> if (s.confirmedTileId == tileId) s.copy(confirmedTileId = null) else s }
        }
    }

    private fun flash(tileId: String) {
        update { it.copy(flashTileId = tileId) }
        flashTimer?.cancel()
        flashTimer = scope.launch {
            delay(FLASH_DURATION_MS)
            update { it.copy(flashTileId = null) }
        }
    }

    private fun errorText(e: Exception): String =
        (e as? JeedomException)?.message ?: "Commande impossible"

    private fun describe(tile: Tile, action: TileAction, value: Double?, choice: String?): String = when (action) {
        TileAction.On -> "Allumer « ${tile.name} »"
        TileAction.Off -> "Éteindre « ${tile.name} »"
        TileAction.Toggle -> if (tile.isOn) "Éteindre « ${tile.name} »" else "Allumer « ${tile.name} »"
        TileAction.Up -> "Monter « ${tile.name} »"
        TileAction.Down -> "Descendre « ${tile.name} »"
        TileAction.Stop -> "Arrêter « ${tile.name} »"
        TileAction.Set -> if (choice != null) {
            val label = tile.choices.firstOrNull { it.value == choice }?.label ?: choice
            "Régler « ${tile.name} » sur « $label »"
        } else {
            "Régler « ${tile.name} » sur ${formatValue(value ?: 0.0, tile.unit)}"
        }
        TileAction.Run -> "Lancer « ${tile.name} »"
        TileAction.Press -> "Activer « ${tile.name} »"
    }

    private companion object {
        const val GENERIC_ERROR = "Connexion à Jeedom impossible"
        const val NOTICE_DURATION_MS = 4_000L
        const val FLASH_DURATION_MS = 600L
        const val BANNER_DURATION_MS = 8_000L
        const val STATE_DEBOUNCE_MS = 300L
        const val MAX_HANDLED_IDS = 100

        /** Une attente longue dure 25 s au plus côté plugin : au-delà de 35 s, la connexion est morte. */
        const val STALE_CALL_MS = 35_000L

        /** Question sans délai fourni par le plugin. */
        const val DEFAULT_QUESTION_TIMEOUT_S = 60

        /** Durée d'affichage du résultat d'une réponse. */
        const val QUESTION_RESULT_MS = 2_000L

        /** Affichage de « Réponse donnée sur … » avant la fermeture (ordre `ask_close`). */
        const val ANSWERED_ELSEWHERE_MS = 3_000L

        /** Jetons de questions fermées retenus (une question en retard ne s'affiche plus). */
        const val MAX_CLOSED_ASKS = 50

        const val ANSWER_ERROR = "Réponse impossible"

        /** Panneau sans durée, ou touché par l'utilisateur : fermé après une minute sans touche. */
        const val PANEL_IDLE_MS = 60_000L

        /** Application derrière depuis ce délai : une page cachée affichée est quittée. */
        const val HIDDEN_PAGE_DELAY_MS = 2_000L

        /** Panneau sur un tableau des trains : on le lit sans toucher la télécommande. */
        const val BOARD_IDLE_MS = 5 * 60_000L

        /** Barre d'avertissement avant la fermeture du panneau pour inactivité. */
        const val PANEL_CLOSING_WARNING_MS = 10_000L

        /** Notifications en attente au plus (la plus ancienne est abandonnée au-delà). */
        const val MAX_PENDING_NOTIFICATIONS = 3

        /** Erreurs consécutives de `changes` au-delà desquelles Jeedom passe pour injoignable. */
        const val UNREACHABLE_FAILURES = 3

        /** Sans aucune réponse de `changes` pendant ce temps, Jeedom passe pour injoignable. */
        const val UNREACHABLE_AFTER_MS = 60_000L

        /** Attente au-delà de laquelle une notification en file est périmée (au moins sa durée). */
        const val PENDING_STALE_MIN_MS = 30_000L

        /** Surfaces où une notification peut s'afficher. */
        private enum class NotificationSurface { App, Panel, Overlay }

        /** Notification en attente, et son heure d'arrivée ([elapsedMs]) pour l'expiration. */
        private data class PendingNotification(val banner: Banner, val receivedAt: Long)

        /** Coche de confirmation d'un ordre sur une tuile. */
        const val CONFIRM_DURATION_MS = 1_000L
    }

    private data class PanelReturn(val pageId: String?, val focusedIndex: Int)

    /** Retour d'un tableau des trains : [back] vaut tant que la page [pageId] est affichée. */
    private data class BoardReturn(val pageId: String, val back: ReturnTarget)

    /** [background] : l'application était en arrière-plan avant l'affichage temporaire. */
    private data class ReturnTarget(
        val screen: Screen,
        val pageId: String?,
        val focusedIndex: Int,
        val background: Boolean,
    )
}

// --- Fonctions pures sur l'état (testables sans contrôleur) -------------------------------------

/** Pause avant le nouvel essai n° [failures] + 1 : 3 s, puis le double à chaque échec, 30 s au plus. */
internal fun retryDelayMs(failures: Int): Long =
    (3_000L shl failures.coerceIn(0, 4)).coerceAtMost(30_000L)

/** Pas du réglage : celui du plugin, sinon un dixième de la plage. */
internal val Tile.effectiveStep: Double
    get() = step?.takeIf { it > 0 } ?: ((max ?: 100.0) - (min ?: 0.0)).div(10).takeIf { it > 0 } ?: 1.0

/** Ramène [value] dans [min, max] et sur un multiple du pas (évite 20.499999). */
internal fun Tile.clamp(value: Double): Double {
    val low = min ?: return value
    val high = max ?: return value
    val step = effectiveStep
    val snapped = low + ((value - low) / step).roundToLong() * step
    val rounded = Math.round(snapped * 1e6) / 1e6
    return rounded.coerceIn(low, high)
}

/** 40.0 → "40 %", 20.5 → "20,5 °C". */
fun formatValue(value: Double, unit: String): String {
    val text = if (abs(value - Math.rint(value)) < 1e-9) {
        value.roundToLong().toString()
    } else {
        value.toString().replace('.', ',')
    }
    return if (unit.isBlank()) text else "$text $unit"
}

/**
 * Page voisine de [index] qui n'est pas cachée (elle-même, sinon la suivante, sinon la précédente) :
 * au démarrage ou après la disparition d'une page, une page cachée ne s'affiche pas d'elle-même.
 * Toutes cachées : [AppState.NO_PAGE] (aucune page plutôt que le tableau des trains).
 */
private fun neighbourVisible(pages: List<Page>, index: Int): Int {
    if (pages.getOrNull(index)?.hidden != true) return index
    return (index until pages.size).firstOrNull { !pages[it].hidden }
        ?: (index downTo 0).firstOrNull { !pages[it].hidden }
        ?: AppState.NO_PAGE
}

/** Page cachée affichée : la page d'accueil à sa place (aucune s'il n'y en a pas) ; sinon inchangé. */
private fun AppState.awayFromHiddenPage(): AppState {
    if (currentPage?.hidden != true) return this
    return copy(pageIndex = firstVisiblePageIndex ?: AppState.NO_PAGE, focusedIndex = 0, focusZone = FocusZone.Tiles)
}

/** Tableaux des trains reçus par `changes.boards` : remplacés tels quels, seulement sur les pages `board`. */
internal fun AppState.withBoards(boards: Map<String, Board>): AppState {
    if (pages.none { it.isBoard && it.id in boards }) return this
    return copy(pages = pages.map { page ->
        val board = boards[page.id]
        if (page.isBoard && board != null) page.copy(board = board) else page
    })
}

/** Nouvelles valeurs de tuiles, dans toutes les pages où elles apparaissent. */
internal fun AppState.withChanges(changes: List<TileChange>): AppState {
    if (changes.isEmpty()) return this
    val values = changes.associate { it.tile to it.value }
    return copy(
        // Les éléments du bandeau arrivent dans la même liste que les tuiles, par leur id.
        header = if (header.none { it.id in values }) {
            header
        } else {
            header.map { item -> if (item.id in values) item.copy(value = values[item.id]) else item }
        },
        pages = pages.map { page ->
            if (page.tiles.none { it.id in values }) {
                page
            } else {
                page.copy(tiles = page.tiles.map { tile ->
                    if (tile.id in values) tile.copy(value = values[tile.id]) else tile
                })
            }
        }
    )
}

/**
 * Nouveau layout : la page affichée est retrouvée par son id, la sélection suit la tuile
 * sélectionnée (par son id) si elle est encore sur la page, et un réglage ou une confirmation
 * sur une tuile disparue est abandonné. Page disparue : page voisine, sélection sur la première tuile.
 */
internal fun AppState.withLayout(layout: Layout): AppState {
    val currentPageId = currentPage?.id
    val samePage = layout.pages.indexOfFirst { it.id == currentPageId }.takeIf { it >= 0 }
    val newPageIndex = samePage ?: neighbourVisible(layout.pages, pageIndex.coerceIn(0, (layout.pages.size - 1).coerceAtLeast(0)))
    val newTiles = layout.pages.getOrNull(newPageIndex)?.tiles.orEmpty()
    val focusedId = focusedTile?.id
    val newFocus = when {
        samePage == null -> 0
        else -> newTiles.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 }
            ?: focusedIndex.coerceIn(0, (newTiles.size - 1).coerceAtLeast(0))
    }
    val ids = layout.pages.flatMap { page -> page.tiles.map { it.id } }.toSet()
    return copy(
        revision = layout.revision,
        pages = layout.pages,
        colorKeys = layout.keys,
        header = layout.header,
        status = layout.status,
        pageIndex = newPageIndex,
        focusedIndex = newFocus,
        adjust = adjust?.takeIf { it.tileId in ids },
        // Mode de choix gardé si la tuile a encore des choix ; sélection ramenée dans la liste.
        choice = choice?.let { c ->
            val count = layout.pages.asSequence().flatMap { it.tiles }.firstOrNull { it.id == c.tileId }?.choices?.size ?: 0
            if (count == 0) null else c.copy(selected = c.selected.coerceIn(0, count - 1))
        },
        confirm = confirm?.takeIf { it.tileId in ids },
    )
}
