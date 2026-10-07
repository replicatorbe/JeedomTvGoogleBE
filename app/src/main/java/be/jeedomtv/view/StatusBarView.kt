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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Corner
import be.jeedomtv.model.Screen
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
 * écran des pages de l'application affiché : elle y est dessinée dans la rangée des onglets, à la
 * place du nom de la TV (dans un coin du bas, elle masquerait l'aide).
 */
fun statusWindowKind(state: AppState): Corner? {
    val status = state.status?.takeIf { it.visible } ?: return null
    if (state.dreaming || !state.screenOn) return null
    if (state.uiVisible && state.screen == Screen.Pages) return null
    return status.corner
}

/** « 19:20 » : format 24 h. */
fun statusClockText(millis: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(millis))

/** Texte lisible sur n'importe quelle image : une ombre plutôt qu'un fond. */
private val StatusShadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1f), blurRadius = 6f)

private val ItemHeight = 30.dp

/**
 * Barre d'état (remplace l'horloge et les indicateurs de TvOverlay) : l'heure, puis les
 * indicateurs, sur fond transparent. Redessinée une fois par minute pour l'heure, sinon
 * seulement quand Jeedom change la barre.
 */
@Composable
fun StatusBarView(status: StatusBar, modifier: Modifier = Modifier) {
    Row(
        modifier.alpha(status.opacity.coerceIn(0, 100) / 100f).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.clock) StatusClock()
        status.items.forEach { StatusChip(it) }
    }
}

@Composable
private fun StatusClock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Réveil au changement de minute seulement : l'heure n'affiche pas les secondes.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    Text(
        statusClockText(now),
        style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, shadow = StatusShadow),
    )
}

private fun StatusShape.toShape(): Shape = when (this) {
    StatusShape.Circle -> CircleShape
    StatusShape.Rounded -> RoundedCornerShape(8.dp)
    StatusShape.Rectangular -> RectangleShape
}

/** Indicateur : icône MDI, texte facultatif, bordure et fond de la couleur demandée. */
@Composable
private fun StatusChip(item: StatusItem) {
    val shape = item.shape.toShape()
    val border = Color(item.borderColor)
    val chip = Modifier
        .height(ItemHeight)
        .background(Color(item.backgroundColor), shape)
        .then(if (border.alpha > 0f) Modifier.border(2.dp, border, shape) else Modifier)
    if (item.text.isEmpty()) {
        // Icône seule : un carré (un rond pour `circle`).
        Box(chip.size(ItemHeight), contentAlignment = Alignment.Center) {
            MdiIcon(item.icon, Color(item.iconColor), 18.dp)
        }
    } else {
        Row(
            chip.padding(start = 6.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MdiIcon(item.icon, Color(item.iconColor), 18.dp)
            Text(
                item.text,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 160.dp),
                style = TextStyle(color = Color(item.textColor), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, shadow = StatusShadow),
            )
        }
    }
}
