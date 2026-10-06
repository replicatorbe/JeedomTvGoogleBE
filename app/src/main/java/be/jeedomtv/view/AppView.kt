package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        state.banner?.let { BannerView(it, Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) }
    }
}

/** Message de Jeedom (ordre `notify`), au-dessus de tout écran pendant quelques secondes. */
@Composable
internal fun BannerView(banner: Banner, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .widthIn(max = 960.dp)
            .background(JeedomTvColors.Overlay, shape)
            .border(2.dp, JeedomTvColors.Accent, shape)
            .padding(horizontal = 32.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (banner.title.isNotBlank()) {
            Text(banner.title, color = JeedomTvColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(banner.message, color = JeedomTvColors.Text, fontSize = 28.sp)
    }
}
