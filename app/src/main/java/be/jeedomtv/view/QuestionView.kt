package be.jeedomtv.view

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.Question
import be.jeedomtv.model.QuestionStatus

private val DialogShape = RoundedCornerShape(20.dp)
private val AnswerShape = RoundedCornerShape(14.dp)

/**
 * Question de Jeedom, lisible à 3 m : titre, question en grand, réponses en boutons horizontaux
 * (la sélection, pleine et agrandie, saute aux yeux), compte à rebours. Utilisée dans
 * l'application et en superposition ; les touches passent par le contrôleur.
 */
@Composable
fun QuestionDialog(question: Question, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(min = 560.dp, max = 900.dp)
            .background(JeedomTvColors.Overlay, DialogShape)
            .border(3.dp, JeedomTvColors.Accent, DialogShape)
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (question.title.isNotBlank()) {
            Text(question.title, color = JeedomTvColors.Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            question.message,
            color = JeedomTvColors.Text,
            fontSize = 36.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        when (val status = question.status) {
            QuestionStatus.Choosing -> {
                Answers(question)
                Countdown(question)
                Text(
                    "◀ ▶ : choisir · OK : répondre · 1-${question.answers.size} : réponse directe · Retour : fermer",
                    color = JeedomTvColors.TextMuted,
                    fontSize = 16.sp,
                )
            }
            QuestionStatus.Sending -> {
                Answers(question)
                Text("Envoi de la réponse…", color = JeedomTvColors.TextMuted, fontSize = 24.sp)
            }
            is QuestionStatus.Sent ->
                Text(
                    "Réponse envoyée : ${status.answer}",
                    color = JeedomTvColors.Accent,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
            is QuestionStatus.Failed ->
                Text(status.message, color = JeedomTvColors.Error, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Réponses côte à côte ; si elles ne tiennent pas sur une ligne, elles passent à la suivante. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Answers(question: Question) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        question.answers.forEachIndexed { index, answer ->
            val selected = index == question.selected
            Row(
                modifier = Modifier
                    .scale(if (selected) 1.08f else 1f)
                    .background(if (selected) JeedomTvColors.Accent else JeedomTvColors.SurfaceVariant, AnswerShape)
                    .border(
                        width = if (selected) 4.dp else 2.dp,
                        color = if (selected) JeedomTvColors.Text else JeedomTvColors.TextMuted.copy(alpha = 0.5f),
                        shape = AnswerShape,
                    )
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (selected) JeedomTvColors.OnAccent else JeedomTvColors.Text
                if (question.answers.size <= 9) {
                    Text("${index + 1}", color = color.copy(alpha = 0.7f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    answer,
                    color = color,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Barre qui se vide en continu jusqu'à la fermeture, avec les secondes restantes. */
@Composable
private fun Countdown(question: Question) {
    // Vise la valeur de la seconde suivante : la barre descend sans à-coups entre deux ticks.
    val target = ((question.remainingSec - 1).coerceAtLeast(0).toFloat() / question.timeoutSec).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(target, tween(1_000, easing = LinearEasing), label = "compte à rebours")
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(JeedomTvColors.SurfaceVariant, cornerRadius = radius)
                drawRoundRect(
                    if (question.remainingSec <= 5) JeedomTvColors.Error else JeedomTvColors.Accent,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = radius,
                )
            }
        }
        Text("${question.remainingSec} s", color = JeedomTvColors.TextMuted, fontSize = 20.sp)
    }
}
