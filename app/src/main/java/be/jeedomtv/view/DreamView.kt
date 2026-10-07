package be.jeedomtv.view

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.AppState
import be.jeedomtv.model.HeaderItem
import be.jeedomtv.model.MAX_HEADER_ITEMS
import be.jeedomtv.model.Overlay
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ce que l'écran de veille affiche, déduit de l'état de l'application. */
data class DreamContent(
    /** Infos du bandeau ; vides sans configuration ou hors ligne (seulement l'heure). */
    val header: List<HeaderItem>,
    /** « Jeedom injoignable » sous l'heure. */
    val unreachable: Boolean,
)

/**
 * Sans configuration : l'heure seule. Configurée mais Jeedom injoignable (hors ligne, ou pages
 * jamais chargées) : l'heure et un petit « Jeedom injoignable ». Sinon le bandeau d'infos.
 */
fun dreamContent(state: AppState): DreamContent {
    if (state.config == null) return DreamContent(emptyList(), unreachable = false)
    val unreachable = state.offline || state.revision == null
    return DreamContent(if (unreachable) emptyList() else state.header.take(MAX_HEADER_ITEMS), unreachable)
}

/**
 * Un ordre de Jeedom demande l'attention : question posée, page affichée en panneau, ou
 * application à ouvrir. L'écran de veille s'efface alors devant lui.
 */
fun dreamShouldWake(state: AppState): Boolean =
    state.question != null || state.overlay is Overlay.Panel || state.foregroundRequested

/** « 21:07 ». */
fun dreamTime(millis: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(millis))

/** « Mardi 7 octobre ». */
fun dreamDate(millis: Long): String =
    SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(millis))
        .replaceFirstChar { it.titlecase(Locale.FRANCE) }

/** Position du contenu à la minute [minute] : un petit tour lent, pour ne pas marquer l'écran. */
internal fun dreamShift(minute: Long): Pair<Int, Int> = DreamShifts[Math.floorMod(minute, DreamShifts.size.toLong()).toInt()]

/** Décalages (dp) parcourus une minute après l'autre. */
private val DreamShifts = listOf(0 to 0, 24 to -12, 40 to 8, 16 to 24, -16 to 20, -40 to 4, -28 to -16, -4 to -24)

/** Texte un peu adouci : pas de blanc pur des heures durant. */
private val DreamText = Color(0xFFCFD4DA)
private val DreamMuted = Color(0xFF7D858F)

@Composable
fun DreamView(content: DreamContent) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Réveil à chaque changement de minute seulement : l'horloge n'affiche pas les secondes.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    val (dx, dy) = dreamShift(now / 60_000)
    val x by animateDpAsState(dx.dp, tween(durationMillis = 4_000), label = "x")
    val y by animateDpAsState(dy.dp, tween(durationMillis = 4_000), label = "y")

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            Modifier.offset(x, y).padding(horizontal = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(dreamTime(now), color = DreamText, fontSize = 112.sp, fontWeight = FontWeight.Light, lineHeight = 120.sp)
            Text(dreamDate(now), color = DreamMuted, fontSize = 32.sp)
            if (content.unreachable) {
                Spacer(Modifier.height(16.dp))
                Text("Jeedom injoignable", color = DreamMuted, fontSize = 18.sp)
            }
            if (content.header.isNotEmpty()) {
                Spacer(Modifier.height(32.dp))
                // Rangées de trois : assez de largeur pour une valeur en grand sans couper les mots.
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    content.header.chunked(DREAM_COLUMNS).forEach { row ->
                        Row(
                            Modifier.align(Alignment.CenterHorizontally),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            row.forEach { DreamInfo(it, Modifier.width(DreamInfoWidth)) }
                        }
                    }
                }
            }
        }
    }
}

private const val DREAM_COLUMNS = 3
private val DreamInfoWidth = 250.dp

/** Une info du bandeau, en grand : icône, valeur, libellé. */
@Composable
private fun DreamInfo(item: HeaderItem, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TileIconView(item.icon, DreamMuted, Modifier.size(32.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            headerValueText(item),
            color = DreamText,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (item.label.isNotBlank()) {
            Text(item.label, color = DreamMuted, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
