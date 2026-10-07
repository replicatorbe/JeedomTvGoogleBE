package be.jeedomtv.view

import android.view.KeyEvent
import be.jeedomtv.controller.RemoteCommand
import be.jeedomtv.controller.RemoteKeyMapper

/**
 * Transmet les touches de la télécommande au contrôleur, pour l'activité comme pour les fenêtres
 * en superposition. Une touche dont l'appui a été consommé l'est aussi jusqu'à son relâchement.
 */
class RemoteKeyForwarder(
    /** Commande à transmettre ou non (l'écran de configuration garde flèches et chiffres). */
    private val shouldForward: (RemoteCommand) -> Boolean = { true },
    /**
     * Commandes exécutées au relâchement et non à l'appui. Pour une superposition, Retour (et Menu)
     * la ferment : à l'appui, le relâchement arriverait seul à l'application vidéo, devenue
     * destinataire des touches. Ces commandes doivent toujours être traitées par le contrôleur.
     */
    private val actOnRelease: Set<RemoteCommand> = emptySet(),
    /** Retourne true si le contrôleur a traité la commande. */
    private val onCommand: (RemoteCommand) -> Boolean,
) {
    private val consumedKeyCodes = mutableSetOf<Int>()

    /** Commande à exécuter au relâchement de la touche, par code de touche. */
    private val onRelease = mutableMapOf<Int, RemoteCommand>()

    /** true si l'événement est consommé ; sinon, comportement Android normal. */
    fun dispatch(event: KeyEvent): Boolean = onKey(event.keyCode, event.action, event.repeatCount)

    /** Règle de [dispatch], sans dépendre de KeyEvent (testable). */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                val command = RemoteKeyMapper.map(keyCode)
                if (command != null && repeatCount == 0 && command in actOnRelease && shouldForward(command)) {
                    onRelease[keyCode] = command
                    consumedKeyCodes += keyCode
                    return true
                }
                // Touche maintenue : seules les flèches se répètent (défilement de la sélection ou de la
                // valeur en attente) ; OK maintenu ne doit pas basculer un interrupteur dix fois.
                val repeatable = repeatCount == 0 || command in REPEATABLE
                if (command != null && repeatable && command !in actOnRelease &&
                    shouldForward(command) && onCommand(command)
                ) {
                    consumedKeyCodes += keyCode
                    return true
                }
                // Répétition ignorée d'une touche déjà consommée : on la garde jusqu'au relâchement.
                if (keyCode in consumedKeyCodes) return true
            }
            KeyEvent.ACTION_UP -> {
                val command = onRelease.remove(keyCode)
                if (consumedKeyCodes.remove(keyCode)) {
                    command?.let { onCommand(it) }
                    return true
                }
            }
        }
        return false
    }

    /** Fenêtre cachée : les relâchements ne viendront plus. */
    fun clear() {
        consumedKeyCodes.clear()
        onRelease.clear()
    }

    private companion object {
        val REPEATABLE = setOf(RemoteCommand.Up, RemoteCommand.Down, RemoteCommand.Left, RemoteCommand.Right)
    }
}
