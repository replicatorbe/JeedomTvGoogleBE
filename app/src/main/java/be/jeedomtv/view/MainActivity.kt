package be.jeedomtv.view

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import be.jeedomtv.BuildConfig
import be.jeedomtv.JeedomTvApp
import be.jeedomtv.JeedomTvService
import be.jeedomtv.controller.AppController
import be.jeedomtv.controller.RemoteCommand
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Screen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Vue principale : affiche l'état du Modèle et transmet au contrôleur les touches de la
 * télécommande. Modèle et Contrôleur vivent dans [JeedomTvApp].
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController

    /** Touches transmises au contrôleur (même gestion que le panneau en superposition). */
    private val keys = RemoteKeyForwarder(::shouldForward) { controller.onCommand(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        controller = (application as JeedomTvApp).controller

        // La connexion à Jeedom est lancée par l'application ; en debug, adb peut la remplacer.
        // Recréation de l'activité : la configuration de debug a déjà été appliquée.
        if (savedInstanceState == null) debugConfigFromIntent(intent)?.let { controller.submitSetup(it) }

        observeExitRequests()

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

    /*
     * Visible = activité au sommet de l'écran (onTopResumedActivityChanged), pas seulement
     * démarrée : sur la TCL, une ouverture depuis l'arrière-plan juste après la touche Accueil
     * démarre et reprend l'activité, puis l'accueil repasse devant sans qu'onStop n'arrive.
     * L'application se croyait visible et ne redemandait plus le premier plan.
     */
    private var resumed = false
    private var topResumed = false

    override fun onResume() {
        super.onResume()
        // Application au premier plan : le moment où TCL autorise le service au premier plan.
        JeedomTvService.start(this)
        resumed = true
        // Avant Android 10, pas de notion d'activité « au sommet » : reprise = visible.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) topResumed = true
        reportVisibility()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        topResumed = isTopResumedActivity
        reportVisibility()
    }

    override fun onPause() {
        resumed = false
        topResumed = false
        reportVisibility()
        super.onPause()
    }

    private fun reportVisibility() {
        Log.i(TAG, "visibilité : resumed=$resumed top=$topResumed")
        controller.onUiVisibilityChanged(resumed && topResumed)
    }

    override fun onStop() {
        keys.clear()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keys.dispatch(event)) return true
        // Non géré par le contrôleur : comportement Android normal
        // (Retour quitte l'app depuis les pages, focus Compose dans le formulaire).
        return super.dispatchKeyEvent(event)
    }

    /** Ordre `exit` de Jeedom : on passe en arrière-plan sans fermer l'application. */
    private fun observeExitRequests() {
        lifecycleScope.launch {
            controller.state
                .map { it.exitRequested }
                .distinctUntilChanged()
                .collect { requested ->
                    if (requested) {
                        Log.i(TAG, "passage en arrière-plan demandé")
                        moveTaskToBack(true)
                        controller.onExitHandled()
                    }
                }
        }
    }

    /**
     * Sur l'écran de configuration, les flèches, OK et les chiffres servent à la saisie
     * et à la navigation Compose : seuls Retour, Menu et les touches de couleur vont au contrôleur.
     */
    private fun shouldForward(command: RemoteCommand): Boolean =
        // Une question passe au-dessus du formulaire : elle reçoit toutes les touches.
        controller.state.value.question != null ||
            controller.state.value.screen != Screen.Setup ||
            command == RemoteCommand.Back || command == RemoteCommand.Menu || command is RemoteCommand.Color

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
        const val TAG = "JeedomTv"
    }
}
