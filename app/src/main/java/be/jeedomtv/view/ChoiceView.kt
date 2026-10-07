package be.jeedomtv.view

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.ChoiceMode
import be.jeedomtv.model.Tile

private val ChoiceShape = RoundedCornerShape(24.dp)

/**
 * Mode de choix d'une tuile `select` : les libellés en « puces », le choix en attente sur fond
 * d'accent (envoyé par OK), la valeur actuelle marquée d'un ✓ et cerclée.
 * [compact] : version resserrée pour le panneau en superposition.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoicePanel(tile: Tile, mode: ChoiceMode, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier
            .widthIn(max = 860.dp)
            .background(JeedomTvColors.Overlay, RoundedCornerShape(16.dp))
            .padding(horizontal = 32.dp, vertical = if (compact) 12.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TileIconView(tile.icon, JeedomTvColors.Accent, Modifier.size(if (compact) 32.dp else 44.dp))
            Text(tile.name, color = JeedomTvColors.Text, fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.SemiBold)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = tile.choiceIndex
            tile.choices.forEachIndexed { index, choice ->
                val selected = index == mode.selected
                val isCurrent = index == current
                Text(
                    if (isCurrent) "✓ ${choice.label}" else choice.label,
                    color = if (selected) JeedomTvColors.OnAccent else JeedomTvColors.Text,
                    fontSize = if (compact) 20.sp else 24.sp,
                    fontWeight = if (selected || isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier
                        .border(3.dp, if (isCurrent) JeedomTvColors.Accent else Color.Transparent, ChoiceShape)
                        .background(if (selected) JeedomTvColors.Accent else JeedomTvColors.SurfaceVariant, ChoiceShape)
                        .padding(horizontal = 22.dp, vertical = if (compact) 6.dp else 10.dp),
                )
            }
        }
        Text("Actuel : ${tile.choiceLabel ?: "—"}", color = JeedomTvColors.TextMuted, fontSize = 18.sp)
    }
}
