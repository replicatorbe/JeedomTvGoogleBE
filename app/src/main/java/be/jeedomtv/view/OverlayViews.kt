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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.AppState
import be.jeedomtv.model.FocusZone
import be.jeedomtv.model.Overlay

/** Fond du panneau presque opaque : l'interface de l'application vidéo (guide, menus) ne doit pas se lire au travers. */
private val PanelBackground = JeedomTvColors.Background.copy(alpha = 0.95f)

/** Bandeau `notify` par-dessus la vidéo (fenêtre ni focusable ni tactile). */
@Composable
fun OverlayNoticeView(state: AppState, top: Boolean) {
    // Seulement les notifications de son bord (haut ou bas) : la fenêtre de l'autre bord, en fondu
    // de sortie, ne dessine jamais la suivante (ni ne lance un second lecteur vidéo).
    val current = (state.overlay as? Overlay.Notice)?.banner?.takeIf { it.corner.isTop == top }
    var last by remember { mutableStateOf(current) }
    if (current != null) last = current
    // Fondu terminé : plus rien n'est dessiné (ni animation, ni image en mémoire).
    LaunchedEffect(current == null) {
        if (current == null) {
            delay(EXIT_FADE_MS + 50L)
            last = null
        }
    }
    val banner = last ?: return
    val alpha by animateFloatAsState(if (current != null) 1f else 0f, tween(EXIT_FADE_MS), label = "sortie")
    // Coin demandé par la notification (`corner`) ; la fenêtre est en haut ou en bas de l'écran.
    val alignment = if (banner.corner.isStart) Alignment.TopStart else Alignment.TopEnd
    // Marges : la carte à ~24 dp des bords ; un peu de place autour pour son ombre portée.
    Box(
        Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }.padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = alignment,
    ) {
        // En fondu de sortie, sans vidéo : le décodeur est rendu aussitôt.
        NotificationView(
            banner,
            videoAllowed = current != null && bannerVideoAllowed(state),
            waiting = if (current != null) state.waitingNotifications else 0,
        )
    }
}

/** Fondu de sortie d'une notification en superposition (la fenêtre est retirée ensuite). */
internal const val EXIT_FADE_MS = 200

/**
 * Panneau `show` par-dessus la vidéo (menu des touches de couleur) : même famille que les cartes.
 * Fond sombre en dégradé (la télé se devine en haut), coins de 24 dp, liseré fin ; onglets en
 * texte et heure, puces d'infos, deux rangées de tuiles entières (défilement au-delà), réglage
 * ou confirmation à la place de la grille, et un rappel des touches court.
 * Les touches passent par le contrôleur, comme sur l'écran des pages.
 */
@Composable
fun OverlayPanelView(state: AppState) {
    if (state.overlay !is Overlay.Panel) return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(PanelGradient)
            .border(1.dp, CardOutline, PanelShape)
            .padding(start = 32.dp, end = 32.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Fermeture pour inactivité imminente : fine barre en haut du panneau (place gardée).
        Box(Modifier.fillMaxWidth().height(3.dp)) { if (state.panelClosing) ClosingBar() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PageTabs(state, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            // Comme sur l'écran des pages : des valeurs peut-être périmées doivent se voir.
            if (state.offline) {
                OfflineIndicator()
                Spacer(Modifier.width(16.dp))
            }
            Text(statusClockText(rememberMinuteTime()), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        if (state.header.isNotEmpty()) InfoHeader(state.header, Modifier.fillMaxWidth().padding(horizontal = 8.dp), compact = true)
        state.banner?.let { banner ->
            val waiting = state.waitingNotifications.takeIf { it > 0 }?.let { "  (+$it)" }.orEmpty()
            PanelLine(listOf(banner.title, banner.message).filter { it.isNotBlank() }.joinToString(" · ") + waiting, Color.White.copy(alpha = 0.08f), maxLines = 2)
        }
        // Ordre refusé, Jeedom injoignable : sans ce message, un interrupteur revenu à son état
        // d'avant ne disait pas pourquoi.
        state.notice?.let { PanelLine(it, Color.White.copy(alpha = 0.08f), maxLines = 1, icon = "mdi:alert-circle-outline", iconColor = ErrorRed) }
        val page = state.currentPage
        val adjust = state.adjust
        val adjustTile = state.adjustTile
        val confirm = state.confirm
        val choiceTile = state.choiceTile
        val choice = state.choice
        // Réglage, choix ou confirmation à la place de la grille, à la même hauteur : rien ne saute.
        // Hauteur minimale seulement : une confirmation plus haute garde ses pilules OK / Retour visibles.
        val modalHeight = PanelTileHeight * 2 + 14.dp + 16.dp
        when {
            confirm != null -> PanelSlot(modalHeight) { ConfirmDialog(confirm) }
            adjust != null && adjustTile != null -> PanelSlot(modalHeight) { AdjustPanel(adjustTile, adjust, compact = true) }
            choice != null && choiceTile != null -> PanelSlot(modalHeight) { ChoicePanel(choiceTile, choice, compact = true) }
            page == null || page.tiles.isEmpty() -> PanelSlot(PanelTileHeight) {
                Text("Aucune tuile sur cette page", color = CardTextMuted, fontSize = 18.sp)
            }
            else -> TileGrid(page, state, visibleRows = 2, fixedTileHeight = PanelTileHeight)
        }
        HelpLine(panelHelpText(state))
    }
}

/** Haut arrondi du panneau ; le bas touche le bord de l'écran. */
private val PanelShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/** Sombre profond : léger en haut (la télé se devine), ~92 % dès le haut de la grille. */
private val PanelGradient = Brush.verticalGradient(
    0f to Color(0x8C10141B),
    0.3f to Color(0xEB10141B),
    1f to Color(0xEB10141B),
)

@Composable
private fun PanelSlot(height: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = height), contentAlignment = Alignment.Center) { content() }
}

/** Ligne de message dans le panneau (bandeau d'un `notify`, erreur). */
@Composable
private fun PanelLine(text: String, background: Color, maxLines: Int, icon: String? = null, iconColor: Color = Color.White) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { MdiIcon(it, iconColor, 20.dp) }
        Text(text, color = Color.White, fontSize = 16.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Fine barre des 10 dernières secondes avant la fermeture du panneau pour inactivité : elle se
 * vide jusqu'à la fermeture ; toute touche l'efface (le contrôleur relance l'attente).
 */
@Composable
private fun ClosingBar(modifier: Modifier = Modifier) {
    val remaining = remember { Animatable(1f) }
    LaunchedEffect(Unit) { remaining.animateTo(0f, tween(PANEL_CLOSING_MS, easing = LinearEasing)) }
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(Color.White.copy(alpha = 0.10f))
        drawRect(SoftBlue, size = size.copy(width = size.width * remaining.value))
    }
}

private const val PANEL_CLOSING_MS = 10_000

/** Rappel des touches du panneau : Retour ferme, Menu ouvre l'application complète. */
fun panelHelpText(state: AppState): String {
    modalHelp(state)?.let { return it }
    if (state.focusZone == FocusZone.Tabs) return TABS_HELP
    return listOfNotNull(
        "◀▶▲▼ naviguer",
        okHelp(state.focusedTile?.type),
        "1-9 tuile",
        pagesHelp(state),
        "Menu ouvrir l'app",
        "Retour fermer",
    ).joinToString(" · ")
}
