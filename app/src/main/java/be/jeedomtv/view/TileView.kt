package be.jeedomtv.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import be.jeedomtv.controller.formatValue
import be.jeedomtv.model.Tile
import be.jeedomtv.model.TileIcon
import be.jeedomtv.model.TileType
import kotlin.math.cos
import kotlin.math.sin

private val TileShape = RoundedCornerShape(12.dp)

/**
 * Une tuile de la grille : icône, nom, valeur avec unité. La sélection est dessinée d'après
 * l'état ([focused]), pas d'après le focus Compose. Un interrupteur allumé a un fond ambré.
 */
@Composable
fun TileView(
    tile: Tile,
    number: Int,
    focused: Boolean,
    flashing: Boolean,
    modifier: Modifier = Modifier,
) {
    val on = tile.type == TileType.Switch && tile.isOn
    val background = when {
        flashing -> JeedomTvColors.Accent
        on -> JeedomTvColors.SwitchOn
        focused -> JeedomTvColors.SurfaceVariant
        else -> JeedomTvColors.Surface
    }
    val content = when {
        flashing -> JeedomTvColors.OnAccent
        on -> JeedomTvColors.OnSwitchOn
        else -> JeedomTvColors.Text
    }
    val muted = if (flashing || on) content.copy(alpha = 0.75f) else JeedomTvColors.TextMuted

    Column(
        modifier
            .border(
                width = 4.dp,
                color = if (focused) JeedomTvColors.Accent else Color.Transparent,
                shape = TileShape,
            )
            .padding(4.dp)
            .background(background, TileShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val (room, label) = splitRoom(tile.name)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                room.orEmpty(),
                color = muted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Touches 1 à 9 : action directe sur la tuile N.
            if (number in 1..9) {
                Text(number.toString(), color = muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Le nom prend la place qui reste une fois la valeur placée : deux lignes, une seule, ou
        // des points de suspension. La valeur, elle, n'est jamais rognée.
        Text(
            label,
            color = content,
            fontSize = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TileIconView(tile.icon, color = if (on || flashing) content else JeedomTvColors.Accent, modifier = Modifier.size(26.dp))
            FitText(
                tileValueText(tile),
                color = if (tile.type == TileType.Switch || tile.type == TileType.Scene || isButtonWithoutValue(tile)) muted else content,
                maxFontSize = if (tile.type == TileType.Info || tile.type == TileType.Slider) 24.sp else 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Tailles essayées, de la plus grande à la plus petite, pour qu'une valeur tienne en largeur. */
private val FitScales = listOf(1f, 0.88f, 0.76f, 0.66f, 0.56f)

/**
 * Texte d'une ligne qui réduit sa taille plutôt que d'être coupé (« 24,4 °C » dans une tuile
 * étroite). La hauteur de ligne reste celle de [maxFontSize] : la tuile ne bouge pas.
 */
@Composable
private fun FitText(
    text: String,
    color: Color,
    maxFontSize: TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current.merge(TextStyle(fontWeight = fontWeight))
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val maxWidth = constraints.maxWidth
        val fontSize = remember(text, maxWidth, maxFontSize, baseStyle) {
            FitScales.map { maxFontSize * it }.firstOrNull { size ->
                measurer.measure(text, baseStyle.copy(fontSize = size), maxLines = 1, softWrap = false)
                    .size.width <= maxWidth
            } ?: (maxFontSize * FitScales.last())
        }
        Text(
            text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = baseStyle.copy(lineHeight = maxFontSize * 1.2f),
        )
    }
}

/**
 * Les pages par type nomment les tuiles « Pièce · Nom » : la pièce s'affiche à part, en petit,
 * pour laisser au nom toute la largeur de la tuile. Sans séparateur, pas de pièce.
 */
fun splitRoom(name: String): Pair<String?, String> {
    val index = name.indexOf(ROOM_SEPARATOR)
    if (index <= 0 || index + ROOM_SEPARATOR.length >= name.length) return null to name
    return name.substring(0, index).trim() to name.substring(index + ROOM_SEPARATOR.length).trim()
}

private const val ROOM_SEPARATOR = " · "

/** Texte de la valeur selon le type : « Allumé », « 20,5 °C », « 40 % »… */
fun tileValueText(tile: Tile): String = when (tile.type) {
    TileType.Switch -> when {
        tile.value == null -> ""
        tile.isOn -> "Allumé"
        else -> "Éteint"
    }
    TileType.Scene -> "▶ Lancer"
    TileType.Button -> if (tile.value == null) "▶" else valueWithUnit(tile)
    TileType.Select -> tile.choiceLabel ?: "—"
    TileType.Shutter -> when {
        tile.value == null -> "▲ ▼"
        tile.numericValue != null && tile.numericValue == tile.min -> "Fermé"
        tile.numericValue != null && tile.numericValue == tile.max -> "Ouvert"
        else -> valueWithUnit(tile)
    }
    TileType.Slider, TileType.Info -> if (tile.value == null) "—" else valueWithUnit(tile)
}

private fun isButtonWithoutValue(tile: Tile) = tile.type == TileType.Button && tile.value == null

private fun valueWithUnit(tile: Tile): String {
    val number = tile.numericValue
    return if (number != null) {
        formatValue(number, tile.displayUnit)
    } else {
        listOf(tile.value.orEmpty(), tile.displayUnit).filter { it.isNotBlank() }.joinToString(" ")
    }
}

/**
 * Unité affichée : Jeedom n'en donne pas pour la position des groupes de volets,
 * qui est pourtant un pourcentage dès que la tuile a des bornes.
 */
val Tile.displayUnit: String
    get() = if (type == TileType.Shutter && unit.isBlank() && min != null && max != null) "%" else unit

/** Icônes dessinées au Canvas : pas de police d'emoji ni de ressource à embarquer. */
@Composable
fun TileIconView(icon: TileIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
        when (icon) {
            TileIcon.Light -> {
                drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.5f, h * 0.38f), style = stroke)
                drawLine(color, Offset(w * 0.38f, h * 0.78f), Offset(w * 0.62f, h * 0.78f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.42f, h * 0.92f), Offset(w * 0.58f, h * 0.92f), stroke.width, StrokeCap.Round)
            }
            TileIcon.Plug -> {
                drawRoundRect(color, Offset(w * 0.22f, h * 0.32f), Size(w * 0.56f, h * 0.4f), CornerRadius(w * 0.1f), style = stroke)
                drawLine(color, Offset(w * 0.38f, h * 0.1f), Offset(w * 0.38f, h * 0.32f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.1f), Offset(w * 0.62f, h * 0.32f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.92f), stroke.width, StrokeCap.Round)
            }
            TileIcon.Shutter -> {
                drawRect(color, Offset(w * 0.15f, h * 0.12f), Size(w * 0.7f, h * 0.76f), style = stroke)
                for (i in 1..3) {
                    val y = h * (0.12f + 0.19f * i)
                    drawLine(color, Offset(w * 0.15f, y), Offset(w * 0.85f, y), stroke.width * 0.7f)
                }
            }
            TileIcon.Thermostat -> {
                drawCircle(color, radius = w * 0.38f, style = stroke)
                rotate(-40f) {
                    drawLine(color, center, Offset(w * 0.5f, h * 0.24f), stroke.width, StrokeCap.Round)
                }
                drawCircle(color, radius = w * 0.06f)
            }
            TileIcon.Temperature -> {
                drawRoundRect(color, Offset(w * 0.4f, h * 0.08f), Size(w * 0.2f, h * 0.6f), CornerRadius(w * 0.1f), style = stroke)
                drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.78f))
            }
            TileIcon.Scene -> drawPath(starPath(w, h), color)
            TileIcon.Fan -> {
                for (angle in listOf(0f, 120f, 240f)) {
                    rotate(angle) {
                        drawOval(color, Offset(w * 0.4f, h * 0.06f), Size(w * 0.2f, h * 0.4f))
                    }
                }
                drawCircle(color, radius = w * 0.08f)
            }
            TileIcon.Lock -> {
                drawArc(color, 180f, 180f, false, Offset(w * 0.3f, h * 0.1f), Size(w * 0.4f, h * 0.44f), style = stroke)
                drawLine(color, Offset(w * 0.3f, h * 0.32f), Offset(w * 0.3f, h * 0.45f), stroke.width)
                drawLine(color, Offset(w * 0.7f, h * 0.32f), Offset(w * 0.7f, h * 0.45f), stroke.width)
                drawRoundRect(color, Offset(w * 0.2f, h * 0.45f), Size(w * 0.6f, h * 0.45f), CornerRadius(w * 0.08f))
            }
            TileIcon.Alarm -> drawBell(color, stroke)
            TileIcon.Camera -> drawCamera(color, stroke)
            TileIcon.Sun -> drawSun(color, stroke)
            TileIcon.Rain -> drawRain(color, stroke)
            TileIcon.Trash -> drawTrash(color, stroke)
            TileIcon.Power -> drawPower(color, stroke)
            TileIcon.Generic -> {
                drawRoundRect(color, Offset(w * 0.15f, h * 0.15f), Size(w * 0.7f, h * 0.7f), CornerRadius(w * 0.15f), style = stroke)
                drawCircle(color, radius = w * 0.1f)
            }
        }
    }
}

private fun starPath(w: Float, h: Float): Path = Path().apply {
    val cx = w / 2
    val cy = h / 2
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) w * 0.45f else w * 0.2f
        val a = Math.toRadians(-90.0 + i * 36.0)
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun DrawScope.drawBell(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val bell = Path().apply {
        moveTo(w * 0.2f, h * 0.75f)
        lineTo(w * 0.8f, h * 0.75f)
        lineTo(w * 0.72f, h * 0.62f)
        lineTo(w * 0.72f, h * 0.4f)
        cubicTo(w * 0.72f, h * 0.12f, w * 0.28f, h * 0.12f, w * 0.28f, h * 0.4f)
        lineTo(w * 0.28f, h * 0.62f)
        close()
    }
    drawPath(bell, color, style = stroke)
    drawCircle(color, radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.86f))
}

