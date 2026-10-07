package be.jeedomtv.view

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import be.jeedomtv.model.AppState
import be.jeedomtv.model.Question
import be.jeedomtv.model.QuestionStatus

/** Coins de la photo ou de la vidéo dans la carte. */
private val MediaShape = RoundedCornerShape(12.dp)

/** Plus grand côté visé au décodage de la photo jointe. */
private const val PHOTO_MAX_PX = 1280

/** Icône des questions (le contrat n'en transmet pas). */
private const val QUESTION_ICON = "mdi:help-circle-outline"

/** Réponse focalisée : fond blanc, texte sombre. */
private val FocusedText = Color(0xFF10141B)
private val SentGreen = Color(0xFF81C995)
private val FailedRed = Color(0xFFF28B82)

/**
 * Question de Jeedom, dans la famille des cartes (fond sombre, liseré fin, pastille d'icône,
 * réponses en pilules) et lisible à 3 m. Sans photo : carte centrée. Avec photo ou vidéo (sonnette) :
 * le média en grand à gauche, la question à droite. Les touches passent par le contrôleur.
 */
@Composable
fun QuestionDialog(question: Question, modifier: Modifier = Modifier) {
    // Photo jointe (portier…) : décodée à la taille utile, hors du thread principal.
    val photo by rememberDecodedImage(question.imageBytes, maxWidth = PHOTO_MAX_PX, maxHeight = PHOTO_MAX_PX)
    val image = photo
    // Vidéo en direct tant qu'on choisit : dès la réponse envoyée, le lecteur est libéré.
    val video = question.video?.takeIf { question.status == QuestionStatus.Choosing }
    if (image == null && question.video == null) {
        Column(
            modifier
                .widthIn(min = 560.dp, max = 820.dp)
                .jeedomCard()
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            QuestionContent(question, large = true)
        }
    } else {
        // Avec photo ou vidéo : le média en grand à gauche (la moitié), la question à droite.
        Row(
            modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 1400.dp)
                .jeedomCard()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var playing by remember(question.ask) { mutableStateOf(false) }
            val media = Modifier.weight(0.5f).heightIn(max = 480.dp).aspectRatio(16f / 9f).clip(MediaShape)
            Box(media) {
                when {
                    // La vidéo remplace la photo, à la même place ; la photo sert d'attente et de repli.
                    video != null -> LiveVideo(
                        video,
                        placeholder = image,
                        modifier = Modifier.matchParentSize(),
                        onPlayingChange = { playing = it },
                    )
                    image != null -> Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    // Réponse envoyée, sans photo : le cadre sombre reste, la mise en page ne saute pas.
                    else -> Box(Modifier.matchParentSize().background(Color.Black))
                }
                if (video != null && playing) LiveBadge(Modifier.align(Alignment.TopStart).padding(12.dp))
            }
            Column(
                Modifier.weight(0.5f),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                QuestionContent(question, large = true)
            }
        }
    }
}

/**
 * Fenêtre de la question en superposition, par-dessus une autre application (vidéo) :
 *
 * - [Banner] : question sans image, en bandeau compact dans le tiers inférieur de l'écran.
 *   La vidéo (caméra en direct, film) reste visible et sans voile au-dessus ;
 * - [Dialog] : question avec image (photo du portier), en grand au centre, vidéo assombrie.
 */
enum class QuestionWindowKind { None, Banner, Dialog }

/** Fenêtre de question à afficher en superposition pour l'état [state]. */
fun questionWindowKind(state: AppState): QuestionWindowKind {
    val question = state.question?.takeIf { it.inOverlay } ?: return QuestionWindowKind.None
    // L'identifiant suffit : la mise en page ne change pas quand la photo arrive.
    return if (question.image == null && question.video == null) QuestionWindowKind.Banner else QuestionWindowKind.Dialog
}

/** Rappel des touches d'une question : les chiffres 1 à 9 ne valent que pour 9 réponses au plus. */
fun questionHelpText(question: Question): String {
    val count = question.answers.size
    val choose = if (count in 2..9) "◀ ▶ ou 1-$count : choisir" else if (count > 9) "◀ ▶ : choisir" else null
    return listOfNotNull(choose, "OK : répondre", "Retour : fermer").joinToString(" · ")
}

/**
 * Question sans image par-dessus la vidéo : carte compacte centrée en bas de l'écran (~640 dp),
 * pour ne pas masquer la caméra affichée au-dessus. Question en haut, réponses, puis compte à
 * rebours et rappel des touches ; le texte trop long est coupé (trois lignes au plus).
 */
