package be.jeedomtv.controller

import android.view.KeyEvent
import be.jeedomtv.model.AppState

/**
 * Règle du service d'accessibilité, qui voit passer toutes les touches avant les fenêtres :
 *
 * - il ne garde que les quatre touches de couleur associées à une page (rouge par défaut),
 *   et seulement quand une autre application a le focus ([AppState.ownsRemoteKeys] faux) ;
 *   il transmet alors la touche au contrôleur et consomme l'appui comme le relâchement ;
 * - quand l'écran, le panneau ou une question de l'application est affiché, il laisse passer :
 *   la fenêtre reçoit la touche elle-même. Une touche n'est donc jamais traitée deux fois ;
 * - toutes les autres touches passent, sans exception.
 *
 * Seul cas particulier : une touche de couleur reçue par le panneau qui le ferme (même touche).
 * Son relâchement arriverait seul à l'application vidéo ; il est consommé ici.
 */
class ColorKeyFilter(
    private val state: () -> AppState,
    /** Retourne true si le contrôleur a traité la commande. */
    private val onCommand: (RemoteCommand) -> Boolean,
) {
    private enum class Down { Consumed, ToOurWindow }

    /** Appuis en cours des touches de couleur, par code de touche. */
    private val downs = mutableMapOf<Int, Down>()

    /** true : l'événement est consommé (il n'ira à aucune fenêtre). */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val color = RemoteKeyMapper.map(keyCode) as? RemoteCommand.Color ?: return false
        return when (action) {
            KeyEvent.ACTION_DOWN -> onDown(keyCode, color, repeatCount)
            KeyEvent.ACTION_UP -> when (downs.remove(keyCode)) {
                Down.Consumed -> true
                // Le panneau s'est fermé à l'appui : le relâchement n'a plus de destinataire chez nous.
                Down.ToOurWindow -> !state().ownsRemoteKeys
                null -> false
            }
            else -> false
        }
    }

    private fun onDown(keyCode: Int, color: RemoteCommand.Color, repeatCount: Int): Boolean {
        // Touche maintenue : les répétitions suivent le sort du premier appui, sans nouvelle commande.
        if (repeatCount > 0) return downs[keyCode] == Down.Consumed
        val current = state()
        if (current.ownsRemoteKeys) {
            downs[keyCode] = Down.ToOurWindow
            return false
        }
        if (current.pageIndexFor(color.key) == null) {
            downs.remove(keyCode)
            return false
        }
        onCommand(color)
        downs[keyCode] = Down.Consumed
        return true
    }

    /** Service interrompu : les relâchements en attente ne viendront plus. */
    fun clear() {
        downs.clear()
    }
}