/** Caméra : boîtier, objectif et viseur. */
private fun DrawScope.drawCamera(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawRoundRect(color, Offset(w * 0.1f, h * 0.3f), Size(w * 0.8f, h * 0.55f), CornerRadius(w * 0.1f), style = stroke)
    drawLine(color, Offset(w * 0.34f, h * 0.18f), Offset(w * 0.52f, h * 0.18f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(w * 0.3f, h * 0.3f), Offset(w * 0.34f, h * 0.18f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(w * 0.52f, h * 0.18f), Offset(w * 0.56f, h * 0.3f), stroke.width, StrokeCap.Round)
    drawCircle(color, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.575f), style = stroke)
    drawCircle(color, radius = w * 0.04f, center = Offset(w * 0.76f, h * 0.4f))
}

/** Soleil : disque et huit rayons. */
private fun DrawScope.drawSun(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        drawLine(
            color,
            Offset(w * (0.5f + 0.32f * c), h * (0.5f + 0.32f * s)),
            Offset(w * (0.5f + 0.44f * c), h * (0.5f + 0.44f * s)),
            stroke.width,
            StrokeCap.Round,
        )
    }
}

/** Pluie : nuage et trois gouttes obliques. */
private fun DrawScope.drawRain(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val cloud = Path().apply {
        moveTo(w * 0.24f, h * 0.58f)
        cubicTo(w * 0.06f, h * 0.58f, w * 0.08f, h * 0.34f, w * 0.26f, h * 0.36f)
        cubicTo(w * 0.3f, h * 0.12f, w * 0.64f, h * 0.1f, w * 0.7f, h * 0.32f)
        cubicTo(w * 0.92f, h * 0.3f, w * 0.96f, h * 0.58f, w * 0.76f, h * 0.58f)
        close()
    }
    drawPath(cloud, color, style = stroke)
    for (x in listOf(0.32f, 0.5f, 0.68f)) {
        drawLine(color, Offset(w * x, h * 0.7f), Offset(w * (x - 0.06f), h * 0.88f), stroke.width, StrokeCap.Round)
    }
}

