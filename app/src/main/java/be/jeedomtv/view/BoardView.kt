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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Board
import be.jeedomtv.model.BoardSection
import be.jeedomtv.model.Page
import be.jeedomtv.model.Train
import be.jeedomtv.model.TrainStatus

/*
 * Tableau des trains (page `board`) : écran plein façon tableau de gare SNCB, fond bleu nuit,
 * texte blanc et jaune, lisible de loin. Aucune action : Retour le ferme (contrôleur).
 * La hauteur des lignes suit le nombre de trains : peu de trains, grandes lettres.
 */

/** Couleurs du tableau, celles d'un tableau de départs belge. */
internal object BoardColors {
    val Background = Color(0xFF0A1D44)
    val Stripe = Color(0xFF0F2652)
    val NextRow = Color(0xFF1B3A73)
    val Yellow = Color(0xFFFFD400)
    val Text = Color.White
    val Muted = Color.White.copy(alpha = 0.62f)
    val OnTime = Color(0xFF5FD873)
    val Slight = Color(0xFFFFC566)
    val Delayed = Color(0xFFFF7A45)
    val Canceled = Color(0xFFFF5252)
    val Alert = Color(0xFFFFB300)
    val OnAlert = Color(0xFF0A1D44)
}

/**
 * Hauteur d'une ligne de train : bornée pour rester lisible de loin. Sous le plancher, le tableau
 * montre moins de trains par section plutôt que des lettres trop petites (voir [boardFit]).
 */
private val MinRowHeight = 26.dp
private val MaxRowHeight = 46.dp

/** Marges de l'écran (zone sûre des téléviseurs). */
private val BoardPaddingX = 40.dp
private val BoardPaddingY = 22.dp

/** Ligne du bandeau d'un `notify` par-dessus la télé (et son écart) : place retirée aux trains. */
private val BannerLineHeight = 42.dp

/**
 * Le tableau [page] en plein écran. [inOverlay] : par-dessus une autre application (panneau
 * `show`) ; le bandeau d'un `notify` s'y affiche alors en une ligne, et la fine barre annonce
 * la fermeture pour inactivité.
 */
@Composable
fun BoardView(page: Page, state: AppState, inOverlay: Boolean = false) {
    val board = page.board ?: Board()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(BoardColors.Background)
            .padding(horizontal = BoardPaddingX, vertical = BoardPaddingY),
    ) {
        val banner = state.banner?.takeIf { inOverlay }
        val (row, trainsPerSection) = boardFit(board, maxHeight, reserved = if (banner != null) BannerLineHeight else 0.dp)
        val font = with(LocalDensity.current) { (row * 0.56f).toSp() }
        val small = with(LocalDensity.current) { (row * 0.42f).toSp() }
        Column(Modifier.fillMaxSize()) {
            if (inOverlay && state.panelClosing) ClosingBar() else Spacer(Modifier.height(3.dp))
            if (board.sections.isEmpty()) {
                // Le plugin n'a encore rien calculé : le titre de la page et l'heure seulement.
                SectionTitle(page.name, "", font, endReserve = row * 4)
                Text("Aucun train", color = BoardColors.Muted, fontSize = font, modifier = Modifier.padding(top = 12.dp))
            }
            board.sections.forEachIndexed { index, section ->
                if (index > 0) Spacer(Modifier.height(row * SECTION_GAP))
                // La première ligne de titre laisse la place de l'horloge, en haut à droite.
                SectionTitle(section.title.ifBlank { page.name }, section.day, font, endReserve = if (index == 0) row * 4 else 0.dp)
                Spacer(Modifier.height(4.dp))
                if (section.trains.isEmpty()) {
                    TrainRowBox(row, stripe = false, next = false) {
                        Text("Aucun train", color = BoardColors.Muted, fontSize = font, modifier = Modifier.padding(start = row * 0.8f))
                    }
                }
                boardTrains(section.trains, trainsPerSection).forEachIndexed { trainIndex, train ->
                    TrainRow(train, row, font, small, stripe = trainIndex % 2 == 1)
                }
                section.notes.forEach { NoteLine(it, row, small) }
            }
            Spacer(Modifier.weight(1f))
            banner?.let {
                val waiting = state.waitingNotifications.takeIf { it > 0 }?.let { "  (+$it)" }.orEmpty()
                PanelLine(listOf(it.title, it.message).filter { text -> text.isNotBlank() }.joinToString(" · ") + waiting, Color.White.copy(alpha = 0.12f), maxLines = 1)
                Spacer(Modifier.height(6.dp))
            }
            // « hors ligne » à côté de l'heure de lecture : les deux disent si les trains sont à jour.
            // En haut, il empiétait sur le titre de la première section, à côté de l'horloge.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(boardUpdatedText(board), color = BoardColors.Muted, fontSize = 16.sp)
                if (state.offline) OfflineIndicator()
                Spacer(Modifier.weight(1f))
                Text("Retour fermer", color = BoardColors.Muted, fontSize = 14.sp)
            }
        }
        // Horloge en haut à droite, comme sur le quai.
        Text(
            statusClockText(rememberMinuteTime()),
            color = BoardColors.Text,
            fontSize = font * 1.15f,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp),
        )
    }
}

