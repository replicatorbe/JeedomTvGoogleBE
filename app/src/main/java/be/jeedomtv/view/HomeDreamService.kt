package be.jeedomtv.view

import android.service.dreams.DreamService
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import be.jeedomtv.JeedomTvApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Écran de veille domotique (Daydream) : grande horloge, date, et les infos du bandeau de Jeedom
 * en grand. Les valeurs viennent de l'état du contrôleur, tenu à jour par la boucle des
 * changements de l'Application : rien de plus ne tourne pendant la veille.
 *
 * Non interactif : toute touche de la télécommande le quitte (comportement standard). Un ordre
 * de Jeedom qui demande l'attention (question, page, ouverture de l'application) le quitte aussi.
 */
class HomeDreamService : DreamService() {

    /** Cycle de vie de la ComposeView, hors activité (comme les superpositions). */
    private var owner: OverlayOwner? = null

    /** Surveillance des ordres qui réveillent l'écran, le temps de la veille. */
    private var scope: CoroutineScope? = null

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
                    // Seul ce que la veille affiche compte : les autres changements d'état
                    // (sélection, compte à rebours d'une question…) ne la redessinent pas.
                    val contents = remember { controller.state.map(::dreamContent).distinctUntilChanged() }
                    val content by contents.collectAsState(dreamContent(controller.state.value))
                    DreamView(content)
                }
            }
        }
        setContentView(view)

        scope = MainScope().also { dreamScope ->
            dreamScope.launch {
                controller.state
                    .map(::dreamShouldWake)
                    .distinctUntilChanged()
                    .drop(1) // Seulement un nouvel ordre, pas un état antérieur à la veille.
                    .filter { it }
                    .collect {
                        Log.i(TAG, "écran de veille quitté : ordre de Jeedom")
                        finish()
                    }
            }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        owner?.destroy()
        owner = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "JeedomTv"
    }
}
