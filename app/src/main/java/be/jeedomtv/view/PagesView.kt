package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
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
import be.jeedomtv.model.FocusZone
import be.jeedomtv.model.Page
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileType

/** Rangées de tuiles visibles sans défilement ; au-delà, la grille suit la sélection. */
private const val VISIBLE_ROWS = 3
private val TileSpacing = 14.dp

/** Marge intérieure de la grille, pour la tuile agrandie au focus et son ombre. */
private val GridPadding = 8.dp

/** Hauteur des tuiles dans le panneau : deux rangées entières sous les onglets et les puces. */
internal val PanelTileHeight = 100.dp
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
    // Tableau des trains : écran plein, sans onglets ni tuiles.
    state.currentPage?.takeIf { it.isBoard }?.let { page ->
        BoardView(page, state)
        return
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 18.dp)) {
            Header(state)
            if (state.header.isEmpty()) {
                Spacer(Modifier.height(10.dp))
            } else {
                // Puces d'infos discrètes sous les onglets.
                Spacer(Modifier.height(8.dp))
                InfoHeader(state.header, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                Spacer(Modifier.height(6.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val page = state.currentPage
                when {
                    // Démarrage sans Jeedom (TV allumée avant le serveur) : la boucle réessaie.
                    state.pages.isEmpty() && state.revision == null ->
                        EmptyMessage("Jeedom injoignable, nouvel essai en cours…")
                    state.pages.isEmpty() -> EmptyMessage("Aucune page configurée pour cette TV dans Jeedom")
                    // Toutes les pages sont cachées : elles ne s'ouvrent que par Jeedom ou une touche de couleur.
                    page == null -> EmptyMessage("Aucune page visible pour cette TV dans Jeedom")
                    page.tiles.isEmpty() -> EmptyMessage("Aucune tuile sur cette page")
                    else -> TileGrid(
                        page,
                        state,
                        minTileHeight = if (state.header.isEmpty()) MinTileHeight else WithHeaderMinTileHeight,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HelpLine(helpText(state))
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

/** Onglets des pages et, à droite, l'indicateur hors ligne puis la barre d'état (ou le nom de la TV). */
@Composable
private fun Header(state: AppState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PageTabs(state, Modifier.weight(1f))
        Spacer(Modifier.width(24.dp))
        if (state.offline) {
            OfflineIndicator()
            Spacer(Modifier.width(20.dp))
        }
        // La barre d'état prend la place du nom de la TV : en bas, elle masquerait l'aide.
        val status = state.status?.takeIf { it.visible }
        if (status != null) {
            // Au plus ~45 % de la largeur : les onglets gardent leur place, le reste est coupé.
            val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.45f
            Box(Modifier.widthIn(max = maxWidth).clipToBounds()) { StatusBarView(status, unreachable = !state.jeedomReachable) }
        } else {
            state.tvName?.let { Text(it, color = JeedomTvColors.TextMuted, fontSize = 16.sp, maxLines = 1) }
        }
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

/**
 * Onglets des pages, en texte : l'onglet actif en blanc gras, souligné de la couleur d'accent ;
 * les autres en blanc à 60 %. Focus dans les onglets (flèches) : l'onglet actif prend une pilule
 * claire cerclée de blanc. Icône MDI de la page devant son nom quand elle se déduit.
 */
@Composable
internal fun PageTabs(state: AppState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // Sans les pages cachées : seuls `show` et les touches de couleur les ouvrent.
    val tabs = state.tabPages
    LaunchedEffect(state.pageIndex, tabs.size) {
        val position = tabs.indexOfFirst { it.index == state.pageIndex }
        if (position >= 0) listState.animateScrollToItem(position)
    }
    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs, key = { (index, page) -> "$index:${page.id}" }) { (index, page) ->
            PageTab(page, selected = index == state.pageIndex, targeted = index == state.pageIndex && state.focusZone == FocusZone.Tabs)
        }
    }
}

@Composable
internal fun PageTab(page: Page, selected: Boolean, targeted: Boolean = false) {
    val shape = RoundedCornerShape(50)
    val color = if (selected) Color.White else Color.White.copy(alpha = 0.6f)
    Column(
        Modifier
            .background(if (targeted) Color.White.copy(alpha = 0.14f) else Color.Transparent, shape)
            .border(2.dp, if (targeted) Color.White else Color.Transparent, shape)
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pageMdiIcon(page)?.let { MdiIcon(it, color, 20.dp) }
            Text(
                page.name,
                color = color,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
        // Soulignement de l'onglet actif (la place est gardée : la rangée ne bouge pas).
        Box(
            Modifier
                .padding(top = 4.dp)
                .width(28.dp)
                .height(3.dp)
                .background(if (selected) SoftBlue else Color.Transparent, RoundedCornerShape(2.dp)),
        )
    }
}

/** Grille de la page ; [visibleRows] rangées tiennent dans la hauteur disponible (panneau : moins). */
@Composable
internal fun TileGrid(
    page: Page,
    state: AppState,
    visibleRows: Int = VISIBLE_ROWS,
    minTileHeight: Dp = MinTileHeight,
    /** Hauteur imposée des tuiles (panneau) : la grille montre exactement [visibleRows] rangées entières. */
    fixedTileHeight: Dp? = null,
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
    // Panneau : hauteur imposée, rangées entières (jamais coupées) ; écran des pages : hauteur
    // calculée pour que trois rangées tiennent, quelle que soit la densité de la TV.
    val rows = ((page.tiles.size + AppState.GRID_COLUMNS - 1) / AppState.GRID_COLUMNS).coerceIn(1, visibleRows)
    val outer = if (fixedTileHeight != null) {
        Modifier.fillMaxWidth().height(fixedTileHeight * rows + TileSpacing * (rows - 1) + GridPadding * 2)
    } else {
        Modifier.fillMaxSize()
    }
    BoxWithConstraints(outer) {
        val tileHeight = fixedTileHeight
            ?: ((maxHeight - GridPadding * 2 - TileSpacing * (visibleRows - 1)) / visibleRows).coerceAtLeast(minTileHeight)
        LazyVerticalGrid(
            columns = GridCells.Fixed(AppState.GRID_COLUMNS),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(TileSpacing),
            verticalArrangement = Arrangement.spacedBy(TileSpacing),
            // Marge autour des tuiles : l'agrandissement et l'ombre de la tuile sélectionnée ne sont pas rognés.
            contentPadding = PaddingValues(GridPadding),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Ids opaques et supposés uniques : la position dans la clé évite tout plantage sur un doublon.
            itemsIndexed(page.tiles, key = { index, tile -> "$index:${tile.id}" }) { index, tile ->
                TileView(
                    tile = tile,
                    number = index + 1,
                    // Focus dans les onglets : aucune tuile n'est mise en avant.
                    focused = state.focusZone == FocusZone.Tiles && index == state.focusedIndex,
                    flashing = tile.id == state.flashTileId,
                    confirmed = tile.id == state.confirmedTileId,
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
            .jeedomCard()
            .padding(horizontal = 40.dp, vertical = if (compact) 12.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconPill(tileMdiIcon(tile), SoftBlue, if (compact) 36.dp else 44.dp)
            Text(tile.name, color = Color.White, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.SemiBold)
        }
        val pending = adjust.pending
        val min = tile.min
        val max = tile.max
        if (pending != null && min != null && max != null) {
            Text(
                formatValue(pending, tile.displayUnit),
                color = SoftBlue,
                fontSize = if (compact) 36.sp else 56.sp,
                fontWeight = FontWeight.Bold,
            )
            val span = (max - min).toFloat()
            Gauge(
                fraction = ((pending - min) / span).toFloat(),
                currentFraction = tile.numericValue?.let { ((it - min) / span).toFloat() },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatValue(min, tile.displayUnit), color = CardTextMuted, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text("Actuel : ${tileValueText(tile)}", color = CardTextSecondary, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text(formatValue(max, tile.displayUnit), color = CardTextMuted, fontSize = 16.sp)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                ShutterOrder("▲", "Monter")
                ShutterOrder("■", "Stop (OK)")
                ShutterOrder("▼", "Descendre")
            }
            Text("Volet sans retour de position", color = CardTextMuted, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ShutterOrder(symbol: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(56.dp).background(Color.White.copy(alpha = 0.08f), CircleShape).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, color = Color.White, fontSize = 24.sp)
        }
        Text(label, color = CardTextSecondary, fontSize = 16.sp)
    }
}

/** Message temporaire (ordre refusé, Jeedom injoignable). */
@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    // Même famille que les cartes : fond sombre, pastille d'erreur rouge, texte blanc.
    Row(
        modifier
            .jeedomCard()
            .padding(start = 14.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconPill("mdi:alert-circle-outline", ErrorRed, 36.dp)
        Text(text, color = Color.White, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Rouge des erreurs, assorti aux questions (« Réponse refusée »). */
internal val ErrorRed = Color(0xFFF28B82)

/** Rappel des touches : une ligne courte, en petit gris, centrée, sans fond. */
@Composable
internal fun HelpLine(text: String) {
    Text(
        text,
        color = CardTextMuted,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** « Jeedom TV 0.7.0 », affiché sur l'écran de configuration. */
internal val appVersionLabel: String
    get() = "Jeedom TV ${BuildConfig.VERSION_NAME}"

/** Effet de OK sur la tuile sélectionnée, pour le rappel des touches ; null s'il n'y en a pas. */
internal fun okHelp(type: TileType?): String? = when (type) {
    TileType.Switch -> "OK allumer / éteindre"
    TileType.Scene -> "OK lancer"
    TileType.Button -> "OK activer"
    TileType.Select -> "OK choisir"
    TileType.Shutter, TileType.Slider -> "OK régler"
    TileType.Info, null -> null
}

/** Aide quand le focus est dans les onglets (écran des pages et panneau). */
internal const val TABS_HELP = "◀▶ changer de page · ▼ / OK tuiles · Retour tuiles"

/** « ▲ pages » sur la première rangée de tuiles (ou une page vide), d'où ▲ monte aux onglets. */
internal fun pagesHelp(state: AppState): String? {
    val tiles = state.currentPage?.tiles.orEmpty()
    return if (tiles.isEmpty() || state.focusedIndex < AppState.GRID_COLUMNS) "▲ pages" else null
}

/** Rappel des touches d'un mode qui garde la main (confirmation, choix, réglage) ; null sinon. */
internal fun modalHelp(state: AppState): String? {
    if (state.confirm != null) return "OK confirmer · Retour annuler"
    if (state.choice != null && state.choiceTile != null) return "◀▶ choisir · OK envoyer · Retour annuler"
    val tile = state.adjustTile
    val adjust = state.adjust
    if (tile != null && adjust != null) {
        return when {
            adjust.pending == null -> "▲ monter · ▼ descendre · OK stop · Retour sortir"
            tile.type == TileType.Shutter -> "▲▼ régler · ◀▶ fermé / ouvert · OK envoyer · Retour annuler"
            else -> "▲▼ régler · ◀▶ min / max · OK envoyer · Retour annuler"
        }
    }
    return null
}

/** Rappel des touches de l'écran des pages. */
fun helpText(state: AppState): String {
    modalHelp(state)?.let { return it }
    if (state.focusZone == FocusZone.Tabs) return TABS_HELP
    return listOfNotNull(
        "◀▶▲▼ naviguer",
        okHelp(state.focusedTile?.type),
        "1-9 tuile",
        pagesHelp(state),
        "Menu réglages",
        "Retour quitter",
    ).joinToString(" · ")
}
