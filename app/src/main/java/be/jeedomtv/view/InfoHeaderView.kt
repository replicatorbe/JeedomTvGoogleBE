package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.controller.formatValue
import be.jeedomtv.model.HeaderItem
import be.jeedomtv.model.MAX_HEADER_ITEMS


/**
 * Bandeau d'infos de la maison (`header`) : une ligne de « puces » icône, libellé discret au-dessus
 * de la valeur avec unité. Chaque puce a sa largeur naturelle ; si elles ne tiennent pas toutes,
 * seules les plus larges sont réduites (texte coupé par des points de suspension), voir [shareWidths].
 * [compact] : panneau en superposition, un peu plus petit.
 */
@Composable
fun InfoHeader(items: List<HeaderItem>, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (items.isEmpty()) return
    val gap = if (compact) 18.dp else 24.dp
    Layout(
        content = { items.take(MAX_HEADER_ITEMS).forEach { InfoChip(it, compact) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val needs = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val available = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - gapPx * (measurables.size - 1)).coerceAtLeast(0)
        } else {
            needs.sum()
        }
        val widths = shareWidths(needs, available)
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints(maxWidth = widths[index]))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val width = placeables.sumOf { it.width } + gapPx * (placeables.size - 1).coerceAtLeast(0)
        layout(width.coerceAtMost(constraints.maxWidth), height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gapPx
            }
        }
    }
}

/**
 * Largeurs des puces, d'après leurs largeurs naturelles [needs] et la place [available] :
 * tout tient → chacune a la sienne ; sinon un même plafond est fixé pour les plus larges,
 * de façon à remplir exactement la place, et les petites restent entières.
 */
internal fun shareWidths(needs: List<Int>, available: Int): List<Int> {
    if (needs.sum() <= available) return needs
    var remaining = available
    var left = needs.size
    var cap = 0
    for (need in needs.sorted()) {
        cap = remaining / left
        if (need > cap) break
        remaining -= need
        left--
    }
    return needs.map { minOf(it, cap) }
}

@Composable
private fun InfoChip(item: HeaderItem, compact: Boolean, modifier: Modifier = Modifier) {
    // Puce discrète, sans fond : icône colorée, valeur en blanc, libellé en petit gris.
    Row(
        modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MdiIcon(item.icon.mdiName(), SoftBlue, if (compact) 18.dp else 20.dp)
        OneLine(
            headerValueText(item),
            color = Color.White,
            fontSize = if (compact) 14.sp else 16.sp,
            lineHeight = if (compact) 18.sp else 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (item.label.isNotBlank()) {
            // Le libellé cède la place à la valeur quand la largeur manque.
            Box(Modifier.weight(1f, fill = false)) {
                OneLine(item.label, color = CardTextMuted, fontSize = if (compact) 12.sp else 13.sp, lineHeight = 16.sp)
            }
        }
    }
}

/** Une ligne, coupée par des points de suspension si elle ne tient pas. */
@Composable
private fun OneLine(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Text(
        text,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

/** « 17 °C », « 2283 W », « demain : Déchets organiques », « — » sans valeur. */
fun headerValueText(item: HeaderItem): String {
    val value = item.value?.takeIf { it.isNotBlank() } ?: return "—"
    val number = value.trim().replace(',', '.').toDoubleOrNull()
    return if (number != null) {
        formatValue(number, item.unit)
    } else {
        listOf(value, item.unit).filter { it.isNotBlank() }.joinToString(" ")
    }
}
