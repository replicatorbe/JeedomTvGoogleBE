package be.jeedomtv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import be.jeedomtv.controller.ColorKeyFilter

/**
 * Service d'accessibilité : capte les touches de couleur de la télécommande quand une autre
 * application est affichée (TV Player, YouTube…), pour ouvrir le panneau sur la page associée.
 * La règle (quelles touches, quand) est dans [ColorKeyFilter] ; le contrôleur vit dans l'Application.
 *
 * Activé une fois par adb (voir le README). Sans lui, l'application marche comme avant :
 * les touches de couleur ne fonctionnent alors que dans l'application et sur le panneau.
 */
class ColorKeyService : AccessibilityService() {

    private var filter: ColorKeyFilter? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as JeedomTvApp
        filter = ColorKeyFilter(state = { app.model.state.value }, onCommand = { app.controller.onCommand(it) })
        Log.i(TAG, "service des touches de couleur connecté")
    }

    /** Appelé sur le thread principal, comme le contrôleur. */
    override fun onKeyEvent(event: KeyEvent): Boolean = try {
        filter?.onKey(event.keyCode, event.action, event.repeatCount) ?: false
    } catch (e: RuntimeException) {
        // Jamais de télécommande bloquée à cause de l'application : la touche passe.
        Log.w(TAG, "touche de couleur non traitée", e)
        false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        filter?.clear()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "service des touches de couleur arrêté")
        filter = null
        return super.onUnbind(intent)
    }

    private companion object {
        const val TAG = "JeedomTv"
    }
}
