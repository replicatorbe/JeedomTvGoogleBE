package be.jeedomtv.view

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import be.jeedomtv.model.Banner
import be.jeedomtv.model.VideoUrl

/** Largeur des cartes de notification (vidéo, image, texte) : environ 800 px sur la TV. */
private val CardWidth = 400.dp

/** Icône d'une notification qui n'en donne pas : une cloche, plutôt que l'icône « inconnue ». */
internal const val DEFAULT_NOTIFICATION_ICON = "mdi:bell-outline"

/** La notification a-t-elle une image ou une vidéo à montrer ? Sinon, carte texte. */
fun Banner.isMediaCard(videoAllowed: Boolean): Boolean = (video != null && videoAllowed) || (image != null && !imageFailed)

/**
 * Clé d'animation d'une carte : son identité ([Banner.id]), stable quand l'image arrive ou que la
 * carte change de fenêtre. Banner porte un ByteArray : son égalité de data class change à chaque copie.
 */
internal val Banner.cardKey: Any get() = if (id != 0L) id else this

/**
 * Notification de Jeedom : carte « image dans l'image » si elle a une vidéo ou une image,
 * carte texte compacte sinon. [videoAllowed] : faux quand une question avec vidéo garde le décodeur.
 */
@Composable
fun NotificationView(banner: Banner, videoAllowed: Boolean, modifier: Modifier = Modifier, waiting: Int = 0) {
    // Image décodée ici : des octets illisibles font revenir à la carte texte, comme un échec de
    // téléchargement, au lieu d'une attente sans fin.
    val decoded by rememberDecodedImageResult(banner.imageBytes, maxWidth = CARD_IMAGE_WIDTH_PX, maxHeight = CARD_IMAGE_HEIGHT_PX)
    val video = banner.video?.takeIf { videoAllowed }
    Box(modifier) {
        if (banner.isMediaCard(videoAllowed) && (video != null || decoded != DecodedImage.Unreadable)) {
            NotificationCard(banner, video, (decoded as? DecodedImage.Ready)?.bitmap)
        } else {
            TextNotificationCard(banner)
        }
        // D'autres notifications attendent leur tour : petit badge dans le coin.
        if (waiting > 0) WaitingBadge(waiting, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

/** « +2 » : notifications en attente derrière celle-ci. */
@Composable
private fun WaitingBadge(count: Int, modifier: Modifier = Modifier) {
    Text(
        "+$count",
        color = Color.White,
        fontSize = 13.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .border(1.dp, CardOutline, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Couleur d'accent d'une notification : celle de son icône, sinon le bleu doux. */
private val Banner.accent: Color
    get() = iconColor?.let { Color(it) } ?: SoftBlue

/**
 * Notification texte (portail, alarme, colis, rappels…) : carte compacte façon toast, de la
 * largeur de la carte vidéo et de la hauteur du texte. Grande pastille d'icône à gauche (cloche
 * par défaut), titre en gras puis message sur trois lignes au plus, barre du temps restant.
 * Sans titre, le message seul, centré verticalement contre la pastille.
 */
@Composable
fun TextNotificationCard(banner: Banner, modifier: Modifier = Modifier) {
    val accent = banner.accent
    Box(
        modifier
            .cardEnter(banner.cardKey, fromStart = banner.corner.isStart)
            .width(CardWidth)
            .jeedomCard(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconPill(banner.icon ?: DEFAULT_NOTIFICATION_ICON, accent, 40.dp)
            NotificationText(banner, messageLines = 3)
        }
        if (banner.durationMs > 0) RemainingBar(banner, accent, Modifier.align(Alignment.BottomStart))
    }
}

/** Titre (gras, 16 sp, blanc) puis message (14 sp, blanc à 80 %), coupés proprement. */
@Composable
private fun NotificationText(banner: Banner, messageLines: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (banner.title.isNotBlank()) {
            Text(
                banner.title,
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (banner.message.isNotBlank()) {
            // Sans titre, le message est le seul texte : en blanc, un peu plus grand.
            val alone = banner.title.isBlank()
            Text(
                banner.message,
                color = if (alone) Color.White else CardTextSecondary,
                fontSize = if (alone) 15.sp else 14.sp,
                lineHeight = if (alone) 19.sp else 18.sp,
                maxLines = messageLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Incrustation soignée : la vidéo (ou l'image) est la carte, 16:9, coins arrondis, liseré très
 * fin ; titre et message s'incrustent en bas sur un dégradé ; badge « EN DIRECT » pendant la
 * lecture ; fine barre du temps restant. Entrée en glissement et fondu depuis le bord du coin.
 * Seulement des formes simples : pas de flou, rien de lourd pour la TV.
 */
@Composable
fun NotificationCard(banner: Banner, video: VideoUrl?, image: ImageBitmap?, modifier: Modifier = Modifier) {
    var playing by remember(banner.cardKey) { mutableStateOf(false) }
    val accent = banner.accent

    Box(
        modifier
            .cardEnter(banner.cardKey, fromStart = banner.corner.isStart)
            .width(CardWidth)
            .aspectRatio(16f / 9f)
            .jeedomCard(surface = false)
            .background(CardBackground),
    ) {
        // Média en plein cadre. TextureView : le clip arrondi s'y applique (pas avec une SurfaceView).
        when {
            video != null -> LiveVideo(
                video,
                placeholder = image,
                modifier = Modifier.fillMaxSize(),
                onPlayingChange = { playing = it },
                background = Color.Transparent,
                waiting = { failed -> CardWaiting(if (failed) "Vidéo indisponible" else "Connexion à la caméra…", pulse = !failed) },
            )
            image != null -> Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Image en cours de téléchargement (un échec rend la carte texte, voir isMediaCard).
            else -> CardWaiting("", pulse = true)
        }

        if (video != null && playing) LiveBadge(Modifier.align(Alignment.TopStart).padding(10.dp))

        // Texte incrusté en bas, sur un dégradé du transparent au noir.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(start = 14.dp, end = 14.dp, top = 28.dp, bottom = 14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                banner.icon?.let { IconPill(it, accent, 30.dp) }
                NotificationText(banner, messageLines = 2)
            }
        }

        if (banner.durationMs > 0) {
            RemainingBar(banner, accent, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** Fond de la carte tant que rien n'est affiché : sombre, jamais un rectangle noir vide. */
private val CardBackground = Brush.linearGradient(listOf(Color(0xFF1C2430), Color(0xFF0E1218)))

/** Attente sans image : petite icône de caméra et un indicateur discret qui pulse. */
@Composable
private fun CardWaiting(text: String, pulse: Boolean) {
    val pulsing = if (pulse) {
        rememberInfiniteTransition(label = "attente")
            .animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    } else {
        null
    }
    val alpha = { pulsing?.value ?: 0.6f }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // Alpha lu au dessin (graphicsLayer) : l'animation ne recompose rien.
        Box(Modifier.graphicsLayer { this.alpha = alpha() }) { MdiIcon("cctv", Color.White, 36.dp) }
        if (text.isNotEmpty()) {
            Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** « ● EN DIRECT » : pilule sombre, point rouge qui pulse doucement. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "direct")
    val dot = transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1_000), RepeatMode.Reverse), label = "point")
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Alpha lu au dessin : le point pulse sans recomposer la carte.
        Box(Modifier.size(8.dp).graphicsLayer { alpha = dot.value }.background(LiveRed, CircleShape))
        Text("EN DIRECT", color = Color.White, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val LiveRed = Color(0xFFFF3B30)

/**
 * Fine barre du temps restant, en bas de la carte : elle part de ce qu'il reste vraiment
 * ([Banner.endsAtMs], horloge `elapsedRealtime`), aussi après un changement de fenêtre.
 */
@Composable
private fun RemainingBar(banner: Banner, color: Color, modifier: Modifier = Modifier) {
    val leftMs = { if (banner.endsAtMs > 0) banner.endsAtMs - SystemClock.elapsedRealtime() else banner.durationMs }
    val remaining = remember(banner.cardKey) { Animatable(remainingFraction(leftMs(), banner.durationMs)) }
    LaunchedEffect(banner.cardKey) {
        val left = leftMs().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        remaining.animateTo(0f, tween(left, easing = LinearEasing))
    }
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(color.copy(alpha = 0.9f), size = size.copy(width = size.width * remaining.value))
    }
}


/**
 * Taille visée au décodage de l'image d'une carte 16:9 de 400 dp (≈ 800 × 450 px), un peu au-dessus.
 * Aux proportions de la carte : une cible carrée laissait une photo 1920 × 1080 en pleine taille.
 */
internal const val CARD_IMAGE_WIDTH_PX = 960
internal const val CARD_IMAGE_HEIGHT_PX = 540

/** Part de la barre encore pleine, pour [leftMs] restantes sur [durationMs]. */
internal fun remainingFraction(leftMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 1f else (leftMs.toFloat() / durationMs).coerceIn(0f, 1f)
