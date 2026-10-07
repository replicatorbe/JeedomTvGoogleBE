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
        state.banner?.let {
            BannerView(it, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), videoAllowed = bannerVideoAllowed(state))
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
internal fun BannerView(banner: Banner, modifier: Modifier = Modifier, videoAllowed: Boolean = true) {
    val shape = RoundedCornerShape(12.dp)
    val thumbnail by rememberDecodedImage(banner.imageBytes, maxWidth = THUMBNAIL_MAX_PX, maxHeight = THUMBNAIL_MAX_PX)
    val video = banner.video?.takeIf { videoAllowed }
    Row(
        modifier
            .widthIn(max = 960.dp)
            .background(JeedomTvColors.Overlay, shape)
            .border(2.dp, JeedomTvColors.Accent, shape)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // Vidéo en direct (caméra) dans une petite fenêtre ; l'image sert d'attente et de repli.
            video != null -> LiveVideo(
                video,
                placeholder = thumbnail,
                modifier = Modifier.width(BANNER_VIDEO_WIDTH).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
            )
            // Sans image ni vidéo : l'icône MDI de la notification, s'il y en a une.
            thumbnail == null && banner.image == null && banner.icon != null -> MdiIcon(
                banner.icon,
                color = banner.iconColor?.let { Color(it) } ?: JeedomTvColors.Accent,
                size = 48.dp,
            )
        }
        // Vignette de l'image jointe (photo du portier…), à gauche du texte.
        thumbnail?.takeIf { video == null }?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(120.dp)
                    .aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (banner.title.isNotBlank()) {
                Text(banner.title, color = JeedomTvColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(banner.message, color = JeedomTvColors.Text, fontSize = 28.sp, lineHeight = 34.sp)
        }
    }
}

/** Largeur de la petite fenêtre vidéo d'un bandeau (16:9). */
private val BANNER_VIDEO_WIDTH = 320.dp

/**
 * Un seul flux vidéo à la fois : la TV n'a que deux décodeurs, dont un pour la télé elle-même.
 * Une question avec vidéo garde le décodeur ; le bandeau montre alors son image.
 */
fun bannerVideoAllowed(state: be.jeedomtv.model.AppState): Boolean = state.question?.video == null

/** Plus grand côté visé au décodage d'une vignette de bandeau. */
private const val THUMBNAIL_MAX_PX = 480
