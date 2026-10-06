package be.jeedomtv.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Couleurs partagées par les vues. */
object JeedomTvColors {
    val Background = Color(0xFF0A0A0C)
    val Surface = Color(0xFF1A1B20)
    val SurfaceVariant = Color(0xFF26272E)
    val Accent = Color(0xFF4FC3F7)
    val OnAccent = Color(0xFF00222E)
    val Text = Color(0xFFECECEF)
    val TextMuted = Color(0xFFA0A3AD)
    val Error = Color(0xFFFF6B6B)
    /** Fond d'un interrupteur allumé : ambré, pour ne pas le confondre avec la sélection (cyan). */
    val SwitchOn = Color(0xFFFFC857)
    val OnSwitchOn = Color(0xFF2E2000)
    val Overlay = Color(0xE6101116)
}

private val DarkColors = darkColorScheme(
    primary = JeedomTvColors.Accent,
    onPrimary = JeedomTvColors.OnAccent,
    background = JeedomTvColors.Background,
    onBackground = JeedomTvColors.Text,
    surface = JeedomTvColors.Surface,
    onSurface = JeedomTvColors.Text,
    surfaceVariant = JeedomTvColors.SurfaceVariant,
    onSurfaceVariant = JeedomTvColors.TextMuted,
    error = JeedomTvColors.Error,
)

/** Thème sombre « 10-foot UI » : fond quasi noir, accent cyan bien visible pour la sélection. */
@Composable
fun JeedomTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
