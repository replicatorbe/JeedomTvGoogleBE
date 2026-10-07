package be.jeedomtv.view

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text

/**
 * Police Material Design Icons (@mdi/font, Pictogrammers Free License, police Apache 2.0),
 * embarquée dans les assets avec sa correspondance nom → code (tools/mdi/update_mdi.py).
 * Chargée une seule fois par processus, à la première icône (ou en avance par [warmUp]).
 */
class MdiFont private constructor(context: Context) {
    val family: FontFamily = FontFamily(
        androidx.compose.ui.text.font.Typeface(
            android.graphics.Typeface.createFromAsset(context.assets, "$ASSET_DIR/materialdesignicons-webfont.ttf"),
        ),
    )
    private val codes: Map<String, Int> =
        context.assets.open("$ASSET_DIR/codepoints.txt").bufferedReader().use { parseMdiCodepoints(it.readText()) }

    /** Code de l'icône [value] (`mdi:nom` ou `nom`), ou null si le nom est inconnu. */
    fun codeOf(value: String?): Int? = mdiName(value)?.let { codes[it] }

    /** Code de l'icône générique, pour un nom inconnu. */
    val genericCode: Int? get() = codes[GENERIC_ICON]

    companion object {
        private const val ASSET_DIR = "mdi"

        @Volatile
        private var instance: MdiFont? = null

        fun get(context: Context): MdiFont = instance ?: synchronized(this) {
            instance ?: MdiFont(context.applicationContext).also { instance = it }
        }

        /** Chargement en avance (hors du thread principal) : la première icône s'affiche sans attente. */
        fun warmUp(context: Context) {
            get(context)
        }
    }
}

/** Icône affichée pour un nom inconnu ou absent. */
internal const val GENERIC_ICON = "help-circle-outline"

/** « mdi:Weather-Rainy » → « weather-rainy » ; sans préfixe, la valeur est lue comme un nom MDI. */
fun mdiName(value: String?): String? =
    value?.trim()?.lowercase()?.removePrefix("mdi:")?.trim()?.takeIf { it.isNotEmpty() }

/** Lignes « nom code_hexa » (les lignes « # » et vides sont ignorées). */
fun parseMdiCodepoints(text: String): Map<String, Int> {
    val map = HashMap<String, Int>(8192)
    text.lineSequence().forEach { line ->
        if (line.isBlank() || line.startsWith("#")) return@forEach
        val space = line.indexOf(' ')
        if (space <= 0) return@forEach
        line.substring(space + 1).trim().toIntOrNull(16)?.let { map[line.substring(0, space)] = it }
    }
    return map
}

/**
 * Icône Material Design Icons [name] (`mdi:weather-rainy`, ou sans préfixe) de couleur [color],
 * dans un carré de [size]. Un nom inconnu donne une icône générique.
 */
@Composable
fun MdiIcon(name: String?, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val font = MdiFont.get(LocalContext.current)
    val code = font.codeOf(name) ?: font.genericCode
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (code == null) return@Box
        val fontSize = with(LocalDensity.current) { size.toSp() }
        Text(
            String(Character.toChars(code)),
            color = color,
            style = TextStyle(
                fontFamily = font.family,
                fontSize = fontSize,
                lineHeight = fontSize,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}
