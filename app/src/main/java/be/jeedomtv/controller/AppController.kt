package be.jeedomtv.controller

import be.jeedomtv.model.Adjust
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Banner
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.Overlay
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

    /** Efface le message temporaire. */
    private var noticeTimer: Job? = null

    /** Efface le retour visuel d'une tuile. */
    private var flashTimer: Job? = null

    /** Efface le bandeau `notify`. */
    private var bannerTimer: Job? = null

    /** Envoi différé (anti-rebond) de l'état de la TV à Jeedom. */
    private var stateTimer: Job? = null

    /** Dernier état programmé pour l'envoi : un état identique n'est pas renvoyé. */
    private var lastScheduledState: TvState? = null

    /** Ids des ordres déjà traités (les plus récents seulement). */
    private val handledCommandIds = LinkedHashSet<Long>()

    /** Écran à retrouver après un `show` temporaire, et le minuteur qui l'y ramène. */
    private var returnTarget: ReturnTarget? = null
    private var returnTimer: Job? = null

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
        // L'application complète est affichée : la superposition n'a plus lieu d'être.
        if (visible && state.value.overlay != Overlay.None) dismissOverlay(restoreSelection = false)
        // Une question suit l'écran : dans l'application si elle est affichée, sinon par-dessus.
        val question = state.value.question
        if (question != null) {
            val inOverlay = !visible && overlayPermission.granted()
            if (question.inOverlay != inOverlay && (visible || inOverlay)) {
                update { it.copy(question = it.question?.copy(inOverlay = inOverlay)) }
            }
        }
    }

    /** La TV allume ou éteint son écran (sortie ou entrée en veille). */
    fun onScreenChanged(on: Boolean) {
        update { it.copy(screenOn = on) }
        // Au réveil, la connexion d'avant la veille est probablement morte.
        if (on) onNetworkMaybeRestored()
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
            it.copy(screen = Screen.Loading, config = config, error = null, adjust = null, confirm = null)
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
            driver = newDriver
            authBlocked = false
            update {
                it.copy(tvName = ping.tvName, offline = false, error = null).withLayout(layout)
                    .copy(screen = Screen.Pages)
            }
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
                driver = newDriver
                authBlocked = false
                update { it.copy(screen = Screen.Pages, offline = true, error = null) }
                startChangesLoop(reloadFirst = false)
            }
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
        update { it.copy(screen = Screen.Setup, error = message, adjust = null, confirm = null) }
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
    }

    private fun stopChangesLoop() {
        changesJob?.cancel()
        changesJob = null
    }

    /**
     * Attente longue des changements : applique les valeurs, recharge le layout si la révision
     * change, exécute les ordres de Jeedom. Après une erreur : « hors ligne », pause,
     * rechargement du layout, reprise sans curseur. L'écran ne repasse jamais par le chargement.
     */
    private suspend fun runChanges(target: JeedomDriver, reloadFirst: Boolean) {
        var since: String? = null
        var reload = reloadFirst
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
                val result = target.changes(since)
                currentCoroutineContext().ensureActive()
                since = result.since
                update { it.copy(offline = false).withChanges(result.changes) }
                val revision = result.revision
                if (revision != null && revision != state.value.revision) {
                    applyLayout(target.layout())
                }
                // Démarrage ou reconnexion : Jeedom ne connaît peut-être pas encore notre état.
                if (restarted) resendState()
                result.commands.forEach { applyCommand(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is AuthenticationException && state.value.uiVisible) {
                    // Clé régénérée ou équipement désactivé dans Jeedom : il faut ressaisir la clé.
                    changesJob = null
                    authBlocked = true
                    showSetupError(e.message ?: GENERIC_ERROR)
                    return
                }
                update { it.copy(offline = true) }
                delay(RETRY_DELAY_MS)
                reload = true
                since = null
            }
        }
    }

    private suspend fun applyLayout(layout: Layout) {
        currentCoroutineContext().ensureActive()
        update { it.withLayout(layout) }
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
            is TvCommand.Exit -> exit()
            is TvCommand.Ask -> ask(command)
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
        // Application cachée (vidéo en cours) : panneau par-dessus, la vidéo reste au premier plan.
        if (!current.uiVisible && overlayPermission.granted()) {
            openPanel(index, command)
            return
        }
        // Un affichage temporaire déjà en cours garde l'écran d'origine.
        val previous = returnTarget ?: ReturnTarget(
            screen = current.screen,
            pageId = current.currentPage?.id,
            focusedIndex = current.focusedIndex,
            background = !current.uiVisible,
        )
        cancelAutoReturn()
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
                adjust = null,
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
                    val index = s.pages.indexOfFirst { it.id == previous.pageId }.takeIf { it >= 0 } ?: s.pageIndex
                    val count = s.pages.getOrNull(index)?.tiles?.size ?: 0
                    s.copy(
                        screen = Screen.Pages,
                        pageIndex = index,
                        focusedIndex = previous.focusedIndex.coerceIn(0, (count - 1).coerceAtLeast(0)),
                    )
                }
                Screen.Setup -> s.copy(screen = Screen.Setup)
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
     * Bandeau de quelques secondes, seulement si quelqu'un regarde l'application, ou va la
     * regarder : un `show` qui la ramène au premier plan peut précéder le message dans la même réponse.
     */
    private fun notify(command: TvCommand.Notify) {
        val current = state.value
        val banner = Banner(command.title, command.message)
        if (!current.uiVisible && !current.foregroundRequested) {
            when {
                // Le panneau affiche le bandeau en son sein.
                current.overlay is Overlay.Panel -> Unit
                // Bandeau en superposition, sans focus : la vidéo ne remarque rien.
                overlayPermission.granted() -> {
                    showNoticeOverlay(banner)
                    return
                }
                else -> return
            }
        }
        update { it.copy(banner = banner) }
        bannerTimer?.cancel()
        bannerTimer = scope.launch {
            delay(BANNER_DURATION_MS)
            update { it.copy(banner = null) }
        }
    }

    private fun exit() {
        if (state.value.question != null) closeQuestion()
        cancelAutoReturn()
        if (state.value.overlay != Overlay.None) dismissOverlay(restoreSelection = true)
        // Déjà en arrière-plan : rien à quitter.
        update {
            it.copy(adjust = null, confirm = null, exitRequested = it.uiVisible, foregroundRequested = false)
        }
    }

    // --- Superposition ------------------------------------------------------------------------

    /** Bandeau par-dessus la vidéo ; un nouvel ordre remplace la superposition courante. */
    private fun showNoticeOverlay(banner: Banner) {
        if (state.value.overlay is Overlay.Panel) dismissOverlay(restoreSelection = true)
        update { it.copy(overlay = Overlay.Notice(banner)) }
        restartOverlayTimer(BANNER_DURATION_MS)
    }

    /** Panneau par-dessus la vidéo, sur la page [index] ; il remplace la superposition courante. */
    private fun openPanel(index: Int, command: TvCommand.Show) {
        cancelAutoReturn()
        val current = state.value
        if (current.overlay !is Overlay.Panel) panelReturn = PanelReturn(current.pageIndex, current.focusedIndex)
        update {
            it.copy(
                pageIndex = index,
                focusedIndex = 0,
                adjust = null,
                confirm = null,
                overlay = Overlay.Panel(command.page, command.durationSec),
            )
        }
        restartOverlayTimer(if (command.durationSec > 0) command.durationSec * 1000L else PANEL_IDLE_MS)
    }

    private fun restartOverlayTimer(delayMs: Long) {
        overlayTimer?.cancel()
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
        val back = panelReturn
        panelReturn = null
        update { s ->
            val closed = if (s.overlay is Overlay.Panel) s.copy(adjust = null, confirm = null) else s
            val restored = if (restoreSelection && back != null && s.overlay is Overlay.Panel) {
                val index = back.pageIndex.coerceIn(0, (s.pages.size - 1).coerceAtLeast(0))
                val count = s.pages.getOrNull(index)?.tiles?.size ?: 0
                closed.copy(pageIndex = index, focusedIndex = back.focusedIndex.coerceIn(0, (count - 1).coerceAtLeast(0)))
            } else {
                closed
            }
            restored.copy(overlay = Overlay.None)
        }
    }

    /**
     * Touches du panneau : comme sur l'écran des pages (flèches, OK, chiffres, CH+/CH-, réglage,
     * confirmation). Retour ferme le panneau, Menu ouvre l'application complète. Toute touche
     * annule la fermeture programmée par la durée de l'ordre ; reste la minute d'inactivité.
     */
    private fun onPanelCommand(command: RemoteCommand, current: AppState): Boolean {
        restartOverlayTimer(PANEL_IDLE_MS)
        if (command == RemoteCommand.Menu) {
            openFullApp()
            return true
        }
        current.confirm?.let { return onConfirmCommand(command, it) }
        val adjust = current.adjust
        val adjustTile = current.adjustTile
        if (adjust != null && adjustTile != null) return onAdjustCommand(command, adjust, adjustTile)
        if (command == RemoteCommand.Back) {
            dismissOverlay(restoreSelection = true)
            return true
        }
        onGridCommand(command, current)
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
                screen = Screen.Pages,
                error = null,
                foregroundRequested = !it.uiVisible,
            )
        }
    }

    // --- Questions de Jeedom -----------------------------------------------------------------

    /**
     * Question d'un bloc « Demander » : dans l'application si elle est affichée, sinon par-dessus
     * la vidéo (permission accordée), sinon en ouvrant l'activité. Elle remplace la précédente.
     */
    private fun ask(command: TvCommand.Ask) {
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
                    remainingSec = timeout,
                    inOverlay = inOverlay,
                ),
                foregroundRequested = it.foregroundRequested || opensActivity,
            )
        }
        startQuestionCountdown()
    }

    /** Une seconde de moins à chaque tick ; à zéro, la question se ferme sans réponse. */
    private fun startQuestionCountdown() {
        questionTicker?.cancel()
        questionTicker = scope.launch {
            while (true) {
                delay(1_000)
                val question = state.value.question ?: return@launch
                if (question.status != QuestionStatus.Choosing) return@launch
                val remaining = question.remainingSec - 1
                if (remaining <= 0) {
                    questionTicker = null
                    closeQuestion()
                    return@launch
                }
                update { it.copy(question = it.question?.copy(remainingSec = remaining)) }
            }
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
            else -> Unit // CH+/CH-, Menu : la question garde la main.
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
                QuestionStatus.Failed(
                    when (e.httpCode) {
                        404 -> "Question expirée"
                        422 -> "Réponse refusée"
                        else -> e.message ?: ANSWER_ERROR
                    }
                )
            } catch (e: Exception) {
                QuestionStatus.Failed(ANSWER_ERROR)
            }
            if (state.value.question?.ask != question.ask) return@launch // Remplacée ou fermée.
            update { it.copy(question = it.question?.copy(status = status)) }
            questionResultTimer = scope.launch {
                delay(QUESTION_RESULT_MS)
                questionResultTimer = null
                if (state.value.question?.ask == question.ask) closeQuestion()
            }
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
            if (state.value.overlay is Overlay.Panel) restartOverlayTimer(PANEL_IDLE_MS)
        }
    }

    private fun cancelQuestionTimers() {
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
        if (command != RemoteCommand.Back || driver == null || authBlocked || current.config == null) return false
        update { it.copy(screen = Screen.Pages, error = null) }
        return true
    }

    // --- Écran des pages ---------------------------------------------------------------------

    private fun onPagesCommand(command: RemoteCommand, current: AppState): Boolean {
        current.confirm?.let { return onConfirmCommand(command, it) }
        val adjustTile = current.adjustTile
        val adjust = current.adjust
        if (adjust != null && adjustTile != null) return onAdjustCommand(command, adjust, adjustTile)
        if (adjust != null) update { it.copy(adjust = null) } // Tuile disparue entre-temps.
        return onGridCommand(command, current)
    }

    private fun onGridCommand(command: RemoteCommand, current: AppState): Boolean {
        val tiles = current.currentPage?.tiles.orEmpty()
        val index = current.focusedIndex
        val columns = AppState.GRID_COLUMNS
        when (command) {
            // Haut depuis la première ligne : sans effet (les onglets se changent par CH+ / CH-).
            RemoteCommand.Up -> moveFocusTo(index - columns, tiles.size)
            RemoteCommand.Down -> moveDown(index, tiles.size)
            RemoteCommand.Left -> moveFocusTo(index - 1, tiles.size)
            RemoteCommand.Right -> moveFocusTo(index + 1, tiles.size)
            RemoteCommand.ChannelUp -> showPage(current.pageIndex + 1)
            RemoteCommand.ChannelDown -> showPage(current.pageIndex - 1)
            RemoteCommand.Ok -> tiles.getOrNull(index)?.let { activate(it) }
            is RemoteCommand.Digit -> {
                val target = command.value - 1
                if (command.value in 1..9 && target in tiles.indices) {
                    update { it.copy(focusedIndex = target) }
                    activate(tiles[target])
                }
            }
            RemoteCommand.Menu -> showSetup()
            RemoteCommand.Back -> return false // Comportement par défaut de l'activité : quitter l'app.
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

    /** Page suivante / précédente, en boucle ; la sélection revient sur la première tuile. */
    private fun showPage(index: Int) {
        val count = state.value.pages.size
        if (count == 0) return
        update { it.copy(pageIndex = Math.floorMod(index, count), focusedIndex = 0) }
    }

    /** Configuration : la boucle continue (les ordres de Jeedom restent reçus). */
    private fun showSetup() {
        update { it.copy(screen = Screen.Setup, error = null, adjust = null, confirm = null) }
    }

    /** OK (ou chiffre) sur une tuile, selon son type. */
    private fun activate(tile: Tile) {
        when (tile.type) {
            TileType.Switch -> request(tile, TileAction.Toggle)
            TileType.Scene -> request(tile, TileAction.Run)
            TileType.Info -> Unit
            TileType.Shutter -> enterAdjust(tile, positional = tile.hasRange)
            TileType.Slider -> if (tile.hasRange) enterAdjust(tile, positional = true)
        }
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
            is RemoteCommand.Digit -> Unit
        }
        return true
    }

    private fun setPending(tile: Tile, value: Double) {
        val clamped = tile.clamp(value)
        update { s -> s.adjust?.let { s.copy(adjust = it.copy(pending = clamped)) } ?: s }
    }

    private fun leaveAdjust() {
        update { it.copy(adjust = null) }
    }

    // --- Confirmation et ordres --------------------------------------------------------------

    private fun onConfirmCommand(command: RemoteCommand, pending: PendingAction): Boolean {
        when (command) {
            RemoteCommand.Ok -> {
                update { it.copy(confirm = null) }
                state.value.findTile(pending.tileId)?.let { execute(it, pending.action, pending.value) }
            }
            RemoteCommand.Back -> update { it.copy(confirm = null) }
            RemoteCommand.Menu -> showSetup()
            else -> Unit // La boîte de confirmation garde la main.
        }
        return true
    }

    /** Ordre demandé par l'utilisateur : confirmation d'abord si la tuile l'exige. */
    private fun request(tile: Tile, action: TileAction, value: Double? = null) {
        if (tile.confirm) {
            update {
                it.copy(confirm = PendingAction(tile.id, action, value, describe(tile, action, value)))
            }
        } else {
            execute(tile, action, value)
        }
    }

    private fun execute(tile: Tile, action: TileAction, value: Double?) {
        val target = driver ?: return
        if (action == TileAction.Toggle) {
            toggle(target, tile)
            return
        }
        if (action == TileAction.Run) flash(tile.id)
        scope.launch {
            try {
                val newValue = target.exec(tile.id, action, value)
                if (newValue != null) update { it.withChanges(listOf(TileChange(tile.id, newValue))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotice(errorText(e))
            }
        }
    }

    /**
     * Bascule avec mise à jour optimiste : la valeur change tout de suite, puis la réponse
     * (ou `changes`) la corrige. En cas d'erreur, l'ancienne valeur revient.
     */
    private fun toggle(target: JeedomDriver, tile: Tile) {
        val old = state.value.findTile(tile.id)?.value
        val optimistic = if (state.value.findTile(tile.id)?.isOn == true) "0" else "1"
        update { it.withChanges(listOf(TileChange(tile.id, optimistic))) }
        scope.launch {
            try {
                val newValue = target.exec(tile.id, TileAction.Toggle, null)
                if (newValue != null) update { it.withChanges(listOf(TileChange(tile.id, newValue))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Retour en arrière, sauf si une valeur plus récente est arrivée entre-temps.
                update {
                    if (it.findTile(tile.id)?.value == optimistic) {
                        it.withChanges(listOf(TileChange(tile.id, old)))
                    } else {
                        it
                    }
                }
                showNotice(errorText(e))
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

    private fun describe(tile: Tile, action: TileAction, value: Double?): String = when (action) {
        TileAction.On -> "Allumer « ${tile.name} »"
        TileAction.Off -> "Éteindre « ${tile.name} »"
        TileAction.Toggle -> if (tile.isOn) "Éteindre « ${tile.name} »" else "Allumer « ${tile.name} »"
        TileAction.Up -> "Monter « ${tile.name} »"
        TileAction.Down -> "Descendre « ${tile.name} »"
        TileAction.Stop -> "Arrêter « ${tile.name} »"
        TileAction.Set -> "Régler « ${tile.name} » sur ${formatValue(value ?: 0.0, tile.unit)}"
        TileAction.Run -> "Lancer « ${tile.name} »"
    }

    private companion object {
        const val GENERIC_ERROR = "Connexion à Jeedom impossible"
        const val RETRY_DELAY_MS = 3_000L
        const val NOTICE_DURATION_MS = 4_000L
        const val FLASH_DURATION_MS = 600L
        const val BANNER_DURATION_MS = 8_000L
        const val STATE_DEBOUNCE_MS = 300L
        const val MAX_HANDLED_IDS = 100

        /** Question sans délai fourni par le plugin. */
        const val DEFAULT_QUESTION_TIMEOUT_S = 60

        /** Durée d'affichage du résultat d'une réponse. */
        const val QUESTION_RESULT_MS = 2_000L

        const val ANSWER_ERROR = "Réponse impossible"

        /** Panneau sans durée, ou touché par l'utilisateur : fermé après une minute sans touche. */
        const val PANEL_IDLE_MS = 60_000L
    }

    private data class PanelReturn(val pageIndex: Int, val focusedIndex: Int)

    /** [background] : l'application était en arrière-plan avant l'affichage temporaire. */
    private data class ReturnTarget(
        val screen: Screen,
        val pageId: String?,
        val focusedIndex: Int,
        val background: Boolean,
    )
}

// --- Fonctions pures sur l'état (testables sans contrôleur) -------------------------------------

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

/** Nouvelles valeurs de tuiles, dans toutes les pages où elles apparaissent. */
internal fun AppState.withChanges(changes: List<TileChange>): AppState {
    if (changes.isEmpty()) return this
    val values = changes.associate { it.tile to it.value }
    return copy(
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
 * Nouveau layout : la page affichée est retrouvée par son id, la sélection est gardée dans
 * les bornes, et un réglage ou une confirmation sur une tuile disparue est abandonné.
 */
internal fun AppState.withLayout(layout: Layout): AppState {
    val currentPageId = currentPage?.id
    val newPageIndex = layout.pages.indexOfFirst { it.id == currentPageId }.takeIf { it >= 0 }
        ?: pageIndex.coerceIn(0, (layout.pages.size - 1).coerceAtLeast(0))
    val tileCount = layout.pages.getOrNull(newPageIndex)?.tiles?.size ?: 0
    val ids = layout.pages.flatMap { page -> page.tiles.map { it.id } }.toSet()
    return copy(
        revision = layout.revision,
        pages = layout.pages,
        pageIndex = newPageIndex,
        focusedIndex = focusedIndex.coerceIn(0, (tileCount - 1).coerceAtLeast(0)),
        adjust = adjust?.takeIf { it.tileId in ids },
        confirm = confirm?.takeIf { it.tileId in ids },
    )
}
