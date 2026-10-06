package be.jeedomtv.view

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Facteur de sous-échantillonnage (puissance de 2) pour qu'une image de [width]×[height] soit
 * décodée à peine plus grande que [maxWidth]×[maxHeight] : une photo de 4000 px ne doit pas
 * occuper 60 Mo sur une TV de 2 Go.
 */
fun sampleSizeFor(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= maxWidth && height / (sample * 2) >= maxHeight) sample *= 2
    return sample
}

/** Décode une image JPEG ou PNG, sous-échantillonnée ; null si les octets sont illisibles. */
fun decodeSampled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
        // Deux fois moins de mémoire qu'en ARGB, sans différence visible pour une photo.
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** Image décodée hors du thread principal ; null tant qu'elle ne l'est pas, ou si elle est illisible. */
@Composable
fun rememberDecodedImage(bytes: ByteArray?, maxWidth: Int, maxHeight: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, bytes) {
        value = bytes?.let {
            withContext(Dispatchers.Default) { runCatching { decodeSampled(it, maxWidth, maxHeight) }.getOrNull() }
        }
    }
