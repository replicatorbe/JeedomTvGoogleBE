package be.jeedomtv.controller

import be.jeedomtv.model.Adjust
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.PendingAction
import be.jeedomtv.model.Screen
import be.jeedomtv.model.SettingsRepository
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TileChange
import be.jeedomtv.model.TileType
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
 * Le Contrôleur du MVC : interprète les commandes de la télécommande, pilote Jeedom
 * et met à jour le [AppModel]. Il ne touche jamais à l'interface Android.
 *
 * Tout son état interne est confiné au thread principal (celui de [scope]).
 */
class AppController(
    private val model: AppModel,
    private val settings: SettingsRepository,
    private val driverFactory: JeedomDriverFactory,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<AppState> = model.state

    /** Pilote de la dernière connexion réussie (null tant qu'aucune connexion n'a abouti). */
    private var driver: JeedomDriver? = null

    /** Chargement ou connexion en cours ; annulé si une nouvelle connexion est demandée. */
    private var connectJob: Job? = null

    /** Boucle des changements en direct ; une seule à la fois. */
    private var changesJob: Job? = null

    /** Efface le message temporaire. */
    private var noticeTimer: Job? = null

    /** Efface le retour visuel d'une tuile. */
    private var flashTimer: Job? = null

    /** Au lancement : configuration enregistrée → connexion, sinon écran de configuration. */
    fun start() {
        launchExclusive {
            model.update { it.copy(screen = Screen.Loading, error = null) }
            val config = try {
                settings.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // Configuration illisible : on repart du formulaire.
            }
            if (config == null) {
                model.update { it.copy(screen = Screen.Setup) }
            } else {
                connectNow(config)
            }
        }
    }

    fun submitSetup(config: JeedomConfig) {
        launchExclusive { connectNow(config) }
    }

    /** Retourne true si la commande a été traitée (l'activité consomme alors la touche). */
    fun onCommand(command: RemoteCommand): Boolean {
        val current = state.value
        return when (current.screen) {
            Screen.Pages -> onPagesCommand(command, current)
            Screen.Setup -> onSetupCommand(command, current)
            Screen.Loading -> false
        }
    }

    /** L'écran de l'application devient visible ou passe derrière une autre application. */
    fun onUiVisibilityChanged(visible: Boolean) {
        model.update { it.copy(uiVisible = visible) }
        updateChangesLoop()
    }

    /** La TV allume ou éteint son écran (sortie ou entrée en veille). */
    fun onScreenChanged(on: Boolean): Unit = TODO("MVP 3")

    /** Rallumage de l'écran ou retour du réseau : la boucle des changements repart aussitôt. */
    fun onNetworkMaybeRestored(): Unit = TODO("MVP 3")

    /** La vue a mis l'application en arrière-plan suite à [AppState.exitRequested]. */
    fun onExitHandled(): Unit = TODO("MVP 3")

    // --- Connexion ---------------------------------------------------------------------------

    /** Annule le travail en cours puis lance [block] : une seule connexion à la fois. */
    private fun launchExclusive(block: suspend CoroutineScope.() -> Unit) {
        stopChangesLoop()
        connectJob?.cancel()
        connectJob = scope.launch(block = block)
    }

    private suspend fun connectNow(config: JeedomConfig) {
        model.update {
            it.copy(screen = Screen.Loading, config = config, error = null, adjust = null, confirm = null)
        }
        try {
            val newDriver = driverFactory.create(config)
            val ping = newDriver.ping()
            currentCoroutineContext().ensureActive()
            // La configuration n'est enregistrée qu'après un ping réussi.
            saveConfig(config)
            val layout = newDriver.layout()
            // Une connexion annulée entre-temps ne doit pas écraser l'état.
            currentCoroutineContext().ensureActive()
            driver = newDriver
            model.update {
                it.copy(tvName = ping.tvName, offline = false, error = null).withLayout(layout)
                    .copy(screen = Screen.Pages)
            }
            updateChangesLoop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: JeedomException) {
            showSetupError(e.message ?: GENERIC_ERROR)
        } catch (e: Exception) {
            showSetupError(GENERIC_ERROR)
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
        stopChangesLoop()
        model.update { it.copy(screen = Screen.Setup, error = message, adjust = null, confirm = null) }
    }

    // --- Changements en direct ---------------------------------------------------------------

    /** La boucle tourne tant que l'écran Pages est affiché ET l'application visible. */
    private fun updateChangesLoop() {
        val current = state.value
        val target = driver
        val shouldRun = current.screen == Screen.Pages && current.uiVisible && target != null
        if (!shouldRun) {
            stopChangesLoop()
        } else if (changesJob?.isActive != true) {
            changesJob = scope.launch { runChanges(target!!) }
        }
    }

    private fun stopChangesLoop() {
        changesJob?.cancel()
        changesJob = null
    }

    /**
     * Attente longue des changements : applique les valeurs, recharge le layout si la révision
     * change. Après une erreur : « hors ligne », pause, rechargement du layout, reprise sans curseur.
     */
    private suspend fun runChanges(target: JeedomDriver) {
        var since: String? = null
        while (true) {
            try {
                val result = target.changes(since)
                currentCoroutineContext().ensureActive()
                since = result.since
                model.update { it.copy(offline = false).withChanges(result.changes) }
                val revision = result.revision
                if (revision != null && revision != state.value.revision) {
                    applyLayout(target.layout())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationException) {
                // Clé régénérée ou équipement désactivé dans Jeedom : il faut ressaisir la clé.
                changesJob = null
                showSetupError(e.message ?: GENERIC_ERROR)
                return
            } catch (e: Exception) {
                model.update { it.copy(offline = true) }
                delay(RETRY_DELAY_MS)
                // Des changements ont pu être perdus : on repart d'un layout frais.
                try {
                    applyLayout(target.layout())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Toujours injoignable : le prochain appel à changes échouera et réessaiera.
                }
                since = null
            }
        }
    }

    private suspend fun applyLayout(layout: Layout) {
        currentCoroutineContext().ensureActive()
        model.update { it.withLayout(layout) }
    }

    // --- Écran de configuration --------------------------------------------------------------

    /** Le formulaire Compose gère lui-même focus et saisie ; seul Retour nous intéresse. */
    private fun onSetupCommand(command: RemoteCommand, current: AppState): Boolean {
        if (command != RemoteCommand.Back || driver == null || current.revision == null) return false
        model.update { it.copy(screen = Screen.Pages, error = null) }
        updateChangesLoop()
        return true
    }

    // --- Écran des pages ---------------------------------------------------------------------

    private fun onPagesCommand(command: RemoteCommand, current: AppState): Boolean {
        current.confirm?.let { return onConfirmCommand(command, it) }
        val adjustTile = current.adjustTile
        val adjust = current.adjust
        if (adjust != null && adjustTile != null) return onAdjustCommand(command, adjust, adjustTile)
        if (adjust != null) model.update { it.copy(adjust = null) } // Tuile disparue entre-temps.
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
                    model.update { it.copy(focusedIndex = target) }
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
        if (target in 0 until size) model.update { it.copy(focusedIndex = target) }
    }

    /** Page suivante / précédente, en boucle ; la sélection revient sur la première tuile. */
    private fun showPage(index: Int) {
        val count = state.value.pages.size
        if (count == 0) return
        model.update { it.copy(pageIndex = Math.floorMod(index, count), focusedIndex = 0) }
    }

    private fun showSetup() {
        stopChangesLoop()
        model.update { it.copy(screen = Screen.Setup, error = null, adjust = null, confirm = null) }
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
        model.update { it.copy(adjust = Adjust(tile.id, pending)) }
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
        model.update { s -> s.adjust?.let { s.copy(adjust = it.copy(pending = clamped)) } ?: s }
    }

    private fun leaveAdjust() {
        model.update { it.copy(adjust = null) }
    }

    // --- Confirmation et ordres --------------------------------------------------------------

    private fun onConfirmCommand(command: RemoteCommand, pending: PendingAction): Boolean {
        when (command) {
            RemoteCommand.Ok -> {
                model.update { it.copy(confirm = null) }
                state.value.findTile(pending.tileId)?.let { execute(it, pending.action, pending.value) }
            }
            RemoteCommand.Back -> model.update { it.copy(confirm = null) }
            RemoteCommand.Menu -> showSetup()
            else -> Unit // La boîte de confirmation garde la main.
        }
        return true
    }

    /** Ordre demandé par l'utilisateur : confirmation d'abord si la tuile l'exige. */
    private fun request(tile: Tile, action: TileAction, value: Double? = null) {
        if (tile.confirm) {
            model.update {
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
                if (newValue != null) model.update { it.withChanges(listOf(TileChange(tile.id, newValue))) }
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
        model.update { it.withChanges(listOf(TileChange(tile.id, optimistic))) }
        scope.launch {
            try {
                val newValue = target.exec(tile.id, TileAction.Toggle, null)
                if (newValue != null) model.update { it.withChanges(listOf(TileChange(tile.id, newValue))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Retour en arrière, sauf si une valeur plus récente est arrivée entre-temps.
                model.update {
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
        model.update { it.copy(notice = message) }
        noticeTimer?.cancel()
        noticeTimer = scope.launch {
            delay(NOTICE_DURATION_MS)
            model.update { it.copy(notice = null) }
        }
    }

    private fun flash(tileId: String) {
        model.update { it.copy(flashTileId = tileId) }
        flashTimer?.cancel()
        flashTimer = scope.launch {
            delay(FLASH_DURATION_MS)
            model.update { it.copy(flashTileId = null) }
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
    }
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
