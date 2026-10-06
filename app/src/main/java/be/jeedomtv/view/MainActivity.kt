package be.jeedomtv.view

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import be.jeedomtv.BuildConfig
import be.jeedomtv.JeedomTvApp
import be.jeedomtv.controller.AppController
import be.jeedomtv.controller.RemoteCommand
import be.jeedomtv.controller.RemoteKeyMapper
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Screen

/**
 * Vue principale : affiche l'état du Modèle et transmet au contrôleur les touches de la
 * télécommande. Modèle et Contrôleur vivent dans [JeedomTvApp].
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController

    /** Touches dont l'ACTION_DOWN a été consommé : on consomme aussi leur ACTION_UP. */
    private val consumedKeyCodes = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        controller = (application as JeedomTvApp).controller

        // La connexion à Jeedom est lancée par l'application ; en debug, adb peut la remplacer.
        // Recréation de l'activité : la configuration de debug a déjà été appliquée.
        if (savedInstanceState == null) debugConfigFromIntent(intent)?.let { controller.submitSetup(it) }

        setContent {
            JeedomTvTheme {
                AppView(controller)
            }
        }
    }

    /** Application déjà lancée (launchMode singleTask) : adb peut quand même passer une configuration. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugConfigFromIntent(intent)?.let { controller.submitSetup(it) }
    }

    override fun onStart() {
        super.onStart()
        controller.onUiVisibilityChanged(true)
    }

    override fun onStop() {
        consumedKeyCodes.clear()
        controller.onUiVisibilityChanged(false)
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val command = RemoteKeyMapper.map(event.keyCode)
                // Touche maintenue : seules les flèches se répètent (défilement de la sélection ou de la
                // valeur en attente) ; OK maintenu ne doit pas basculer un interrupteur dix fois.
                val repeatable = event.repeatCount == 0 || command in REPEATABLE
                if (command != null && repeatable && shouldForward(command) && controller.onCommand(command)) {
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
        // Non géré par le contrôleur : comportement Android normal
        // (Retour quitte l'app depuis les pages, focus Compose dans le formulaire).
        return super.dispatchKeyEvent(event)
    }

    /**
     * Sur l'écran de configuration, les flèches, OK et les chiffres servent à la saisie
     * et à la navigation Compose : seuls Retour et Menu vont au contrôleur.
     */
    private fun shouldForward(command: RemoteCommand): Boolean =
        controller.state.value.screen != Screen.Setup ||
            command == RemoteCommand.Back || command == RemoteCommand.Menu

    /**
     * Build debug uniquement : configuration passée par adb, le clavier TV rendant la saisie pénible.
     * adb shell am start -n be.jeedomtv/.view.MainActivity --es jeedom_host IP --es jeedom_key CLE
     */
    private fun debugConfigFromIntent(intent: Intent?): JeedomConfig? {
        if (!BuildConfig.DEBUG) return null
        val host = intent?.getStringExtra("jeedom_host") ?: return null
        return JeedomConfig(host = host, key = intent.getStringExtra("jeedom_key").orEmpty())
    }

    private companion object {
        val REPEATABLE = setOf(RemoteCommand.Up, RemoteCommand.Down, RemoteCommand.Left, RemoteCommand.Right)
    }
}
