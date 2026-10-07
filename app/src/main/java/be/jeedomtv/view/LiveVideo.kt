package be.jeedomtv.view

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import be.jeedomtv.model.VideoUrl
import kotlinx.coroutines.delay

/** Pause avant une nouvelle tentative après une erreur de lecture. */
private const val RETRY_DELAY_MS = 5_000L

/** Tentatives au plus : au-delà, l'image de repli reste (la TV n'a que deux décodeurs). */
private const val MAX_ATTEMPTS = 3

private enum class VideoStatus { Connecting, Playing, Failed }

/**
 * Vidéo en direct (caméra RTSP, flux HLS), sans le son, pour un bandeau ou une question.
 *
 * - [placeholder] (photo jointe) s'affiche en attendant la première image, et en repli si le
 *   flux ne vient pas ; sans elle, un fond noir et un mot d'attente.
 * - Un seul lecteur, libéré dès que le composable disparaît (bandeau fermé, réponse envoyée) ou
 *   que l'écran passe en arrière-plan : le décodeur matériel est rendu aussitôt.
 * - L'URL n'est jamais écrite dans les journaux (identifiants des caméras).
 */
@Composable
fun LiveVideo(video: VideoUrl, placeholder: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val context = LocalContext.current
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

        var attempt by remember(video) { mutableIntStateOf(0) }
        var status by remember(video) { mutableStateOf(VideoStatus.Connecting) }

        val player: ExoPlayer? = remember(video, started, attempt) {
            if (started && attempt < MAX_ATTEMPTS) createLivePlayer(context, video) else null
        }

        DisposableEffect(player) {
            if (player == null) return@DisposableEffect onDispose { }
            status = VideoStatus.Connecting
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    status = VideoStatus.Playing
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Le message d'erreur peut contenir l'URL : il n'est pas journalisé.
                    status = VideoStatus.Failed
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }

        // Nouvelle tentative après une erreur, quelques fois seulement ; le lecteur en erreur est
        // libéré par la recréation (clé `attempt`).
        LaunchedEffect(status, player) {
            if (status == VideoStatus.Failed && player != null) {
                delay(RETRY_DELAY_MS)
                attempt++
            }
        }

        if (player != null) {
            AndroidView(
                factory = { ctx -> createPlayerView(ctx) },
                update = { view -> view.player = player },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (status != VideoStatus.Playing || player == null) {
            if (placeholder != null) {
                Image(placeholder, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                val failed = status == VideoStatus.Failed || player == null
                Text(if (failed) "Vidéo indisponible" else "Connexion…", color = JeedomTvColors.TextMuted, fontSize = 16.sp)
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun createLivePlayer(context: Context, video: VideoUrl): ExoPlayer {
    // Petits tampons : la latence prime (sonnette, portail).
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(500, 2_000, 250, 500)
        .build()
    val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    val mediaSource = if (video.isRtsp) {
        // RTP sur TCP : bien plus fiable que l'UDP sur le Wi-Fi d'une TV (comme CameraOnTv).
        RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(video.url))
    } else {
        val item = MediaItem.Builder().setUri(video.url).apply {
            if (video.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        DefaultMediaSourceFactory(context).createMediaSource(item)
    }
    return ExoPlayer.Builder(context, renderersFactory)
        .setLoadControl(loadControl)
        .build()
        .apply {
            // Sans le son : la piste audio n'est même pas décodée.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            volume = 0f
            setMediaSource(mediaSource)
            playWhenReady = true
            prepare()
        }
}

@OptIn(UnstableApi::class)
private fun createPlayerView(context: Context): PlayerView =
    // SurfaceView (par défaut) : le chemin le plus économe pour le décodeur matériel.
    PlayerView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(AndroidColor.BLACK)
        setKeepContentOnPlayerReset(false)
        isFocusable = false
        isFocusableInTouchMode = false
    }
