package be.jeedomtv.presence

import android.service.notification.NotificationListenerService

/**
 * Écouteur de notifications : il ne lit aucune notification. Il n'existe que parce qu'Android
 * réserve la liste des sessions média (`MediaSessionManager.getActiveSessions`) aux écouteurs
 * activés. Activé une fois par adb (`cmd notification allow_listener`, voir tools/).
 */
class MediaListenerService : NotificationListenerService()
