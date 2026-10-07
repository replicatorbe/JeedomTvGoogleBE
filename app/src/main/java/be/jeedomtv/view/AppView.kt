package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.jeedomtv.controller.AppController
import be.jeedomtv.model.AppState
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
        state.banner?.let { banner ->
            val videoAllowed = bannerVideoAllowed(state)
            // Toutes les notifications sont des cartes, dans leur coin (en haut à droite par défaut).
            val corner = banner.corner
            val alignment = when {
                corner.isTop && corner.isStart -> Alignment.TopStart
                corner.isTop -> Alignment.TopEnd
                corner.isStart -> Alignment.BottomStart
                else -> Alignment.BottomEnd
            }
            NotificationView(banner, videoAllowed, Modifier.align(alignment).padding(24.dp))
        }
        // Question de Jeedom dans l'application (en superposition, c'est une fenêtre à part).
        state.question?.takeIf { !it.inOverlay }?.let { question ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            QuestionDialog(question, Modifier.align(Alignment.Center))
        }
    }
}

/**
 * Un seul flux vidéo à la fois : la TV n'a que deux décodeurs, dont un pour la télé elle-même.
 * Une question avec vidéo garde le décodeur ; le bandeau montre alors son image.
 */
fun bannerVideoAllowed(state: AppState): Boolean = state.question?.video == null

