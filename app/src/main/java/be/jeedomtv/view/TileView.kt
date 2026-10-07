package be.jeedomtv.view

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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

private val TileShape = RoundedCornerShape(16.dp)

/**
 * Une tuile de la grille, dans la famille des cartes : pastille d'icône et valeur en haut, pièce
 * et nom dessous, jauge fine en bas (volet, curseur). Allumée : accent ambre (pastille, état, fond
 * légèrement teinté) au lieu d'un fond plein. Sélection ([focused], portée par l'état et non par
 * le focus Compose) : légèrement agrandie, plus claire, liseré blanc et ombre, en 150 ms.
 */
@Composable
fun TileView(
    tile: Tile,
    number: Int,
    focused: Boolean,
    flashing: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = tile.isActive
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(FOCUS_MS), label = "focus")
    val background by animateColorAsState(
        when {
            flashing -> SoftBlue.copy(alpha = 0.35f)
            active && focused -> ActiveAmber.copy(alpha = 0.22f)
            active -> ActiveAmber.copy(alpha = 0.14f)
            focused -> Color.White.copy(alpha = 0.14f)
            else -> Color.White.copy(alpha = 0.06f)
        },
        tween(FOCUS_MS),
        label = "fond",
    )
    val outline = when {
        focused -> Color.White
        active -> ActiveAmber.copy(alpha = 0.35f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val accent = if (active) ActiveAmber else SoftBlue

    BoxWithConstraints(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focused) Modifier.shadow(12.dp, TileShape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(TileShape)
            .background(background)
            .border(if (focused) 2.dp else 1.dp, outline, TileShape),
    ) {
        // Assez de hauteur : le nom peut tenir sur deux lignes ; sinon une seule, jamais coupée à mi-hauteur.
        val nameLines = if (maxHeight >= 124.dp) 2 else 1
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconPill(tileMdiIcon(tile), if (active) ActiveAmber else if (flashing) Color.White else Color.White.copy(alpha = 0.85f), 34.dp)
                Spacer(Modifier.width(10.dp))
                tileCornerText(tile)?.let { text ->
                    FitText(
                        text,
                        color = cornerColor(tile, active),
                        maxFontSize = if (tile.type == TileType.Info || tile.type == TileType.Slider) 24.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        alignEnd = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            val (room, label) = splitRoom(tile.name)
            if (room != null) {
                Text(
                    room,
                    color = CardTextMuted,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 18.dp),
                )
            }
            Text(
                label,
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = nameLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 18.dp),
            )
        }
        // Touches 1 à 9 : action directe sur la tuile N ; discret, dans le coin.
        if (number in 1..9) {
            Text(
                number.toString(),
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 8.dp),
            )
        }
        // Jauge fine du volet ou du curseur, le long du bord bas.
        tileFraction(tile)?.let { fraction ->
            Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)) {
                drawRect(Color.White.copy(alpha = 0.10f))
                drawRect(accent, size = size.copy(width = size.width * fraction))
            }
        }
    }
}

/** Couleur de la valeur : ambre si allumé, discrète pour un état éteint ou un simple repère. */
private fun cornerColor(tile: Tile, active: Boolean): Color = when {
    active -> ActiveAmber
    tile.type == TileType.Switch -> Color.White.copy(alpha = 0.6f)
    tile.type == TileType.Button && tile.value == null -> Color.White.copy(alpha = 0.6f)
    tile.type == TileType.Shutter && tile.value == null -> Color.White.copy(alpha = 0.6f)
    else -> Color.White
}

private const val FOCUS_MS = 150

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
    alignEnd: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val baseStyle = LocalTextStyle.current.merge(TextStyle(fontWeight = fontWeight))
    BoxWithConstraints(modifier, contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
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
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = radius)
        drawRoundRect(SoftBlue, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
        // Repère de la valeur actuelle.
        currentFraction?.let {
            val x = size.width * it.coerceIn(0f, 1f)
            drawLine(Color.White, Offset(x, -6f), Offset(x, size.height + 6f), strokeWidth = 4f)
        }
    }
}
