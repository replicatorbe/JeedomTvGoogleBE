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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import be.jeedomtv.model.BoardWeather
import be.jeedomtv.model.MAX_WEATHER_HOURS
import be.jeedomtv.model.Page
import be.jeedomtv.model.Train
import be.jeedomtv.model.TrainStatus
import be.jeedomtv.model.WeatherHour

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

/** Bande météo : temps actuel (et horloge), colonnes horaires, écart avec la première section. */
private val WeatherLineHeight = 40.dp
private val WeatherHoursHeight = 32.dp
private val WeatherGap = 6.dp

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
        // Avec la bande météo, l'horloge passe sur sa première ligne : les titres n'ont plus à lui faire place.
        val weather = board.weather?.takeIf { weatherBandLines(it) > 0 }
        val reserved = (if (banner != null) BannerLineHeight else 0.dp) + weatherBandHeight(weather)
        val (row, trainsPerSection) = boardFit(board, maxHeight, reserved)
        val font = with(LocalDensity.current) { (row * 0.56f).toSp() }
        val small = with(LocalDensity.current) { (row * 0.42f).toSp() }
        val clockReserve = if (weather == null) row * 4 else 0.dp
        Column(Modifier.fillMaxSize()) {
            if (inOverlay && state.panelClosing) ClosingBar() else Spacer(Modifier.height(3.dp))
            weather?.let { WeatherBand(it) }
            if (board.sections.isEmpty()) {
                // Le plugin n'a encore rien calculé : le titre de la page et l'heure seulement.
                SectionTitle(page.name, "", font, endReserve = clockReserve)
                Text("Aucun train", color = BoardColors.Muted, fontSize = font, modifier = Modifier.padding(top = 12.dp))
            }
            board.sections.forEachIndexed { index, section ->
                if (index > 0) Spacer(Modifier.height(row * SECTION_GAP))
                // La première ligne de titre laisse la place de l'horloge, en haut à droite.
                SectionTitle(section.title.ifBlank { page.name }, section.day, font, endReserve = if (index == 0) clockReserve else 0.dp)
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
        // Horloge en haut à droite, comme sur le quai (sur la bande météo quand elle est là).
        if (weather == null) {
            Text(
                statusClockText(rememberMinuteTime()),
                color = BoardColors.Text,
                fontSize = font * 1.15f,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp),
            )
        }
    }
}

/** Écart entre deux sections, en hauteurs de ligne. */
private const val SECTION_GAP = 0.5f

/** Mise en page du tableau : hauteur d'une ligne et nombre de trains montrés par section. */
internal data class BoardFit(val row: Dp, val trainsPerSection: Int)

/**
 * Hauteur d'une ligne pour que tout le tableau tienne dans [height] (moins [reserved], la ligne du
 * bandeau d'un `notify` et la bande météo) : titres (1,1 ligne), trains (« Aucun train » compte pour un), notes (0,8),
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
 * Bande météo en tête du tableau : ligne 1, le temps actuel, `tmin`/`tmax`, puis pluie, vigilance
 * et note en couleur d'alerte, l'horloge à droite ; ligne 2, les colonnes horaires.
 */
@Composable
private fun WeatherBand(weather: BoardWeather) {
    Row(Modifier.fillMaxWidth().height(WeatherLineHeight), verticalAlignment = Alignment.CenterVertically) {
        if (weather.icon.isNotBlank()) {
            MdiIcon(weather.icon, BoardColors.Text, 30.dp)
            Spacer(Modifier.width(10.dp))
        }
        val line = buildAnnotatedString {
            weatherTempText(weather.temp).takeIf { it.isNotEmpty() }?.let {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp)) { append(it) }
            }
            weather.condition.takeIf { it.isNotBlank() }?.let { append(if (length > 0) " $it" else it) }
            weatherRangeText(weather.tmin, weather.tmax).takeIf { it.isNotEmpty() }?.let {
                if (length > 0) append(" · ")
                withStyle(SpanStyle(color = BoardColors.Muted)) { append(it) }
            }
            weatherAlerts(weather).forEach { (text, color) ->
                if (length > 0) append(" · ")
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(text) }
            }
        }
        Text(
            line,
            color = BoardColors.Text,
            fontSize = 22.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            statusClockText(rememberMinuteTime()),
            color = BoardColors.Text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
    if (weather.hours.isNotEmpty()) {
        // Six cases de même largeur, même avec moins de colonnes : elles restent alignées à gauche.
        Row(Modifier.fillMaxWidth().height(WeatherHoursHeight), verticalAlignment = Alignment.CenterVertically) {
            weather.hours.forEach { WeatherHourCell(it, Modifier.weight(1f)) }
            val free = MAX_WEATHER_HOURS - weather.hours.size
            if (free > 0) Spacer(Modifier.weight(free.toFloat()))
        }
    }
    Spacer(Modifier.height(WeatherGap))
}

