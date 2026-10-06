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
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        app.controller.onScreenChanged(getSystemService(PowerManager::class.java).isInteractive)
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JeedomTvService::class.java))
        }
    }
}
