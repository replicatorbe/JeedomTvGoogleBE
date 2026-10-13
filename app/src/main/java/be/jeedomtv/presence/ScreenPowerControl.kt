package be.jeedomtv.presence

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import be.jeedomtv.ColorKeyService
import be.jeedomtv.controller.ScreenPower

/**
 * Allumer et éteindre l'écran de la TV.
 *
 * - Allumer : [WakeActivity], démarrée depuis l'arrière-plan (exemption « afficher par-dessus »).
 * - Éteindre : `DevicePolicyManager.lockNow()` (administrateur de l'appareil), à défaut l'action
 *   globale « verrouiller l'écran » du service d'accessibilité.
 */
class ScreenPowerControl(private val context: Context) : ScreenPower {

    override fun wake(): Boolean {
        if (!PresencePermissions.canWake(context)) return false
        return try {
            context.startActivity(
                Intent(context, WakeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            )
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "allumage refusé", e)
            false
        }
    }

    override fun sleep(): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm != null && dpm.isAdminActive(adminComponent(context))) {
            try {
                dpm.lockNow()
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "lockNow refusé", e)
            }
        }
        val service = ColorKeyService.instance ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    companion object {
        private const val TAG = "JeedomTv"

        fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)
    }
}
