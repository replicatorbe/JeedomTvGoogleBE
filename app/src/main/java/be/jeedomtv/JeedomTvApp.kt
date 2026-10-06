package be.jeedomtv

import android.app.Application
import android.content.Intent
import be.jeedomtv.controller.AppController
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.DataStoreSettingsRepository
import be.jeedomtv.model.driver.JeedomDriverFactory
import be.jeedomtv.model.driver.JeedomHttpDriver
import be.jeedomtv.view.MainActivity
import kotlinx.coroutines.MainScope
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
        controller = AppController(model, settings, driverFactory, scope)

        controller.start()
        bringToFrontOnRequest()
        JeedomTvService.start(this)
    }

    /**
     * Ordre `show` reçu pendant qu'une autre application est affichée : on ouvre l'écran.
     * Android 10+ ne l'autorise depuis l'arrière-plan qu'avec la permission
     * « afficher par-dessus les autres applications » (accordée par adb sur Google TV).
     */
    private fun bringToFrontOnRequest() {
        scope.launch {
            model.state
                .map { it.foregroundRequested }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    startActivity(
                        Intent(this@JeedomTvApp, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
        }
    }
}
