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
import androidx.compose.ui.graphics.Color
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
            .width(600.dp)
            .jeedomCard()
            .padding(horizontal = 32.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconPill("mdi:help-circle-outline", SoftBlue, 44.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Confirmer ?", color = CardTextMuted, fontSize = 16.sp)
                Text(pending.label, color = Color.White, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KeyPill("OK", "Confirmer", primary = true)
            KeyPill("Retour", "Annuler", primary = false)
        }
    }
}

/**
 * Bouton en pilule, comme les réponses des questions : le principal en blanc à texte sombre, l'autre
 * avec un contour discret. Pas de focus Compose : la touche indiquée agit (contrôleur).
 */
@Composable
internal fun KeyPill(key: String, label: String, primary: Boolean) {
    val shape = RoundedCornerShape(50)
    val text = if (primary) Color(0xFF10141B) else Color.White
    Row(
        Modifier
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.08f), shape)
            .border(1.dp, if (primary) Color.White else Color.White.copy(alpha = 0.3f), shape)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = text.copy(alpha = 0.55f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
