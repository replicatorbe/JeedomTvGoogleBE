package be.jeedomtv.view

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import be.jeedomtv.controller.AppController
import be.jeedomtv.model.AppState
import be.jeedomtv.model.JeedomConfig

/**
 * Formulaire de configuration : adresse de Jeedom et clé de la TV. Ici, la navigation utilise
 * le focus Compose normal (le contrôleur ne reçoit pas les flèches sur cet écran).
 */
@Composable
fun SetupView(state: AppState, controller: AppController) {
    val initial = state.config
    // Brouillon du formulaire : état purement visuel, réinitialisé si la config du modèle change.
    var host by rememberSaveable(initial) { mutableStateOf(initial?.host.orEmpty()) }
    // Clé enregistrée masquée tant qu'on n'y touche pas (null = pas encore modifiée).
    var keyDraft by rememberSaveable(initial) { mutableStateOf<String?>(null) }
    val keyField = KeyField(saved = initial?.key.orEmpty(), draft = keyDraft)
    var validationError by remember { mutableStateOf<String?>(null) }

    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstField.requestFocus() }

    fun submit() {
        validationError = when {
            host.isBlank() -> "L'adresse de Jeedom est obligatoire."
            keyField.value.isBlank() -> "La clé de la TV est obligatoire."
            else -> null
        }
        if (validationError == null) {
            controller.submitSetup(JeedomConfig(host = host.trim(), key = keyField.value.trim()))
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .padding(vertical = 24.dp)
                .background(JeedomTvColors.Surface, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Configuration de Jeedom",
                color = JeedomTvColors.Text,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "La clé est affichée sur la page de l'équipement de cette TV, dans le plugin Jeedom TV.",
                color = JeedomTvColors.TextMuted,
                fontSize = 18.sp,
            )

            FormField(
                label = "Adresse de Jeedom (IP ou nom d'hôte)",
                value = host,
                onValueChange = { host = it },
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.focusRequester(firstField),
            )
            FormField(
                label = "Clé de la TV",
                value = keyField.shown,
                onValueChange = { keyDraft = keyField.edit(it).draft },
                imeAction = ImeAction.Done,
                onDone = ::submit,
            )

            val error = validationError ?: state.error
            if (error != null) {
                Text(error, color = JeedomTvColors.Error, fontSize = 20.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Version discrète : utile pour savoir quel APK tourne sur la TV.
                Text(
                    appVersionLabel,
                    color = JeedomTvColors.TextMuted.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = ::submit) {
                    Text("Connexion", fontSize = 22.sp)
                }
            }
        }
    }
}

/** Champ texte « TV » : libellé au-dessus, gros texte, bordure accentuée quand il a le focus. */
@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            color = if (focused) JeedomTvColors.Accent else JeedomTvColors.TextMuted,
            fontSize = 18.sp,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = JeedomTvColors.Text, fontSize = 24.sp),
            cursorBrush = SolidColor(JeedomTvColors.Accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                onDone = { onDone() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                // Flèches haut/bas : passer au champ voisin (sinon le champ garde le focus).
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key.nativeKeyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> focusManager.moveFocus(FocusDirection.Down)
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                }
                .background(
                    if (focused) JeedomTvColors.SurfaceVariant else Color.Black.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp),
                )
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) JeedomTvColors.Accent else JeedomTvColors.TextMuted.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
