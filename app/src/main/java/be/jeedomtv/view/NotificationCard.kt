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

/** Largeur de la carte image ou vidéo (16:9) : environ 800 px sur la TV. */
private val CardWidth = 400.dp
private val CardShape = RoundedCornerShape(16.dp)

/** Le bandeau a-t-il une image ou une vidéo à montrer ? Sinon, bandeau texte. */
fun Banner.isMediaCard(videoAllowed: Boolean): Boolean = (video != null && videoAllowed) || image != null

/**
 * Notification de Jeedom : carte « image dans l'image » si elle a une vidéo ou une image,
 * bandeau texte sinon. [videoAllowed] : faux quand une question avec vidéo garde le décodeur.
 */
@Composable
fun NotificationView(banner: Banner, videoAllowed: Boolean, modifier: Modifier = Modifier) {
    if (banner.isMediaCard(videoAllowed)) {
        NotificationCard(banner, banner.video?.takeIf { videoAllowed }, modifier)
    } else {
        BannerView(banner, modifier)
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
    val accent = banner.iconColor?.let { Color(it) } ?: Color.White

    // Entrée : glissement de quelques dizaines de dp depuis le bord du coin, et fondu (~250 ms).
    val enter = remember(banner) { Animatable(0f) }
    LaunchedEffect(banner) { enter.animateTo(1f, tween(ENTER_MS, easing = FastOutSlowInEasing)) }
    val slidePx = with(LocalDensity.current) { 32.dp.toPx() }
    val fromStart = banner.corner.isStart

    Box(
        modifier
            .graphicsLayer {
                alpha = enter.value
                translationX = (1f - enter.value) * slidePx * (if (fromStart) -1f else 1f)
            }
            .width(CardWidth)
            .aspectRatio(16f / 9f)
            .shadow(14.dp, CardShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CardShape)
            .background(CardBackground)
            .border(1.dp, Color.White.copy(alpha = 0.15f), CardShape),
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
                banner.icon?.let { icon ->
                    Box(
                        Modifier.size(30.dp).background(accent.copy(alpha = 0.22f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        MdiIcon(icon, accent, 18.dp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (banner.title.isNotBlank()) {
                        Text(
                            banner.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (banner.message.isNotBlank()) {
                        Text(
                            banner.message,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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

private const val ENTER_MS = 250

/** Plus grand côté visé au décodage de l'image d'une carte (400 dp ≈ 800 px). */
private const val CARD_IMAGE_MAX_PX = 960
