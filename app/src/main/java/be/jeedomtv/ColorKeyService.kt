package be.jeedomtv

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import be.jeedomtv.controller.ColorKeyFilter

/**
 * Service d'accessibilité (activé une fois par adb, voir tools/setup-tv.sh). Deux rôles :
 *
 * - **présence humaine** : il observe chaque touche de la télécommande et l'application au
 *   premier plan (`TYPE_WINDOW_STATE_CHANGED`), pour `inUse` et `wokeBy` ; l'observation ne
 *   consomme aucune touche ;
 * - **touches de couleur** : il capte ces touches quand une autre application est affichée
 *   (TV Player, YouTube…), pour ouvrir le panneau sur la page associée ; la règle (quelles
 *   touches, quand) est dans [ColorKeyFilter].
 *
 * Le nom de la classe ne change pas : c'est le composant activé par adb sur les TV.
 * Sans lui, l'application marche comme avant, sans les touches ni le premier plan (`capabilities`).
 */
class ColorKeyService : AccessibilityService() {

    private var filter: ColorKeyFilter? = null

    /** Composants déjà vérifiés : activité (premier plan) ou autre fenêtre (dialogue, superposition). */
    private val activities = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_CACHED
    }

    private var lastForeground: String? = null

    private val app get() = application as JeedomTvApp

    override fun onServiceConnected() {
        super.onServiceConnected()
        filter = ColorKeyFilter(state = { app.model.state.value }, onCommand = { app.controller.onCommand(it) })
        instance = this
        Log.i(TAG, "service d'accessibilité connecté")
        app.presenceChanged()
    }

    /**
     * Appelé sur le thread principal, comme le contrôleur. Chaque appui est d'abord signalé à la
     * présence (sans jamais être consommé pour elle), puis passe par la règle des touches de couleur.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean = try {
        if (event.action == KeyEvent.ACTION_DOWN) app.controller.onRemoteKeyObserved()
        filter?.onKey(event.keyCode, event.action, event.repeatCount) ?: false
    } catch (e: RuntimeException) {
        // Jamais de télécommande bloquée à cause de l'application : la touche passe.
        Log.w(TAG, "touche non traitée", e)
        false
    }

    /**
     * Application au premier plan : seules les fenêtres d'activité comptent (pas un dialogue
     * système, le volume, le clavier ou nos superpositions).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        if (!isActivity(packageName, className)) return
        if (packageName != lastForeground) {
            lastForeground = packageName
            // Niveau debug : pour identifier l'application TV ou HDMI du fabricant (adb logcat -s JeedomTv).
            Log.d(TAG, "premier plan : $packageName ($className)")
        }
        app.controller.onForegroundApp(packageName)
    }

    private fun isActivity(packageName: String, className: String): Boolean =
        activities.getOrPut("$packageName/$className") {
            try {
                packageManager.getActivityInfo(ComponentName(packageName, className), 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

    override fun onInterrupt() {
        filter?.clear()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "service d'accessibilité arrêté")
        filter = null
        instance = null
        app.presenceChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JeedomTv"
        private const val MAX_CACHED = 200

        /** Service relié (thread principal) : touches observables, mise en veille de repli. */
        var instance: ColorKeyService? = null
            private set
    }
}
