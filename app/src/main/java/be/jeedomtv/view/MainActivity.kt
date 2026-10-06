package be.jeedomtv.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import be.jeedomtv.JeedomTvApp
import be.jeedomtv.controller.AppController

/** Vue principale : affiche l'état du Modèle et transmet au contrôleur les touches de la télécommande. */
class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as JeedomTvApp).controller
    }
}
