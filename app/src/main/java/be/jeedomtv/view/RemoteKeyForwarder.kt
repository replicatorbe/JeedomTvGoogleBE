package be.jeedomtv.view

import android.view.KeyEvent
import be.jeedomtv.controller.RemoteCommand
import be.jeedomtv.controller.RemoteKeyMapper

/**
 * Transmet les touches de la télécommande au contrôleur, pour l'activité comme pour le panneau en
 * superposition. Une touche dont l'appui a été consommé l'est aussi jusqu'à son relâchement.
 */
class RemoteKeyForwarder(
    /** Commande à transmettre ou non (l'écran de configuration garde flèches et chiffres). */
    private val shouldForward: (RemoteCommand) -> Boolean = { true },
    /** Retourne true si le contrôleur a traité la commande. */
    private val onCommand: (RemoteCommand) -> Boolean,
) {
    private val consumedKeyCodes = mutableSetOf<Int>()

    /** true si l'événement est consommé ; sinon, comportement Android normal. */
    fun dispatch(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val command = RemoteKeyMapper.map(event.keyCode)
                // Touche maintenue : seules les flèches se répètent (défilement de la sélection ou de la
                // valeur en attente) ; OK maintenu ne doit pas basculer un interrupteur dix fois.
                val repeatable = event.repeatCount == 0 || command in REPEATABLE
                if (command != null && repeatable && shouldForward(command) && onCommand(command)) {
                    consumedKeyCodes += event.keyCode
                    return true
                }
                // Répétition ignorée d'une touche déjà consommée : on la garde jusqu'au relâchement.
                if (event.keyCode in consumedKeyCodes) return true
            }
            KeyEvent.ACTION_UP -> {
                if (consumedKeyCodes.remove(event.keyCode)) return true
            }
        }
        return false
    }

    /** Fenêtre cachée : les relâchements ne viendront plus. */
    fun clear() {
        consumedKeyCodes.clear()
    }

    private companion object {
        val REPEATABLE = setOf(RemoteCommand.Up, RemoteCommand.Down, RemoteCommand.Left, RemoteCommand.Right)
    }
}
