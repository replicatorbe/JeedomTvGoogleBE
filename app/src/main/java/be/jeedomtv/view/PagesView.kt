package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.BuildConfig
import be.jeedomtv.controller.formatValue
import be.jeedomtv.model.Adjust
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Page
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileType

/** Rangées de tuiles visibles sans défilement ; au-delà, la grille suit la sélection. */
private const val VISIBLE_ROWS = 3
private val TileSpacing = 12.dp
internal val MinTileHeight = 104.dp

/** Avec le bandeau d'infos : un peu plus bas, le nom de la tuile tient alors sur une ligne. */
private val WithHeaderMinTileHeight = 96.dp

/**
 * Plancher du panneau avec bandeau d'infos : nom sur une ligne, valeur entière. En dessous
 * (panneau avec un message en plus, par exemple), le nom serait coupé à mi-hauteur : la grille
 * défile alors plutôt que d'écraser les tuiles.
 */
internal val CompactMinTileHeight = 92.dp

/**
 * Écran principal : onglets des pages en haut, grille de tuiles en dessous, bandeau d'aide en bas.
 * Page et tuile sélectionnées viennent de l'état ([AppState.pageIndex], [AppState.focusedIndex]) :
 * la grille ne prend jamais le focus Compose, les touches passent par le contrôleur.
 */
@Composable
fun PagesView(state: AppState) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
            Header(state)
            if (state.header.isEmpty()) {
                Spacer(Modifier.height(14.dp))
            } else {
                // Bandeau d'infos sous les onglets ; les espacements se resserrent pour que la
                // grille garde ses trois rangées.
                Spacer(Modifier.height(8.dp))
                InfoHeader(state.header, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val page = state.currentPage
                when {
                    // Démarrage sans Jeedom (TV allumée avant le serveur) : la boucle réessaie.
                    state.pages.isEmpty() && state.revision == null ->
                        EmptyMessage("Jeedom injoignable, nouvel essai en cours…")
                    state.pages.isEmpty() -> EmptyMessage("Aucune page configurée pour cette TV dans Jeedom")
                    page == null || page.tiles.isEmpty() -> EmptyMessage("Aucune tuile sur cette page")
                    else -> TileGrid(
                        page,
                        state,
                        minTileHeight = if (state.header.isEmpty()) MinTileHeight else WithHeaderMinTileHeight,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HelpBanner(helpText(state))
        }

        // Voile sur la grille : le panneau de réglage ou la confirmation ressort nettement.
        val choiceTile = state.choiceTile
        val choice = state.choice
        if ((state.adjustTile != null && state.adjust != null) || state.confirm != null || (choiceTile != null && choice != null)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        }
        state.adjustTile?.let { tile ->
            state.adjust?.let { adjust -> AdjustPanel(tile, adjust, Modifier.align(Alignment.Center)) }
        }
        if (choiceTile != null && choice != null && state.confirm == null) {
            ChoicePanel(choiceTile, choice, Modifier.align(Alignment.Center))
        }
        state.confirm?.let { ConfirmDialog(it, Modifier.align(Alignment.Center)) }
        state.notice?.let { Notice(it, Modifier.align(Alignment.TopCenter).padding(top = 96.dp)) }
    }
}

/** Onglets des pages (CH+ / CH-) et, à droite, le nom de la TV et l'indicateur hors ligne. */
@Composable
private fun Header(state: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val listState = rememberLazyListState()
        LaunchedEffect(state.pageIndex) {
            if (state.pages.isNotEmpty()) listState.animateScrollToItem(state.pageIndex)
        }
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(state.pages, key = { index, page -> "$index:${page.id}" }) { index, page ->
                PageTab(page, selected = index == state.pageIndex)
            }
        }
        Spacer(Modifier.width(24.dp))
        if (state.offline) {
            OfflineIndicator()
            Spacer(Modifier.width(20.dp))
        }
        state.tvName?.let { Text(it, color = JeedomTvColors.TextMuted, fontSize = 16.sp, maxLines = 1) }
    }
}

/** Jeedom ne répond plus : les valeurs affichées peuvent être périmées. */
@Composable
internal fun OfflineIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).background(JeedomTvColors.Error, CircleShape))
        Text("hors ligne", color = JeedomTvColors.Error, fontSize = 18.sp)
    }
}