/** Écart entre deux sections, en hauteurs de ligne. */
private const val SECTION_GAP = 0.5f

/** Mise en page du tableau : hauteur d'une ligne et nombre de trains montrés par section. */
internal data class BoardFit(val row: Dp, val trainsPerSection: Int)

/**
 * Hauteur d'une ligne pour que tout le tableau tienne dans [height] (moins [reserved], la ligne du
 * bandeau d'un `notify`) : titres (1,1 ligne), trains (« Aucun train » compte pour un), notes (0,8),
 * écarts entre sections et une marge. Si les lignes passeraient sous [MinRowHeight], chaque section
 * montre moins de trains (voir [boardTrains] : le prochain à prendre et les suivants d'abord),
 * jusqu'à un seul par section ; les notes (grève, travaux) restent toujours.
 */
internal fun boardFit(board: Board, height: Dp, reserved: Dp = 0.dp): BoardFit {
    val sections = board.sections
    val available = height - reserved - 3.dp - 4.dp * sections.size - 30.dp
    var limit = sections.maxOfOrNull { it.trains.size }?.coerceAtLeast(1) ?: 1
    while (true) {
        val units = sections.sumOf { section ->
            1.1 + section.trains.size.coerceIn(1, limit) + section.notes.size * 0.8
        } + (sections.size - 1).coerceAtLeast(0) * SECTION_GAP + 0.4
        val row = available / units.coerceAtLeast(4.0).toFloat()
        if (row >= MinRowHeight || limit <= 1) return BoardFit(row.coerceIn(MinRowHeight, MaxRowHeight), limit)
        limit--
    }
}