@Composable
fun QuestionBanner(question: Question, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 640.dp)
            .jeedomCard()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuestionHeader(question, large = false)
        when (val status = question.status) {
            // Réponses à gauche ; compte à rebours et rappel des touches à droite : peu de hauteur.
            QuestionStatus.Choosing, QuestionStatus.Sending -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Answers(question, large = false) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (status == QuestionStatus.Choosing) {
                        Countdown(question)
                        Text(questionHelpText(question), color = CardTextMuted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2)
                    } else {
                        Text("Envoi de la réponse…", color = CardTextMuted, fontSize = 14.sp)
                    }
                }
            }
            is QuestionStatus.Sent -> Result("mdi:check-circle-outline", SentGreen, "Réponse envoyée : ${status.answer}", large = false)
            is QuestionStatus.Failed -> Result("mdi:alert-circle-outline", FailedRed, status.message, large = false)
            QuestionStatus.AlreadyAnswered -> Result("mdi:information-outline", SoftBlue, ALREADY_ANSWERED, large = false)
            is QuestionStatus.AnsweredElsewhere -> Result("mdi:check-circle-outline", SoftBlue, answeredElsewhereText(status), large = false)
        }
    }
}

/** Pastille d'icône, titre et question. */
@Composable
private fun QuestionHeader(question: Question, large: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (large) 16.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconPill(QUESTION_ICON, SoftBlue, if (large) 48.dp else 40.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (question.title.isNotBlank()) {
                Text(
                    question.title,
                    color = Color.White,
                    fontSize = if (large) 18.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                question.message,
                color = Color.White,
                fontSize = if (large) 30.sp else 21.sp,
                lineHeight = if (large) 38.sp else 26.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (large) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** En-tête (pastille, titre, question), puis réponses et compte à rebours, ou le résultat. */
@Composable
private fun QuestionContent(question: Question, large: Boolean) {
    QuestionHeader(question, large)
    when (val status = question.status) {
        QuestionStatus.Choosing -> {
            Answers(question, large)
            Countdown(question)
            Text(questionHelpText(question), color = CardTextMuted, fontSize = 13.sp, maxLines = 1)
        }
        QuestionStatus.Sending -> {
            Answers(question, large)
            Text("Envoi de la réponse…", color = CardTextMuted, fontSize = 16.sp)
        }
        is QuestionStatus.Sent -> Result("mdi:check-circle-outline", SentGreen, "Réponse envoyée : ${status.answer}", large)
        is QuestionStatus.Failed -> Result("mdi:alert-circle-outline", FailedRed, status.message, large)
        QuestionStatus.AlreadyAnswered -> Result("mdi:information-outline", SoftBlue, ALREADY_ANSWERED, large)
        is QuestionStatus.AnsweredElsewhere -> Result("mdi:check-circle-outline", SoftBlue, answeredElsewhereText(status), large)
    }
}

/** Réponse refusée en 409 : une autre TV a répondu à la question (ton neutre). */
internal const val ALREADY_ANSWERED = "Déjà répondu"

/** « Réponse donnée sur TV salon : Ouvrir » (ordre `ask_close`). */
fun answeredElsewhereText(status: QuestionStatus.AnsweredElsewhere): String =
    "Réponse donnée sur ${status.by ?: "une autre TV"} : ${status.answer}"

/** Résultat de la réponse, affiché ~2 s avant la fermeture. */
@Composable
private fun Result(icon: String, color: Color, text: String, large: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconPill(icon, color, if (large) 40.dp else 34.dp)
        Text(text, color = color, fontSize = if (large) 26.sp else 20.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Réponses en pilules ; la sélection est blanche à texte sombre (et légèrement agrandie), les
 * autres ont un contour discret. Elles passent à la ligne si elles ne tiennent pas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Answers(question: Question, large: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(if (large) 16.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (large) 14.dp else 10.dp),
    ) {
        question.answers.forEachIndexed { index, answer ->
            val selected = index == question.selected
            val textColor = if (selected) FocusedText else Color.White
            Row(
                modifier = Modifier
                    .scale(if (selected) 1.05f else 1f)
                    .background(if (selected) Color.White else Color.White.copy(alpha = 0.08f), PillShape)
                    .border(1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.3f), PillShape)
                    .padding(horizontal = if (large) 26.dp else 20.dp, vertical = if (large) 12.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (question.answers.size <= 9) {
                    Text("${index + 1}", color = textColor.copy(alpha = 0.55f), fontSize = if (large) 18.sp else 15.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    answer,
                    color = textColor,
                    fontSize = if (large) 24.sp else 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val PillShape = RoundedCornerShape(50)

/** Fine barre qui se vide en continu jusqu'à la fermeture, avec les secondes restantes. */
@Composable
private fun Countdown(question: Question) {
    // Vise la valeur de la seconde suivante : la barre descend sans à-coups entre deux ticks.
    val target = ((question.remainingSec - 1).coerceAtLeast(0).toFloat() / question.timeoutSec).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(target, tween(1_000, easing = LinearEasing), label = "compte à rebours")
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = radius)
                drawRoundRect(
                    if (question.remainingSec <= 5) FailedRed else SoftBlue,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = radius,
                )
            }
        }
        Text("${question.remainingSec} s", color = CardTextMuted, fontSize = 14.sp)
    }
}
