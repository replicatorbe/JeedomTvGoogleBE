package be.jeedomtv.presence

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import be.jeedomtv.ColorKeyService
import be.jeedomtv.model.Capabilities
import be.jeedomtv.model.PermissionStatus

/**
 * Permissions accordées par adb (script tools/setup-tv.sh), relues telles quelles : un signal
 * dont la permission manque est déclaré absent, jamais deviné.
 */
object PresencePermissions {

    const val ADMIN_LABEL = "Administrateur de l'appareil (mise en veille)"

    /** Service d'accessibilité coché dans les réglages (une mise à jour peut le décocher). */
    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val ours = ComponentName(context, ColorKeyService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    /** Coché et effectivement relié : les touches et le premier plan arrivent. */
    fun accessibilityConnected(context: Context): Boolean =
        ColorKeyService.instance != null && accessibilityEnabled(context)

    fun usageStatsGranted(context: Context): Boolean = opAllowed(context, AppOpsManager.OPSTR_GET_USAGE_STATS)

    fun notificationListenerGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Appop TURN_SCREEN_ON : n'existe qu'à partir d'Android 14 (null avant : sans objet).
     */
    fun turnScreenOnGranted(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            opAllowed(context, "android:turn_screen_on")
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun adminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            ?.isAdminActive(ScreenPowerControl.adminComponent(context)) == true

    /**
     * Le téléviseur déclare la fonction « administrateur de l'appareil » : sans elle, `lockNow` peut
     * être accepté sans éteindre l'écran (TCL Android 11).
     */
    fun deviceAdminSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)

    /** Mettre en veille : administrateur actif sur un téléviseur qui déclare la fonction. */
    fun canSleep(context: Context): Boolean = adminActive(context) && deviceAdminSupported(context)

    /**
     * Retire l'administrateur de l'appareil (préalable à la désinstallation) : adb ne peut retirer
     * qu'un administrateur de test, l'application peut toujours se retirer elle-même.
     */
    fun removeAdmin(context: Context) {
        context.getSystemService(DevicePolicyManager::class.java)
            ?.removeActiveAdmin(ScreenPowerControl.adminComponent(context))
    }

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Allumer l'écran : démarrer une activité depuis l'arrière-plan, et TURN_SCREEN_ON là où il existe. */
    fun canWake(context: Context): Boolean = overlayGranted(context) && turnScreenOnGranted(context) != false

    fun capabilities(context: Context): Capabilities {
        val accessibility = accessibilityConnected(context)
        return Capabilities(
            keys = accessibility,
            foreground = accessibility || usageStatsGranted(context),
            media = notificationListenerGranted(context),
            wake = canWake(context),
            sleep = canSleep(context),
        )
    }

    /** Pour l'écran de diagnostic, dans l'ordre du script d'installation. */
    fun statuses(context: Context): List<PermissionStatus> = listOf(
        PermissionStatus("Accessibilité (touches, premier plan)", accessibilityConnected(context)),
        PermissionStatus("Statistiques d'utilisation (premier plan, repli)", usageStatsGranted(context)),
        PermissionStatus("Écouteur de notifications (média en lecture)", notificationListenerGranted(context)),
        PermissionStatus("Afficher par-dessus les autres applications", overlayGranted(context)),
        PermissionStatus("Allumer l'écran (TURN_SCREEN_ON)", turnScreenOnGranted(context)),
        PermissionStatus(ADMIN_LABEL, adminActive(context)),
        PermissionStatus("Fonction android.software.device_admin du téléviseur", deviceAdminSupported(context)),
        PermissionStatus("Exemption d'économie d'énergie", batteryExempt(context)),
    )

    private fun opAllowed(context: Context, op: String): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(op, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
