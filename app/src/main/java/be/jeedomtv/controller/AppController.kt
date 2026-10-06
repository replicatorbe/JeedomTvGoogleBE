package be.jeedomtv.controller

import be.jeedomtv.model.AppModel
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.SettingsRepository
import be.jeedomtv.model.driver.JeedomDriverFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Le Contrôleur du MVC : interprète les commandes de la télécommande, pilote Jeedom
 * et met à jour le [AppModel]. Il ne touche jamais à l'interface Android.
 */
class AppController(
    private val model: AppModel,
    private val settings: SettingsRepository,
    private val driverFactory: JeedomDriverFactory,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<AppState> = model.state

    /** Au lancement : configuration enregistrée → connexion, sinon écran de configuration. */
    fun start(): Unit = TODO("MVP")

    fun submitSetup(config: JeedomConfig): Unit = TODO("MVP")

    /** Retourne true si la commande a été traitée (l'activité consomme alors la touche). */
    fun onCommand(command: RemoteCommand): Boolean = TODO("MVP")

    /** L'écran de l'application devient visible ou passe derrière une autre application. */
    fun onUiVisibilityChanged(visible: Boolean): Unit = TODO("MVP")
}
