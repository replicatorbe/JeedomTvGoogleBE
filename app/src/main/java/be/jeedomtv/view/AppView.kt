package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.jeedomtv.controller.AppController
import be.jeedomtv.model.Screen

/** Vue racine : observe l'état du modèle (via le contrôleur) et affiche l'écran courant. */
@Composable
fun AppView(controller: AppController) {
    val state by controller.state.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(JeedomTvColors.Background)
    ) {
        when (state.screen) {
            Screen.Setup -> SetupView(state, controller)
            Screen.Loading -> LoadingView()
            Screen.Pages -> PagesView(state)
        }
    }
}
