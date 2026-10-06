package be.jeedomtv.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.PendingAction

/**
 * Confirmation d'un ordre sur une tuile `confirm: true`. Pas de boutons focusables :
 * OK et Retour sont interprétés par le contrôleur.
 */
@Composable
fun ConfirmDialog(pending: PendingAction, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(640.dp)
            .background(JeedomTvColors.Overlay, RoundedCornerShape(16.dp))
            .border(2.dp, JeedomTvColors.Accent, RoundedCornerShape(16.dp))
            .padding(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Confirmer ?", color = JeedomTvColors.TextMuted, fontSize = 22.sp)
        Text(
            pending.label,
            color = JeedomTvColors.Text,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Key("OK", "Confirmer", accent = true)
            Key("Retour", "Annuler", accent = false)
        }
    }
}

@Composable
private fun Key(key: String, label: String, accent: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            key,
            color = if (accent) JeedomTvColors.OnAccent else JeedomTvColors.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(
                    if (accent) JeedomTvColors.Accent else JeedomTvColors.SurfaceVariant,
                    RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
        Text(label, color = JeedomTvColors.Text, fontSize = 22.sp)
    }
}