@Composable
internal fun PageTab(page: Page, selected: Boolean) {
    Text(
        page.name,
        color = if (selected) JeedomTvColors.OnAccent else JeedomTvColors.TextMuted,
        fontSize = 20.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .background(
                if (selected) JeedomTvColors.Accent else JeedomTvColors.Surface,
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

/** Grille de la page ; [visibleRows] rangées tiennent dans la hauteur disponible (panneau : moins). */
@Composable
internal fun TileGrid(
    page: Page,
    state: AppState,
    visibleRows: Int = VISIBLE_ROWS,
    minTileHeight: Dp = MinTileHeight,
) {
    val gridState = rememberLazyGridState()
    // Fait défiler la grille pour garder la tuile sélectionnée visible.
    LaunchedEffect(page.id, state.focusedIndex) {
        val info = gridState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == state.focusedIndex }
        val fullyVisible = item != null &&
            item.offset.y >= info.viewportStartOffset &&
            item.offset.y + item.size.height <= info.viewportEndOffset
        if (!fullyVisible) gridState.animateScrollToItem(state.focusedIndex)
    }
    // Hauteur calculée pour que trois rangées tiennent à l'écran, quelle que soit la densité de la TV.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileHeight = ((maxHeight - TileSpacing * (visibleRows - 1)) / visibleRows)
            .coerceAtLeast(minTileHeight)
        LazyVerticalGrid(
            columns = GridCells.Fixed(AppState.GRID_COLUMNS),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(TileSpacing),
            verticalArrangement = Arrangement.spacedBy(TileSpacing),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Ids opaques et supposés uniques : la position dans la clé évite tout plantage sur un doublon.
            itemsIndexed(page.tiles, key = { index, tile -> "$index:${tile.id}" }) { index, tile ->
                TileView(
                    tile = tile,
                    number = index + 1,
                    focused = index == state.focusedIndex,
                    flashing = tile.id == state.flashTileId,
                    modifier = Modifier.height(tileHeight),
                )
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = JeedomTvColors.TextMuted, fontSize = 28.sp, textAlign = TextAlign.Center)
    }
}

/**
 * Mode réglage : valeur en attente et jauge (curseur, volet avec position), ou ordres directs du volet.
 * [compact] : version resserrée pour le panneau en superposition.
 */
@Composable
internal fun AdjustPanel(tile: Tile, adjust: Adjust, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier
            .width(720.dp)
            .background(JeedomTvColors.Overlay, RoundedCornerShape(16.dp))
            .padding(horizontal = 40.dp, vertical = if (compact) 12.dp else 32.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TileIconView(tile.icon, JeedomTvColors.Accent, Modifier.size(44.dp))
            Text(tile.name, color = JeedomTvColors.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
        val pending = adjust.pending
        val min = tile.min
        val max = tile.max
        if (pending != null && min != null && max != null) {
            Text(
                formatValue(pending, tile.displayUnit),
                color = JeedomTvColors.Accent,
                fontSize = if (compact) 36.sp else 56.sp,
                fontWeight = FontWeight.Bold,
            )
            val span = (max - min).toFloat()
            Gauge(
                fraction = ((pending - min) / span).toFloat(),
                currentFraction = tile.numericValue?.let { ((it - min) / span).toFloat() },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatValue(min, tile.displayUnit), color = JeedomTvColors.TextMuted, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text("Actuel : ${tileValueText(tile)}", color = JeedomTvColors.TextMuted, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(formatValue(max, tile.displayUnit), color = JeedomTvColors.TextMuted, fontSize = 18.sp)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                ShutterOrder("▲", "Monter")
                ShutterOrder("■", "Stop (OK)")
                ShutterOrder("▼", "Descendre")
            }
            Text("Volet sans retour de position", color = JeedomTvColors.TextMuted, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ShutterOrder(symbol: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(symbol, color = JeedomTvColors.Accent, fontSize = 48.sp)
        Text(label, color = JeedomTvColors.Text, fontSize = 18.sp)
    }
}

/** Message temporaire (ordre refusé, Jeedom injoignable). */
@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = JeedomTvColors.Text,
        fontSize = 22.sp,
        modifier = modifier
            .background(JeedomTvColors.Error.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/** Rappel des touches : toute la largeur lui revient (la version est sur l'écran de configuration). */
@Composable
internal fun HelpBanner(text: String) {
    Text(
        text,
        color = JeedomTvColors.TextMuted,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(JeedomTvColors.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** « Jeedom TV 0.7.0 », affiché sur l'écran de configuration. */
internal val appVersionLabel: String
    get() = "Jeedom TV ${BuildConfig.VERSION_NAME}"

/** Effet de OK sur la tuile sélectionnée, pour le rappel des touches ; null s'il n'y en a pas. */
internal fun okHelp(type: TileType?): String? = when (type) {
    TileType.Switch -> "OK : allumer / éteindre"
    TileType.Scene -> "OK : lancer"
    TileType.Button -> "OK : activer"
    TileType.Select -> "OK : changer"
    TileType.Shutter, TileType.Slider -> "OK : régler"
    TileType.Info, null -> null
}

/** Rappel des touches du contexte courant. */
fun helpText(state: AppState): String {
    if (state.confirm != null) return "OK : confirmer · Retour : annuler"
    if (state.choice != null && state.choiceTile != null) return "◀ ▶ : choisir · OK : envoyer · Retour : annuler"
    val tile = state.adjustTile
    val adjust = state.adjust
    if (tile != null && adjust != null) {
        return if (adjust.pending == null) {
            "▲ : monter · ▼ : descendre · OK : stop · Retour : sortir"
        } else {
            val shutter = if (tile.type == TileType.Shutter) " · CH+ / CH- : monter / descendre" else ""
            "▲ ▼ : régler · ◀ ▶ : min / max · OK : envoyer · Retour : annuler$shutter"
        }
    }
    val action = okHelp(state.focusedTile?.type)
    return listOfNotNull(
        "Flèches : choisir",
        action,
        "1-9 : tuile",
        "CH+/CH- : page",
        "Menu : réglages",
        "Retour : quitter",
    ).joinToString(" · ")
}
