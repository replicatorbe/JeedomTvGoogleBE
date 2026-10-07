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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.delay
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

    private var notice: OverlayWindow? = null
    private var panel: OverlayWindow? = null
    private var question: OverlayWindow? = null
    private var statusBar: OverlayWindow? = null

    fun start() {
        scope.launch {
            controller.state
                .map { statusWindowKind(it) }
                .distinctUntilChanged()
                .collect { showStatusBar(it) }
        }
        scope.launch {
            controller.state
                .map { kindOf(it.overlay) }
                .distinctUntilChanged()
                .collect { show(it) }
        }
        scope.launch {
            controller.state
                .map { questionWindowKind(it) }
                .distinctUntilChanged()
                .collect { showQuestion(it) }
        }
    }

    /** Fenêtre de question (et sa mise en page) affichée. */
    private var questionKind = QuestionWindowKind.None

    private fun showQuestion(kind: QuestionWindowKind) {
        Log.i(TAG, "question en superposition : $kind")
        // Une nouvelle question peut changer de mise en page (avec ou sans image) : nouvelle fenêtre.
        question = question?.let { remove(it); null }
        questionKind = kind
        if (kind != QuestionWindowKind.None) question = add(questionWindow(kind))
    }

    /** Une fenêtre ajoutée passe au-dessus : la question y est remise, avec le focus. */
    private fun keepQuestionOnTop() {
        val current = question ?: return
        remove(current)
        question = add(questionWindow(questionKind))
    }

    /** Barre d'état : fenêtre ajoutée ou retirée (et recréée si elle change de coin). */
    private fun showStatusBar(corner: Corner?) {
        Log.i(TAG, "barre d'état : ${corner ?: "masquée"}")
        statusBar = statusBar?.let { remove(it); null }
        if (corner == null) return
        statusBar = add(statusWindow(corner))
        // Ajoutée en dernier, elle passerait au-dessus : bandeau, panneau et question y sont remis.
        if (statusBar != null) raiseOthers()
    }

    private fun raiseOthers() {
        notice?.let { current ->
            remove(current)
            notice = add(noticeWindow(top = noticeTop))
        }
        panel?.let { current ->
            remove(current)
            panel = add(panelWindow())
        }
        keepQuestionOnTop()
    }

    private enum class Kind { None, NoticeTop, NoticeBottom, Panel }

    /** Bandeau affiché en haut ou en bas de l'écran (coin de la notification). */
    private var noticeTop = true

    private fun kindOf(overlay: Overlay) = when (overlay) {
        Overlay.None -> Kind.None
        is Overlay.Notice -> if (overlay.banner.corner.isTop) Kind.NoticeTop else Kind.NoticeBottom
        is Overlay.Panel -> Kind.Panel
    }

    private fun show(kind: Kind) {
        Log.i(TAG, "superposition : $kind")
        // Bandeau d'un autre coin (haut / bas) : nouvelle fenêtre. L'ancien s'efface en fondu.
        notice = notice?.let { fadeOutAndRemove(it); null }
        if (kind != Kind.Panel) panel = panel?.let { remove(it); null }
        when (kind) {
            Kind.None -> Unit
            // Une question déjà affichée doit rester au-dessus (et garder le focus).
            Kind.NoticeTop, Kind.NoticeBottom -> {
                noticeTop = kind == Kind.NoticeTop
                notice = add(noticeWindow(top = noticeTop))
                keepQuestionOnTop()
            }
            Kind.Panel -> if (panel == null) {
                panel = add(panelWindow())
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
                    // Bas de l'écran (40 % de la hauteur au plus), marges de sécurité des téléviseurs comprises.
                    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 2 / 5
                    // Carte centrée en bas, de largeur raisonnable ; de la place autour pour son ombre.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
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
        val density = context.resources.displayMetrics.density
        val params = baseParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = (if (corner.isTop) Gravity.TOP else Gravity.BOTTOM) or (if (corner.isStart) Gravity.START else Gravity.END)
            x = (STATUS_MARGIN_X_DP * density).toInt()
            y = (STATUS_MARGIN_Y_DP * density).toInt()
            ignoreSystemInsets()
        }
        return OverlayWindow(OverlayRoot(context, onKey = null), params) { state ->
            state.status?.let { StatusBarView(it, style = StatusBarStyle.Overlay) }
        }
    }

    /** Fondu de sortie (voir [OverlayNoticeView]), puis la fenêtre est retirée. */
    private fun fadeOutAndRemove(window: OverlayWindow) {
        window.shown.value = false
        scope.launch {
            delay(EXIT_FADE_MS + 50L)
            remove(window)
        }
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
        val shown = mutableStateOf(true)
        return OverlayWindow(OverlayRoot(context, onKey = null), params, shown = shown) { state ->
            OverlayNoticeView(state, shown.value)
        }
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
        /** Faux pendant le fondu de sortie, avant le retrait de la fenêtre. */
        val shown: MutableState<Boolean> = mutableStateOf(true),
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

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
