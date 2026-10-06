package be.jeedomtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Démarrage de la TV, ou mise à jour de l'application (qui arrête son processus) : le service
 * redémarre sans attendre qu'on ouvre l'application, et les ordres de Jeedom arrivent de nouveau.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // L'Application est déjà créée à ce stade et démarre le service ; on le relance par sûreté.
            JeedomTvService.start(context)
        }
    }
}
