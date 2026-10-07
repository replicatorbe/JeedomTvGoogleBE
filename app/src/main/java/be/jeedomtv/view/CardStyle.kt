package be.jeedomtv.view

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Famille visuelle des cartes de Jeedom TV (notifications, questions) : coins arrondis de 16 dp,
 * liseré blanc de 1 dp à 15 %, ombre douce, fond sombre profond légèrement translucide, pastille
 * d'icône colorée. Uniquement des formes simples : pas de flou, rien de lourd pour la TV.
 */

/** Coins de toutes les cartes. */
val CardShape = RoundedCornerShape(16.dp)

/** Fond sombre profond, légèrement translucide : la télé se devine derrière. */
val CardSurface = Color(0xEB10141B)

/** Liseré très fin des cartes. */
val CardOutline = Color.White.copy(alpha = 0.15f)

/** Accent par défaut (icône, barre de temps) quand Jeedom n'en donne pas : un bleu doux. */
val SoftBlue = Color(0xFF8AB4F8)

/** Texte secondaire des cartes (message) : blanc à 80 %. */
val CardTextSecondary = Color.White.copy(alpha = 0.8f)

/** Texte discret (rappel des touches, secondes) : blanc à 55 %. */
val CardTextMuted = Color.White.copy(alpha = 0.55f)

/** Carte : ombre douce, coins arrondis, fond (si [surface]) et liseré. */
fun Modifier.jeedomCard(shape: Shape = CardShape, surface: Boolean = true, elevation: Dp = 14.dp): Modifier =
    this
        .shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .then(if (surface) Modifier.background(CardSurface) else Modifier)
        .border(1.dp, CardOutline, shape)

/** Pastille ronde : fond de la couleur à ~20 %, icône MDI dans la couleur. */
@Composable
fun IconPill(icon: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
        MdiIcon(icon, color, size * 0.58f)
    }
}

/**
 * Entrée d'une carte (~250 ms) : glissement de quelques dizaines de dp depuis le bord de son coin
 * ([fromStart] : bord gauche, sinon droit), et fondu. Rejouée pour chaque nouveau [key].
 */
@Composable
fun Modifier.cardEnter(key: Any, fromStart: Boolean): Modifier {
    val enter = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { enter.animateTo(1f, tween(CARD_ENTER_MS, easing = FastOutSlowInEasing)) }
    val slidePx = with(LocalDensity.current) { 32.dp.toPx() }
    return graphicsLayer {
        alpha = enter.value
        translationX = (1f - enter.value) * slidePx * (if (fromStart) -1f else 1f)
    }
}

private const val CARD_ENTER_MS = 250
