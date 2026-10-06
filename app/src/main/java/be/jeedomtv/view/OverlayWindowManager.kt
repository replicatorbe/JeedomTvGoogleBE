package be.jeedomtv.view

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
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
import be.jeedomtv.model.AppState
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

    fun start() {
        scope.launch {
            controller.state
                .map { kindOf(it.overlay) }
                .distinctUntilChanged()
                .collect { show(it) }
        }
    }

    private enum class Kind { None, Notice, Panel }

    private fun kindOf(overlay: Overlay) = when (overlay) {
        Overlay.None -> Kind.None
        is Overlay.Notice -> Kind.Notice
        is Overlay.Panel -> Kind.Panel
    }

    private fun show(kind: Kind) {
        Log.i(TAG, "superposition : $kind")
        if (kind != Kind.Notice) notice = notice?.let { remove(it); null }
        if (kind != Kind.Panel) panel = panel?.let { remove(it); null }
        when (kind) {
            Kind.None -> Unit
            Kind.Notice -> if (notice == null) notice = add(noticeWindow())
            Kind.Panel -> if (panel == null) panel = add(panelWindow())
        }
    }

    private fun noticeWindow(): OverlayWindow {
        val params = baseParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * context.resources.displayMetrics.density).toInt()
        }
        return OverlayWindow(OverlayRoot(context, onKey = null), params) { state -> OverlayNoticeView(state) }
    }

    private fun panelWindow(): OverlayWindow {
        val keys = RemoteKeyForwarder { controller.onCommand(it) }
        val params = baseParams().apply {
            // Focusable (pas de FLAG_NOT_FOCUSABLE) : la télécommande pilote le panneau.
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = (context.resources.displayMetrics.heightPixels * PANEL_HEIGHT_RATIO).toInt()
            gravity = Gravity.BOTTOM
        }
        val root = OverlayRoot(context, onKey = keys::dispatch).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        return OverlayWindow(root, params, onRemoved = keys::clear) { state -> OverlayPanelView(state) }
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

        /** Panneau sur un peu plus de la moitié basse de l'écran : la vidéo reste visible au-dessus. */
        const val PANEL_HEIGHT_RATIO = 0.6f
    }
}

/** Vue racine d'une superposition : intercepte les touches avant Compose (panneau seulement). */
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
