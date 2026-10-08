package be.jeedomtv.view

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import be.jeedomtv.controller.AppController
import be.jeedomtv.controller.RemoteCommand
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Corner
import be.jeedomtv.model.Overlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Vue des superpositions : observe l'état et ajoute ou retire les fenêtres
 * `TYPE_APPLICATION_OVERLAY` par-dessus l'application vidéo, qui reste au premier plan.
 *
 * - Bandeau : fenêtre ni focusable ni tactile, les touches vont toujours à la vidéo.
 * - Panneau : fenêtre focusable en bas de l'écran ; ses touches passent par le contrôleur.
 * - Question : fenêtre focusable, toujours au-dessus du bandeau et du panneau. Sans image, un
 *   bandeau compact dans le tiers inférieur (la vidéo reste visible) ; avec image, au centre.
 *
 * Vit dans l'Application (comme le contrôleur), jamais dans l'activité.
 */
class OverlayWindowManager(
    private val context: Context,
    private val controller: AppController,
    private val scope: CoroutineScope,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)

    /** Fenêtres des notifications, une par bord (haut, bas) : créées une fois, puis gardées. */
    private val notices = HashMap<Boolean, OverlayWindow>()
    private var panel: OverlayWindow? = null
    private var question: OverlayWindow? = null
    private var statusBar: OverlayWindow? = null

    /** Écran de la TV allumé : sinon, les fenêtres passent en CREATED (lecteurs vidéo libérés). */
    private var screenOn = true

    fun start() {
        scope.launch {
            controller.state
                .map { statusWindowKind(it) }
                .distinctUntilChanged()
                .collect { showStatusBar(it) }
        }
        scope.launch {
            controller.state
                .map { kindOf(it) }
                .distinctUntilChanged()
                .collect { show(it) }
        }
        scope.launch {
            controller.state
                .map { questionWindowKind(it) }
                .distinctUntilChanged()
                .collect { showQuestion(it) }
        }
        scope.launch {
            controller.state
                .map { it.screenOn }
                .distinctUntilChanged()
                .collect { on ->
                    screenOn = on
                    allWindows().forEach { it.owner.setActive(on) }
                }
        }
    }

    private fun allWindows(): List<OverlayWindow> = notices.values + listOfNotNull(panel, question, statusBar)

    /** Fenêtre de question (et sa mise en page) affichée. */
    private var questionKind = QuestionWindowKind.None

    private fun showQuestion(kind: QuestionWindowKind) {
        Log.i(TAG, "question en superposition : $kind")
        // Une nouvelle question peut changer de mise en page (avec ou sans image) : nouvelle fenêtre.
        question = question?.let { remove(it); null }
        questionKind = kind
        if (kind != QuestionWindowKind.None) question = add(questionWindow(kind))
    }

    /**
     * Une fenêtre ajoutée passe au-dessus : la question y est remise, avec le focus. Seulement
     * quand c'est nécessaire (panneau focusable, ou première fenêtre d'un bord) : recréée, sa vidéo
     * se reconnecterait.
     */
    private fun keepQuestionOnTop() {
        val current = question ?: return
        remove(current)
        question = add(questionWindow(questionKind))
    }

    /**
     * Barre d'état : fenêtre ajoutée une fois, puis gardée ; masquée par son contenu (vide), et
     * déplacée sur place quand elle change de coin. Elle n'est donc jamais ajoutée par-dessus les
     * autres fenêtres, qui n'ont pas à être recréées.
     */
    private fun showStatusBar(corner: Corner?) {
        Log.i(TAG, "barre d'état : ${corner ?: "masquée"}")
        if (corner == null) return
        val current = statusBar
        if (current == null) {
            statusBar = add(statusWindow(corner))
            return
        }
        current.params.placeStatus(corner)
        try {
            windowManager.updateViewLayout(current.root, current.params)
        } catch (e: IllegalArgumentException) {
            // Fenêtre retirée entre-temps.
        }
    }

    private enum class Kind { None, NoticeTop, NoticeBottom, Panel, Board }

    /** Le panneau sur un tableau des trains devient une fenêtre plein écran (le genre suit la page affichée). */
    private fun kindOf(state: AppState) = when (val overlay = state.overlay) {
        Overlay.None -> Kind.None
        is Overlay.Notice -> if (overlay.banner.corner.isTop) Kind.NoticeTop else Kind.NoticeBottom
        is Overlay.Panel -> if (state.currentBoard != null) Kind.Board else Kind.Panel
    }

    /** Genre de la fenêtre [panel] (panneau en bas, ou tableau plein écran). */
    private var panelKind = Kind.None

    private fun show(kind: Kind) {
        Log.i(TAG, "superposition : $kind")
        if (kind != panelKind) {
            panel = panel?.let { remove(it); null }
            panelKind = Kind.None
        }
        when (kind) {
            // Les fenêtres des notifications restent : leur contenu s'efface en fondu.
            Kind.None -> Unit
            Kind.NoticeTop, Kind.NoticeBottom -> {
                val top = kind == Kind.NoticeTop
                if (notices[top] == null) {
                    add(noticeWindow(top))?.let { notices[top] = it }
                    // Première fois seulement : la question déjà affichée reste au-dessus.
                    keepQuestionOnTop()
                }
            }
            // Le panneau prend le focus : une question déjà affichée doit le reprendre.
            Kind.Panel, Kind.Board -> if (panel == null) {
                panel = add(if (kind == Kind.Board) boardWindow() else panelWindow())
                panelKind = kind
                keepQuestionOnTop()
            }
        }
    }

    /** Touches d'une fenêtre focusable : Retour et Menu agissent au relâchement (voir [RemoteKeyForwarder]). */
    private fun overlayKeys() = RemoteKeyForwarder(actOnRelease = RELEASE_COMMANDS) { controller.onCommand(it) }

    private fun questionWindow(kind: QuestionWindowKind): OverlayWindow {
        val keys = overlayKeys()
        val params = baseParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            if (kind == QuestionWindowKind.Dialog) {
                // Avec image : au centre, la vidéo légèrement assombrie pour que la question ressorte.
                // Plein écran (transparent autour de la boîte) : avec WRAP_CONTENT, Compose mesurait
                // la boîte trop étroite et écrasait les réponses.
                flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                dimAmount = QUESTION_DIM
                height = WindowManager.LayoutParams.MATCH_PARENT
            } else {
                // Sans image : bandeau en bas, sans voile ; la vidéo reste entièrement visible au-dessus.
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.BOTTOM
            }
        }
        val root = OverlayRoot(context, onKey = keys::dispatch).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        return OverlayWindow(root, params, onRemoved = keys::clear) { state ->
            state.question?.takeIf { it.inOverlay }?.let { current ->
                if (kind == QuestionWindowKind.Dialog) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { QuestionDialog(current) }
                } else {
                    // Carte centrée en bas, de largeur raisonnable ; de la place autour pour son ombre.
                    // Sans hauteur maximale : trois réponses sur deux rangées ne sont jamais rognées.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 24.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        QuestionBanner(current)
                    }
                }
            }
        }
    }

    /**
     * Barre d'état : petite fenêtre dans un coin, ni focusable ni tactile (la télécommande et la
     * vidéo l'ignorent), avec la marge de sécurité des téléviseurs.
     */
    private fun statusWindow(corner: Corner): OverlayWindow {
        val params = baseParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            placeStatus(corner)
            ignoreSystemInsets()
        }
        // Rien de dessiné quand la barre ne doit pas se voir (application affichée, veille…).
        return OverlayWindow(OverlayRoot(context, onKey = null), params) { state ->
            if (statusWindowKind(state) != null) state.status?.let { StatusBarView(it, style = StatusBarStyle.Overlay, unreachable = !state.jeedomReachable) }
        }
    }

    /** Coin de la barre d'état, à la marge de sécurité des téléviseurs. */
    private fun WindowManager.LayoutParams.placeStatus(corner: Corner) {
        val density = context.resources.displayMetrics.density
        gravity = (if (corner.isTop) Gravity.TOP else Gravity.BOTTOM) or (if (corner.isStart) Gravity.START else Gravity.END)
        x = (STATUS_MARGIN_X_DP * density).toInt()
        y = (STATUS_MARGIN_Y_DP * density).toInt()
    }

    private fun noticeWindow(top: Boolean): OverlayWindow {
        val params = baseParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Toute la largeur (le bandeau est centré dedans) : en WRAP_CONTENT, la fenêtre gardait
            // la largeur du texte seul et écrasait celui-ci quand l'image arrivait.
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = if (top) Gravity.TOP else Gravity.BOTTOM
            // 12 dp + les 12 dp de marge du contenu (place de l'ombre) : la carte à ~24 dp du bord.
            // En bas : au-dessus de la barre d'état (coins du bas), pour ne pas la masquer.
            y = ((if (top) 12 else 28) * context.resources.displayMetrics.density).toInt()
        }
        return OverlayWindow(OverlayRoot(context, onKey = null), params) { state -> OverlayNoticeView(state, top) }
    }

    private fun panelWindow(): OverlayWindow {
        val keys = overlayKeys()
        val params = baseParams().apply {
            // Focusable (pas de FLAG_NOT_FOCUSABLE) : la télécommande pilote le panneau.
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            width = WindowManager.LayoutParams.MATCH_PARENT
            // Hauteur du contenu : deux rangées de tuiles entières, jamais coupées.
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM
        }
        val root = OverlayRoot(context, onKey = keys::dispatch).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        return OverlayWindow(root, params, onRemoved = keys::clear) { state -> OverlayPanelView(state) }
    }

    /**
     * Tableau des trains par-dessus la vidéo : fenêtre focusable plein écran (Retour et les touches
     * de couleur passent par le contrôleur), au-dessus des barres système comme la vidéo elle-même.
     */
    private fun boardWindow(): OverlayWindow {
        val keys = overlayKeys()
        val params = baseParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            ignoreSystemInsets()
        }
        val root = OverlayRoot(context, onKey = keys::dispatch).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        return OverlayWindow(root, params, onRemoved = keys::clear) { state -> OverlayBoardView(state) }
    }

    /**
     * Position comptée depuis le bord physique de l'écran. Par défaut (Android 11+), une fenêtre de
     * superposition est placée dans la zone laissée par les barres système : sa marge s'ajoutait à
     * l'encart de la barre de navigation (masquée mais réservée sur Google TV), soit 5 à 8 cm de
     * trop au-dessus du bord de la TV.
     */
    private fun WindowManager.LayoutParams.ignoreSystemInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(0)
            setFitInsetsSides(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun add(window: OverlayWindow): OverlayWindow? = try {
        window.owner.start()
        window.owner.setActive(screenOn)
        windowManager.addView(window.root, window.params)
        window.root.requestFocus()
        window
    } catch (e: RuntimeException) {
        // Permission retirée entre-temps (BadTokenException, SecurityException) : rien à afficher.
        Log.w(TAG, "superposition impossible", e)
        window.owner.destroy()
        null
    }

    private fun remove(window: OverlayWindow) {
        try {
            windowManager.removeView(window.root)
        } catch (e: IllegalArgumentException) {
            // Déjà retirée.
        }
        window.onRemoved()
        window.owner.destroy()
    }

    /** Une fenêtre de superposition et la ComposeView qu'elle héberge, hors de toute activité. */
    private inner class OverlayWindow(
        val root: OverlayRoot,
        val params: WindowManager.LayoutParams,
        val onRemoved: () -> Unit = {},
        content: @Composable (AppState) -> Unit,
    ) {
        val owner = OverlayOwner()

        init {
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)
            root.setViewTreeViewModelStoreOwner(owner)
            val compose = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    JeedomTvTheme {
                        val state by controller.state.collectAsState()
                        content(state)
                    }
                }
            }
            root.addView(
                compose,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
    }

    private companion object {
        const val TAG = "JeedomTv"

        /**
         * Marges de la barre d'état (avec le 1 dp de marge intérieure de [StatusBarStyle.Overlay]) :
         * 6 dp du bord, comme TvOverlay (12 px en 1920 × 1080, densité 2).
         */
        const val STATUS_MARGIN_X_DP = 5
        const val STATUS_MARGIN_Y_DP = 5



        /** Assombrissement de la vidéo derrière une question avec image. */
        const val QUESTION_DIM = 0.4f

        /** Commandes qui ferment ou quittent une superposition : exécutées au relâchement. */
        val RELEASE_COMMANDS = setOf(RemoteCommand.Back, RemoteCommand.Menu)
    }
}

/** Vue racine d'une superposition : intercepte les touches avant Compose (panneau, question). */
internal class OverlayRoot(
    context: Context,
    private val onKey: ((KeyEvent) -> Boolean)?,
) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onKey?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Cycle de vie, état sauvegardé et ViewModelStore d'une ComposeView hors activité :
 * Compose les cherche sur l'arbre de vues.
 */
internal class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** Écran éteint : CREATED (la vidéo s'arrête et rend son décodeur) ; rallumé : RESUMED. */
    fun setActive(active: Boolean) {
        val current = registry.currentState
        if (current == Lifecycle.State.INITIALIZED || current == Lifecycle.State.DESTROYED) return
        registry.currentState = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
