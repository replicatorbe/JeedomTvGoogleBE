package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import be.jeedomtv.controller.AppController
import be.jeedomtv.model.Banner
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
            // Carte image / vidéo en haut à droite ; message texte en haut, au centre.
            val position = if (banner.isMediaCard(videoAllowed)) {
                Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 24.dp)
            } else {
                Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
            }
            NotificationView(banner, videoAllowed, position)
        }
        // Question de Jeedom dans l'application (en superposition, c'est une fenêtre à part).
        state.question?.takeIf { !it.inOverlay }?.let { question ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            QuestionDialog(question, Modifier.align(Alignment.Center))
        }
    }
}

/** Message de Jeedom (ordre `notify`), au-dessus de tout écran pendant quelques secondes. */
@Composable
internal fun BannerView(banner: Banner, modifier: Modifier = Modifier) {
    // Même rayon et même liseré discret que la carte image / vidéo : plus de bordure épaisse.
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .widthIn(max = 960.dp)
            .background(JeedomTvColors.Overlay, shape)
            .border(1.dp, Color.White.copy(alpha = 0.15f), shape)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // L'icône MDI de la notification, s'il y en a une (les images et vidéos ont leur carte).
        banner.icon?.let { icon ->
            MdiIcon(icon, color = banner.iconColor?.let { Color(it) } ?: JeedomTvColors.Accent, size = 48.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (banner.title.isNotBlank()) {
                Text(banner.title, color = JeedomTvColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(banner.message, color = JeedomTvColors.Text, fontSize = 28.sp, lineHeight = 34.sp)
        }
    }
}

/**
 * Un seul flux vidéo à la fois : la TV n'a que deux décodeurs, dont un pour la télé elle-même.
 * Une question avec vidéo garde le décodeur ; le bandeau montre alors son image.
 */
fun bannerVideoAllowed(state: be.jeedomtv.model.AppState): Boolean = state.question?.video == null

