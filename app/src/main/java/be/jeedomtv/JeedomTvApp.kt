package be.jeedomtv

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.util.Log
import be.jeedomtv.controller.AppController
import be.jeedomtv.controller.OverlayPermission
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.DataStoreSettingsRepository
import be.jeedomtv.model.driver.JeedomDriverFactory
import be.jeedomtv.model.driver.JeedomHttpDriver
import be.jeedomtv.view.MainActivity
import be.jeedomtv.view.OverlayWindowManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Racine de composition : Modèle et Contrôleur vivent ici, aussi longtemps que le processus,
 * et non dans l'activité. Le service au premier plan et l'écran s'y branchent tous les deux.
 */
class JeedomTvApp : Application() {

    lateinit var model: AppModel
        private set
    lateinit var controller: AppController
        private set

    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        model = AppModel()
        val settings = DataStoreSettingsRepository(this)
        val driverFactory = JeedomDriverFactory { config -> JeedomHttpDriver(config) }
        // Permission « afficher par-dessus » (appops SYSTEM_ALERT_WINDOW) : relue à chaque ordre.
        val overlayPermission = OverlayPermission { Settings.canDrawOverlays(this) }
        controller = AppController(model, settings, driverFactory, scope, overlayPermission, BuildConfig.VERSION_NAME)

        controller.start()
        bringToFrontOnRequest()
        OverlayWindowManager(this, controller, scope).start()
        JeedomTvService.start(this)
    }

    /**
     * Ordre `show` reçu pendant qu'une autre application est affichée : on ouvre l'écran.
     * Android 10+ ne l'autorise depuis l'arrière-plan qu'avec la permission
     * « afficher par-dessus les autres applications » (accordée par adb sur Google TV).
     */
    private fun openScreen() {
        Log.i(TAG, "ouverture de l'écran demandée")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun bringToFrontOnRequest() {
        scope.launch {
            model.state
                .map { it.foregroundRequested }
                .distinctUntilChanged()
                .filter { it }
                .collectLatest {
                    openScreen()
                    // Android bloque ~5 s les ouvertures depuis l'arrière-plan après la touche
                    // Accueil : la demande est alors perdue. Une seconde tentative, une fois ce
                    // délai passé, si l'écran n'est toujours pas au premier plan.
                    delay(FOREGROUND_RETRY_MS)
                    if (model.state.value.foregroundRequested) openScreen()
                }
        }
    }

    private companion object {
        /** Au-delà du blocage d'Android (~5 s) qui suit la touche Accueil. */
        const val FOREGROUND_RETRY_MS = 6_000L

        const val TAG = "JeedomTv"

    }
}
