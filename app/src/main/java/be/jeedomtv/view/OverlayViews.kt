package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Overlay
import be.jeedomtv.model.TileType

/** Fond du panneau : semi-transparent, la vidéo reste devinable derrière. */
/** Presque opaque : l'interface de l'application vidéo (guide, menus) ne doit pas se lire au travers. */
private val PanelBackground = JeedomTvColors.Background.copy(alpha = 0.95f)

/** Bandeau `notify` par-dessus la vidéo (fenêtre ni focusable ni tactile). */
@Composable
fun OverlayNoticeView(state: AppState) {
    val notice = state.overlay as? Overlay.Notice ?: return
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        BannerView(notice.banner, Modifier.padding(8.dp))
    }
}

/**
 * Panneau `show` par-dessus la vidéo : onglets, grille compacte de la page, réglage ou
 * confirmation à la place de la grille, bandeau d'un `notify` et rappel des touches.
 * Les touches passent par le contrôleur, comme sur l'écran des pages.
 */
@Composable
fun OverlayPanelView(state: AppState) {
    if (state.overlay !is Overlay.Panel) return
    Column(
        Modifier
            .fillMaxSize()
            .background(PanelBackground, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(horizontal = 32.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val listState = rememberLazyListState()
            LaunchedEffect(state.pageIndex) {
                if (state.pages.isNotEmpty()) listState.animateScrollToItem(state.pageIndex)
            }
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(state.pages, key = { index, page -> "$index:${page.id}" }) { index, page ->
                    PageTab(page, selected = index == state.pageIndex)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text("Jeedom TV", color = JeedomTvColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        state.banner?.let { banner ->
            Text(
                listOf(banner.title, banner.message).filter { it.isNotBlank() }.joinToString(" · "),
                color = JeedomTvColors.Text,
                fontSize = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JeedomTvColors.SurfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val page = state.currentPage
            val adjust = state.adjust
            val adjustTile = state.adjustTile
            val confirm = state.confirm
            when {
                confirm != null -> ConfirmDialog(confirm)
                adjust != null && adjustTile != null -> AdjustPanel(adjustTile, adjust, compact = true)
                page == null || page.tiles.isEmpty() ->
                    Text("Aucune tuile sur cette page", color = JeedomTvColors.TextMuted, fontSize = 22.sp)
                else -> TileGrid(page, state, visibleRows = 2)
            }
        }
        HelpBanner(panelHelpText(state))
    }
}

/** Rappel des touches du panneau : Retour ferme, Menu ouvre l'application complète. */
fun panelHelpText(state: AppState): String {
    if (state.confirm != null || state.adjust != null) return helpText(state)
    val action = when (state.focusedTile?.type) {
        TileType.Switch -> "OK : allumer / éteindre"
        TileType.Scene -> "OK : lancer"
        TileType.Shutter, TileType.Slider -> "OK : régler"
        TileType.Info, null -> null
    }
    return listOfNotNull(
        "Flèches : choisir",
        action,
        "CH+/CH- : page",
        "Menu : ouvrir Jeedom TV",
        "Retour : fermer",
    ).joinToString(" · ")
}
