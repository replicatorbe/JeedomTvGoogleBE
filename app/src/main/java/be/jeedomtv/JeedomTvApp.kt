package be.jeedomtv

import android.app.Application
import be.jeedomtv.controller.AppController
import be.jeedomtv.model.AppModel
import be.jeedomtv.model.DataStoreSettingsRepository
import be.jeedomtv.model.driver.JeedomDriverFactory
import be.jeedomtv.model.driver.JeedomHttpDriver
import kotlinx.coroutines.MainScope

/**
 * Racine de composition : Modèle et Contrôleur vivent ici, aussi longtemps que le processus,
 * et non dans l'activité.
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
    }
}
