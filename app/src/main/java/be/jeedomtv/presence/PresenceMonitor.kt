package be.jeedomtv.presence

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import be.jeedomtv.controller.AppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Relève les signaux de présence que le service d'accessibilité ne donne pas, et les transmet au
 * contrôleur (thread principal) :
 *
 * - écran de veille : `ACTION_DREAMING_STARTED` / `STOPPED` ;
 * - permissions accordées → `capabilities` (le service d'accessibilité peut avoir été désactivé
 *   par une mise à jour : relu à chaque passage) ;
 * - application au premier plan par `UsageStatsManager`, quand l'accessibilité manque ;
 * - média en lecture : sessions média (écouteur de notifications), repli `isMusicActive`.
 *
 * Écran allumé : un relevé toutes les [ACTIVE_POLL_MS] ; en veille, toutes les [IDLE_POLL_MS].
 */
class PresenceMonitor(
    private val context: Context,
    private val controller: AppController,
    private val scope: CoroutineScope,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listener = ComponentName(context, MediaListenerService::class.java)

    /** Dernier paquet passé au premier plan selon les statistiques d'utilisation. */
    private var usageForeground: String? = null
    private var usageQueriedUntil = 0L

    private val dreamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller.onSystemDreaming(intent.action == Intent.ACTION_DREAMING_STARTED)
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            dreamReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            while (true) {
                poll()
                delay(if (power.isInteractive) ACTIVE_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    /** Relevé immédiat (démarrage, connexion du service d'accessibilité). */
    fun poll() {
        val capabilities = PresencePermissions.capabilities(context)
        controller.onCapabilities(capabilities, PresencePermissions.statuses(context))
        if (!PresencePermissions.accessibilityConnected(context)) {
            controller.onForegroundApp(if (capabilities.foreground) foregroundFromUsageStats() else null)
        }
        controller.onMediaPlaying(playing(capabilities.media))
    }

    /** Dernière activité reprise d'après les statistiques d'utilisation (null si inconnue). */
    private fun foregroundFromUsageStats(): String? {
        val now = System.currentTimeMillis()
        val from = if (usageQueriedUntil == 0L) now - USAGE_LOOKBACK_MS else usageQueriedUntil
        try {
            val events = usage.queryEvents(from, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) usageForeground = event.packageName
            }
            usageQueriedUntil = now
        } catch (e: SecurityException) {
            usageForeground = null
        }
        logForeground(usageForeground, "statistiques")
        return usageForeground
    }

    /**
     * Une session média en lecture (hors la nôtre) ; sans écouteur de notifications, repli sur
     * `AudioManager.isMusicActive` (nos vidéos de caméras n'ont pas de son : elles n'y comptent pas).
     */
    private fun playing(mediaGranted: Boolean): Boolean {
        if (mediaGranted) {
            try {
                return sessions.getActiveSessions(listener).any {
                    it.packageName != context.packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING
                }
            } catch (e: SecurityException) {
                // Écouteur retiré entre-temps : repli.
            }
        }
        return audio.isMusicActive
    }

    private var lastLogged: String? = null

    private fun logForeground(packageName: String?, source: String) {
        if (packageName == lastLogged) return
        lastLogged = packageName
        Log.d(TAG, "premier plan ($source) : $packageName")
    }

    private companion object {
        const val TAG = "JeedomTv"
        const val ACTIVE_POLL_MS = 5_000L
        const val IDLE_POLL_MS = 60_000L
        const val USAGE_LOOKBACK_MS = 60 * 60_000L
    }
}