/** « 09h », icône, « 15° » et le risque de pluie (en couleur d'alerte à partir de 50 %). */
@Composable
private fun WeatherHourCell(hour: WeatherHour, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(weatherHourText(hour.h), color = BoardColors.Muted, fontSize = 18.sp, maxLines = 1, softWrap = false)
        if (hour.icon.isNotBlank()) MdiIcon(hour.icon, BoardColors.Text, 24.dp)
        weatherTempText(hour.temp).takeIf { it.isNotEmpty() }?.let {
            Text(it, color = BoardColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        weatherRainText(hour.rain)?.let {
            val alert = weatherRainAlert(hour.rain)
            Text(
                it,
                color = if (alert) BoardColors.Slight else BoardColors.Muted,
                fontSize = 16.sp,
                fontWeight = if (alert) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
            )
        }
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

// --- Météo (pure, testable sans Compose) -------------------------------------------------------

/**
 * Lignes de la bande météo : 0 (rien à montrer, comme sans météo), 1 (temps actuel ou note seule)
 * ou 2 (avec les colonnes horaires).
 */
fun weatherBandLines(weather: BoardWeather?): Int {
    if (weather == null) return 0
    val first = weatherHasNow(weather) || weatherAlerts(weather).isNotEmpty()
    return when {
        weather.hours.isNotEmpty() -> 2
        first -> 1
        else -> 0
    }
}

/** Hauteur de la bande météo (écart compris), retirée aux trains par [boardFit] ; 0 sans bande. */
internal fun weatherBandHeight(weather: BoardWeather?): Dp = when (weatherBandLines(weather)) {
    0 -> 0.dp
    1 -> WeatherLineHeight + WeatherGap
    else -> WeatherLineHeight + WeatherHoursHeight + WeatherGap
}

/** Le temps actuel est-il connu (température, icône, condition ou températures du jour) ? */
private fun weatherHasNow(weather: BoardWeather): Boolean =
    weather.temp != null || weather.tmin != null || weather.tmax != null ||
        weather.icon.isNotBlank() || weather.condition.isNotBlank()

/** « 14° », « -3° » ; vide si inconnue. */
fun weatherTempText(temp: Int?): String = temp?.let { "$it°" }.orEmpty()

/** « ↓7° ↑21° », une seule des deux si l'autre manque ; vide si aucune n'est connue. */
fun weatherRangeText(tmin: Int?, tmax: Int?): String =
    listOfNotNull(tmin?.let { "↓$it°" }, tmax?.let { "↑$it°" }).joinToString(" ")

/** « 09 » → « 09h », « 9 » → « 09h » ; un autre texte est gardé tel quel. */
fun weatherHourText(h: String): String {
    val hour = h.trim()
    return if (hour.length in 1..2 && hour.all(Char::isDigit)) "${hour.padStart(2, '0')}h" else hour
}

/** « 30 % » ; null sans risque (0 ou inconnu). */
fun weatherRainText(rain: Int?): String? = rain?.takeIf { it > 0 }?.let { "$it %" }

/** Risque de pluie à signaler en couleur d'alerte : 50 % ou plus. */
fun weatherRainAlert(rain: Int?): Boolean = (rain ?: 0) >= 50

/** Couleur d'une vigilance : jaune, orange, rouge ; sans niveau, celle des notes. */
fun weatherWarningColor(level: Int): Color = when (level) {
    1 -> BoardColors.Yellow
    2 -> BoardColors.Delayed
    3 -> BoardColors.Canceled
    else -> BoardColors.Slight
}

/** Textes d'alerte de la ligne 1, dans l'ordre : pluie, vigilance (couleur de son niveau), note. */
fun weatherAlerts(weather: BoardWeather): List<Pair<String, Color>> = listOfNotNull(
    weather.rain.takeIf { it.isNotBlank() }?.let { it to BoardColors.Slight },
    weather.warning.takeIf { it.isNotBlank() }?.let { it to weatherWarningColor(weather.warningLevel) },
    weather.note.takeIf { it.isNotBlank() }?.let { it to BoardColors.Slight },
)