@Composable
private fun SectionTitle(title: String, day: String, font: TextUnit, endReserve: Dp) {
    Row(Modifier.fillMaxWidth().padding(end = endReserve), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = BoardColors.Text,
            fontSize = font * 1.15f,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (day.isNotBlank()) {
            Text("  · $day", color = BoardColors.Yellow, fontSize = font * 1.15f, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun TrainRowBox(height: Dp, stripe: Boolean, next: Boolean, content: @Composable () -> Unit) {
    val background = when {
        next -> BoardColors.NextRow
        stripe -> BoardColors.Stripe
        else -> Color.Transparent
    }
    Box(
        Modifier.fillMaxWidth().height(height).background(background, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** Une ligne : ▶, heure, train, direction (et correspondances), état, voie. */
@Composable
private fun TrainRow(train: Train, row: Dp, font: TextUnit, small: TextUnit, stripe: Boolean) {
    val canceled = train.status == TrainStatus.Canceled
    val strike = if (canceled) TextDecoration.LineThrough else null
    val dim = if (canceled) BoardColors.Muted else BoardColors.Text
    TrainRowBox(row, stripe, train.next) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (train.next) "▶" else "",
                color = BoardColors.Yellow,
                fontSize = font * 0.8f,
                modifier = Modifier.width(row * 0.8f).padding(start = 8.dp),
            )
            BoardCell(train.time, row * 2.1f, font, if (canceled) BoardColors.Muted else BoardColors.Yellow, FontWeight.Bold, strike)
            BoardCell(train.vehicle, row * 2.8f, font * 0.9f, dim, FontWeight.Normal, null)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    train.direction,
                    color = dim,
                    fontSize = font,
                    textDecoration = strike,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                transfersText(train)?.let {
                    Text("  $it", color = BoardColors.Muted, fontSize = small, maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.width(12.dp))
            StatusCell(train, row, font, small)
            PlatformCell(train, row, font)
        }
    }
}

@Composable
private fun BoardCell(text: String, width: Dp, font: TextUnit, color: Color, weight: FontWeight, decoration: TextDecoration?) {
    Text(
        text,
        color = color,
        fontSize = font,
        fontWeight = weight,
        textDecoration = decoration,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width),
    )
}

/** « à l'heure », « +4 min (07:13) » ou « Supprimé », dans la couleur de l'état. */
@Composable
private fun StatusCell(train: Train, row: Dp, font: TextUnit, small: TextUnit) {
    val color = trainStatusColor(train.status)
    Row(Modifier.width(row * 4.3f), verticalAlignment = Alignment.CenterVertically) {
        Text(
            trainStatusText(train),
            color = color,
            fontSize = font,
            fontWeight = if (train.status == TrainStatus.OnTime) FontWeight.Normal else FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
        trainRealText(train)?.let {
            Text("  $it", color = color, fontSize = small, maxLines = 1, softWrap = false)
        }
    }
}

/** « voie 1 » ; une voie inhabituelle ressort sur fond d'alerte. */
@Composable
private fun PlatformCell(train: Train, row: Dp, font: TextUnit) {
    Box(Modifier.width(row * 2.4f), contentAlignment = Alignment.CenterEnd) {
        val text = platformText(train) ?: return@Box
        val changed = train.platformChanged && train.status != TrainStatus.Canceled
        Text(
            text,
            color = if (changed) BoardColors.OnAlert else BoardColors.Text,
            fontSize = font * 0.9f,
            fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .background(if (changed) BoardColors.Alert else Color.Transparent, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
        )
    }
}

/** Perturbation du réseau sous la section. */
@Composable
private fun NoteLine(text: String, row: Dp, small: TextUnit) {
    Row(
        Modifier.fillMaxWidth().height(row * 0.8f).padding(start = row * 0.8f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MdiIcon("mdi:alert", BoardColors.Slight, row * 0.5f)
        Text(text, color = BoardColors.Slight, fontSize = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Trains montrés d'une section, [limit] au plus : à partir du prochain train à prendre (ceux d'avant
 * partent trop tôt pour qu'on les attrape), complétés par ceux d'avant s'il reste de la place.
 * Un seul ▶ par section : un second `next` (hors contrat) est ignoré.
 */
internal fun boardTrains(trains: List<Train>, limit: Int): List<Train> {
    val next = trains.indexOfFirst { it.next }
    val single = if (trains.count { it.next } > 1) {
        trains.mapIndexed { index, train -> if (train.next && index != next) train.copy(next = false) else train }
    } else {
        trains
    }
    val count = limit.coerceAtLeast(1)
    if (single.size <= count) return single
    val start = next.coerceIn(0, single.size - count)
    return single.subList(start, start + count)
}

// --- Textes (purs, testables sans Compose) ------------------------------------------------------

/** État affiché : « Supprimé », « +4 min » (retard connu) ou « à l'heure ». */
fun trainStatusText(train: Train): String = when {
    train.status == TrainStatus.Canceled -> "Supprimé"
    train.delay > 0 -> "+${train.delay} min"
    else -> "à l'heure"
}

/** Heure réelle entre parenthèses quand le train est en retard, sinon null. */
fun trainRealText(train: Train): String? =
    train.real.takeIf { train.status != TrainStatus.Canceled && train.delay > 0 && it.isNotBlank() && it != train.time }
        ?.let { "($it)" }

/** « voie 3 » ; null si la voie est inconnue. */
fun platformText(train: Train): String? = train.platform.takeIf { it.isNotBlank() }?.let { "voie $it" }

/** « 1 corresp. », « 2 corresp. » ; null pour un train direct. */
fun transfersText(train: Train): String? = train.transfers.takeIf { it > 0 }?.let { "$it corresp." }

fun trainStatusColor(status: TrainStatus): Color = when (status) {
    TrainStatus.OnTime -> BoardColors.OnTime
    TrainStatus.Slight -> BoardColors.Slight
    TrainStatus.Delayed -> BoardColors.Delayed
    TrainStatus.Canceled -> BoardColors.Canceled
}

/**
 * Pied du tableau : « vérifié à 07:12 », ou l'intervalle si les trajets n'ont pas été lus à la
 * même minute (« vérifié entre 07:11 et 07:12 ») ; vide si aucune heure n'est connue.
 */
fun boardUpdatedText(board: Board): String {
    val times = board.sections.map(BoardSection::updated).filter { it.isNotBlank() }.distinct().sorted()
    return when (times.size) {
        0 -> ""
        1 -> "vérifié à ${times.first()}"
        else -> "vérifié entre ${times.first()} et ${times.last()}"
    }
}
