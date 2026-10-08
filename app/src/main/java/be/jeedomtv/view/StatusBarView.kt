package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Corner
import be.jeedomtv.model.StatusBar
import be.jeedomtv.model.StatusItem
import be.jeedomtv.model.StatusShape
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coin de la fenêtre de la barre d'état, ou null si elle ne doit pas être affichée : pas de
 * barre (ou rien à montrer), écran éteint, notre écran de veille affiché (il a son horloge), ou
 * application affichée, quel que soit l'écran : sur celui des pages, elle est dessinée dans la
 * rangée des onglets ; sur la configuration ou le chargement, elle masquerait le formulaire.
 */
fun statusWindowKind(state: AppState): Corner? {
    val status = state.status?.takeIf { it.visible } ?: return null
    if (state.dreaming || !state.screenOn) return null
    if (state.uiVisible) return null
    return status.corner
}

/** « 19:20 » : format 24 h. */
fun statusClockText(millis: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(millis))

/** Texte lisible sur n'importe quelle image : une ombre plutôt qu'un fond. */
private val StatusShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1f), blurRadius = 6f)

/**
 * Dimensions de la barre. [Overlay] : par-dessus les autres applications, à la taille de
 * TvOverlay (pastilles d'environ 36 px de haut en 1920 × 1080, densité 2). [InApp] : dans la
 * rangée des onglets de l'application.
 */
class StatusBarStyle(
    val itemHeight: Dp,
    val iconSize: Dp,
    val clockSize: TextUnit,
    val textSize: TextUnit,
    val spacing: Dp,
    val border: Dp,
    val padding: Dp,
    val weight: FontWeight,
) {
    companion object {
        /**
         * Mesuré sur une capture de TvOverlay en 1920 × 1080 (densité 2) : pastilles de ~36 px,
         * icônes de ~22 px, 10 à 12 px entre l'heure et les pastilles et entre deux pastilles,
         * texte de ~13 sp en graisse normale.
         */
        val Overlay = StatusBarStyle(18.dp, 11.dp, 13.sp, 13.sp, 5.dp, 1.dp, 1.dp, FontWeight.Normal)
        val InApp = StatusBarStyle(30.dp, 18.dp, 20.sp, 16.sp, 10.dp, 2.dp, 4.dp, FontWeight.SemiBold)
    }
}

/**
 * Barre d'état (remplace l'horloge et les indicateurs de TvOverlay) : l'heure, puis les
 * indicateurs, sur fond transparent. Redessinée une fois par minute pour l'heure, sinon
 * seulement quand Jeedom change la barre.
 */
@Composable
fun StatusBarView(
    status: StatusBar,
    modifier: Modifier = Modifier,
    style: StatusBarStyle = StatusBarStyle.InApp,
    /** Jeedom injoignable : barre grisée (~40 %) et petit indicateur orange après l'heure. */
    unreachable: Boolean = false,
) {
    Row(
        modifier.alpha(statusBarAlpha(status.opacity, unreachable)).padding(style.padding),
        horizontalArrangement = Arrangement.spacedBy(style.spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.clock) StatusClock(style)
        if (unreachable) MdiIcon(UNREACHABLE_ICON, UnreachableOrange, style.iconSize * 1.2f)
        status.items.forEach { StatusChip(it, style) }
    }
}

/** Opacité de la barre : celle demandée par Jeedom, réduite à ~40 % de celle-ci s'il est injoignable. */
fun statusBarAlpha(opacity: Int, unreachable: Boolean): Float =
    opacity.coerceIn(0, 100) / 100f * (if (unreachable) UNREACHABLE_ALPHA else 1f)

/** Indicateur « Jeedom injoignable » de la barre d'état. */
internal const val UNREACHABLE_ICON = "mdi:lan-disconnect"
internal const val UNREACHABLE_ALPHA = 0.4f
private val UnreachableOrange = Color(0xFFFFA726)

/** Heure courante, mise à jour au changement de minute seulement (pas de recomposition à chaque seconde). */
@Composable
internal fun rememberMinuteTime(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun StatusClock(style: StatusBarStyle) {
    val now = rememberMinuteTime()
    Text(
        statusClockText(now),
        style = TextStyle(color = Color.White, fontSize = style.clockSize, fontWeight = style.weight, shadow = StatusShadow),
    )
}

private fun StatusShape.toShape(): Shape = when (this) {
    StatusShape.Circle -> CircleShape
    StatusShape.Rounded -> RoundedCornerShape(percent = 35)
    StatusShape.Rectangular -> RectangleShape
}

/** Indicateur : icône MDI, texte facultatif, bordure et fond de la couleur demandée. */
@Composable
private fun StatusChip(item: StatusItem, style: StatusBarStyle) {
    val shape = item.shape.toShape()
    val border = Color(item.borderColor)
    val chip = Modifier
        .height(style.itemHeight)
        .background(Color(item.backgroundColor), shape)
        .then(if (border.alpha > 0f) Modifier.border(style.border, border, shape) else Modifier)
    if (item.text.isEmpty()) {
        // Icône seule : un carré (un rond pour `circle`).
        Box(chip.size(style.itemHeight), contentAlignment = Alignment.Center) {
            MdiIcon(item.icon, Color(item.iconColor), style.iconSize)
        }
    } else {
        Row(
            chip.padding(start = style.itemHeight / 4, end = style.itemHeight / 3),
            horizontalArrangement = Arrangement.spacedBy(style.itemHeight / 5),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MdiIcon(item.icon, Color(item.iconColor), style.iconSize)
            Text(
                item.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = style.itemHeight * 5),
                style = TextStyle(
                    color = Color(item.textColor),
                    fontSize = style.textSize,
                    lineHeight = style.textSize,
                    fontWeight = style.weight,
                    shadow = StatusShadow,
                ),
            )
        }
    }
}
