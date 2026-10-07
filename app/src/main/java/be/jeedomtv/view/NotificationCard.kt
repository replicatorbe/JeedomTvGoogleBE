package be.jeedomtv.view

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
fun Banner.isMediaCard(videoAllowed: Boolean): Boolean = (video != null && videoAllowed) || image != null

/**
 * Notification de Jeedom : carte « image dans l'image » si elle a une vidéo ou une image,
 * carte texte compacte sinon. [videoAllowed] : faux quand une question avec vidéo garde le décodeur.
 */
@Composable
fun NotificationView(banner: Banner, videoAllowed: Boolean, modifier: Modifier = Modifier) {
    if (banner.isMediaCard(videoAllowed)) {
        NotificationCard(banner, banner.video?.takeIf { videoAllowed }, modifier)
    } else {
        TextNotificationCard(banner, modifier)
    }
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
            .cardEnter(banner, fromStart = banner.corner.isStart)
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

/** Titre (gras, 16 sp, blanc) puis message (13 sp, blanc à 80 %), coupés proprement. */
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
                fontSize = if (alone) 15.sp else 13.sp,
                lineHeight = if (alone) 19.sp else 17.sp,
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
fun NotificationCard(banner: Banner, video: VideoUrl?, modifier: Modifier = Modifier) {
    val image by rememberDecodedImage(banner.imageBytes, maxWidth = CARD_IMAGE_MAX_PX, maxHeight = CARD_IMAGE_MAX_PX)
    var playing by remember(banner) { mutableStateOf(false) }
    val accent = banner.accent

    Box(
        modifier
            .cardEnter(banner, fromStart = banner.corner.isStart)
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
            image != null -> Image(image!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Image en cours de téléchargement.
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
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "attente")
        val value by transition.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
        value
    } else {
        0.6f
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        MdiIcon("cctv", Color.White.copy(alpha = alpha), 36.dp)
        if (text.isNotEmpty()) {
            Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** « ● EN DIRECT » : pilule sombre, point rouge qui pulse doucement. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "direct")
    val dot by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1_000), RepeatMode.Reverse), label = "point")
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).alpha(dot).background(LiveRed, CircleShape))
        Text("EN DIRECT", color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val LiveRed = Color(0xFFFF3B30)

/** Fine barre du temps restant, de toute la largeur à rien, en bas de la carte. */
@Composable
private fun RemainingBar(banner: Banner, color: Color, modifier: Modifier = Modifier) {
    val remaining = remember(banner) { Animatable(1f) }
    LaunchedEffect(banner) {
        remaining.animateTo(0f, tween(banner.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), easing = LinearEasing))
    }
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(color.copy(alpha = 0.9f), size = size.copy(width = size.width * remaining.value))
    }
}


/** Plus grand côté visé au décodage de l'image d'une carte (400 dp ≈ 800 px). */
private const val CARD_IMAGE_MAX_PX = 960
