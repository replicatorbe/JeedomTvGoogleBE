package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.controller.formatValue
import be.jeedomtv.model.Adjust
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Page
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileType

private val TileHeight = 180.dp

/**
 * Écran principal : onglets des pages en haut, grille de tuiles en dessous, bandeau d'aide en bas.
 * Page et tuile sélectionnées viennent de l'état ([AppState.pageIndex], [AppState.focusedIndex]) :
 * la grille ne prend jamais le focus Compose, les touches passent par le contrôleur.
 */
@Composable
fun PagesView(state: AppState) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
            Header(state)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val page = state.currentPage
                when {
                    state.pages.isEmpty() -> EmptyMessage("Aucune page configurée pour cette TV dans Jeedom")
                    page == null || page.tiles.isEmpty() -> EmptyMessage("Aucune tuile sur cette page")
                    else -> TileGrid(page, state)
                }
            }
            Spacer(Modifier.height(16.dp))
            HelpBanner(helpText(state))
        }

        state.adjustTile?.let { tile ->
            state.adjust?.let { adjust -> AdjustPanel(tile, adjust, Modifier.align(Alignment.Center)) }
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
            itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                PageTab(page, selected = index == state.pageIndex)
            }
        }
        Spacer(Modifier.width(24.dp))
        if (state.offline) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).background(JeedomTvColors.Error, CircleShape))
                Text("hors ligne", color = JeedomTvColors.Error, fontSize = 18.sp)
            }
            Spacer(Modifier.width(20.dp))
        }
        state.tvName?.let { Text(it, color = JeedomTvColors.TextMuted, fontSize = 18.sp) }
    }
}

@Composable
private fun PageTab(page: Page, selected: Boolean) {
    Text(
        page.name,
        color = if (selected) JeedomTvColors.OnAccent else JeedomTvColors.TextMuted,
        fontSize = 24.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier
            .background(
                if (selected) JeedomTvColors.Accent else JeedomTvColors.Surface,
                RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

@Composable
private fun TileGrid(page: Page, state: AppState) {
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
    LazyVerticalGrid(
        columns = GridCells.Fixed(AppState.GRID_COLUMNS),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(page.tiles, key = { _, tile -> tile.id }) { index, tile ->
            TileView(
                tile = tile,
                number = index + 1,
                focused = index == state.focusedIndex,
                flashing = tile.id == state.flashTileId,
                modifier = Modifier.height(TileHeight),
            )
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = JeedomTvColors.TextMuted, fontSize = 28.sp, textAlign = TextAlign.Center)
    }
}

/** Mode réglage : valeur en attente et jauge (curseur, volet avec position), ou ordres directs du volet. */
@Composable
private fun AdjustPanel(tile: Tile, adjust: Adjust, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(720.dp)
            .background(JeedomTvColors.Overlay, RoundedCornerShape(16.dp))
            .padding(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
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
                formatValue(pending, tile.unit),
                color = JeedomTvColors.Accent,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
            )
            val span = (max - min).toFloat()
            Gauge(
                fraction = ((pending - min) / span).toFloat(),
                currentFraction = tile.numericValue?.let { ((it - min) / span).toFloat() },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatValue(min, tile.unit), color = JeedomTvColors.TextMuted, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text("Actuel : ${tileValueText(tile)}", color = JeedomTvColors.TextMuted, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(formatValue(max, tile.unit), color = JeedomTvColors.TextMuted, fontSize = 18.sp)
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

@Composable
private fun HelpBanner(text: String) {
    Text(
        text,
        color = JeedomTvColors.TextMuted,
        fontSize = 18.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(JeedomTvColors.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

/** Rappel des touches du contexte courant. */
fun helpText(state: AppState): String {
    if (state.confirm != null) return "OK : confirmer · Retour : annuler"
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
    val action = when (state.focusedTile?.type) {
        TileType.Switch -> "OK : allumer / éteindre"
        TileType.Scene -> "OK : lancer"
        TileType.Shutter, TileType.Slider -> "OK : régler"
        TileType.Info, null -> null
    }
    return listOfNotNull(
        "Flèches : choisir",
        action,
        "1-9 : tuile N",
        "CH+ / CH- : page",
        "Menu : configuration",
        "Retour : quitter",
    ).joinToString(" · ")
}
