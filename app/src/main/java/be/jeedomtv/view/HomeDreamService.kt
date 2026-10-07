package be.jeedomtv.view

import android.service.dreams.DreamService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import be.jeedomtv.JeedomTvApp

/**
 * Écran de veille domotique (Daydream) : grande horloge, date, et les infos du bandeau de Jeedom
 * en grand. Les valeurs viennent de l'état du contrôleur, tenu à jour par la boucle des
 * changements de l'Application : rien de plus ne tourne pendant la veille.
 *
 * Non interactif : toute touche de la télécommande le quitte (comportement standard).
 */
class HomeDreamService : DreamService() {

    /** Cycle de vie de la ComposeView, hors activité (comme les superpositions). */
    private var owner: OverlayOwner? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val controller = (application as JeedomTvApp).controller
        val viewOwner = OverlayOwner().also { it.start() }
        owner = viewOwner
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(viewOwner)
            setViewTreeSavedStateRegistryOwner(viewOwner)
            setViewTreeViewModelStoreOwner(viewOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                JeedomTvTheme {
                    val state by controller.state.collectAsState()
                    DreamView(dreamContent(state))
                }
            }
        }
        setContentView(view)
    }

    override fun onDetachedFromWindow() {
        owner?.destroy()
        owner = null
        super.onDetachedFromWindow()
    }
}