/** Poubelle : couvercle, poignée et cuve à rainures. */
private fun DrawScope.drawTrash(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawLine(color, Offset(w * 0.18f, h * 0.24f), Offset(w * 0.82f, h * 0.24f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(w * 0.4f, h * 0.12f), Offset(w * 0.6f, h * 0.12f), stroke.width, StrokeCap.Round)
    val bin = Path().apply {
        moveTo(w * 0.26f, h * 0.32f)
        lineTo(w * 0.32f, h * 0.9f)
        lineTo(w * 0.68f, h * 0.9f)
        lineTo(w * 0.74f, h * 0.32f)
    }
    drawPath(bin, color, style = stroke)
    for (x in listOf(0.42f, 0.58f)) {
        drawLine(color, Offset(w * x, h * 0.44f), Offset(w * x, h * 0.78f), stroke.width * 0.7f, StrokeCap.Round)
    }
}

/** Marche / arrêt : cercle ouvert en haut et trait vertical. */
private fun DrawScope.drawPower(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    drawArc(color, -60f, 300f, false, Offset(w * 0.16f, h * 0.18f), Size(w * 0.68f, h * 0.68f), style = stroke)
    drawLine(color, Offset(w * 0.5f, h * 0.1f), Offset(w * 0.5f, h * 0.48f), stroke.width, StrokeCap.Round)
}

/** Jauge horizontale : piste, remplissage jusqu'à [fraction], repère de la valeur actuelle. */
@Composable
fun Gauge(fraction: Float, currentFraction: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(JeedomTvColors.SurfaceVariant, cornerRadius = radius)
            drawRoundRect(
                JeedomTvColors.Accent,
                size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
                cornerRadius = radius,
            )
            currentFraction?.let {
                val x = size.width * it.coerceIn(0f, 1f)
                drawLine(JeedomTvColors.Text, Offset(x, -6f), Offset(x, size.height + 6f), strokeWidth = 4f)
            }
    }
}
