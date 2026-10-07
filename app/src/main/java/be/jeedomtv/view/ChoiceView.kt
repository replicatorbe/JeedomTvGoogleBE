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

private val ChoiceShape = RoundedCornerShape(50)

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
            .jeedomCard()
            .padding(horizontal = 32.dp, vertical = if (compact) 12.dp else 26.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconPill(tileMdiIcon(tile), SoftBlue, if (compact) 36.dp else 44.dp)
            Text(tile.name, color = Color.White, fontSize = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.SemiBold)
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
                    color = if (selected) Color(0xFF10141B) else Color.White,
                    fontSize = if (compact) 20.sp else 24.sp,
                    fontWeight = if (selected || isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    // Sélection : pilule blanche à texte sombre ; choix actuel : ✓ et contour ambre.
                    modifier = Modifier
                        .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f), ChoiceShape)
                        .border(
                            if (isCurrent) 2.dp else 1.dp,
                            when {
                                isCurrent -> ActiveAmber
                                selected -> Color.White
                                else -> Color.White.copy(alpha = 0.3f)
                            },
                            ChoiceShape,
                        )
                        .padding(horizontal = 22.dp, vertical = if (compact) 6.dp else 10.dp),
                )
            }
        }
        Text("Actuel : ${tile.choiceLabel ?: "—"}", color = CardTextMuted, fontSize = 16.sp)
    }
}
