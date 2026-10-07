package be.jeedomtv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Service au premier plan : garde le processus (et donc la boucle des changements du contrôleur)
 * en vie quand la TV affiche une autre application, pour recevoir les ordres de Jeedom à tout moment.
 */
class JeedomTvService : Service() {

    private val app get() = application as JeedomTvApp

    /** Entrée et sortie de veille : l'état de l'écran est signalé, et au rallumage la boucle repart. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            app.controller.onScreenChanged(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }

    /** Retour du réseau après une coupure (le premier rappel, à l'enregistrement, est ignoré). */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        @Volatile
        private var lost = false

        override fun onLost(network: Network) {
            lost = true
        }

        override fun onAvailable(network: Network) {
            if (lost) {
                lost = false
                // Rappel sur un thread du système : le contrôleur vit sur le thread principal.
                ContextCompat.getMainExecutor(app).execute { app.controller.onNetworkMaybeRestored() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        app.controller.onScreenChanged(getSystemService(PowerManager::class.java).isInteractive)
        // Diffusions du système seulement : le récepteur n'a pas à être joignable par d'autres applications.
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    /*
     * Chaque démarrage redemande le premier plan, pas seulement la création : TCL (TclAppBoot)
     * le refuse au service relancé après une mise à jour, et l'ouverture de l'application, qui
     * redémarre le service, doit pouvoir le lui rendre.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        return START_STICKY
    }

    private fun promoteToForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: IllegalStateException) {
            // Android 12+ : premier plan refusé à ce moment ; le processus continue sans lui.
            Log.w(TAG, "premier plan refusé", e)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pilotage par Jeedom", NotificationManager.IMPORTANCE_MIN)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("En attente des ordres de Jeedom")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jeedom"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "JeedomTv"

        /**
         * Démarre (ou relance) le service. Android 12+ refuse un service au premier plan démarré
         * depuis l'arrière-plan hors des cas permis (démarrage de la TV, mise à jour…) : l'application
         * ne doit pas planter pour autant, l'ouverture de l'écran le redémarrera.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, JeedomTvService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "service au premier plan refusé pour l'instant", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "service au premier plan refusé", e)
            }
        }
    }
}
