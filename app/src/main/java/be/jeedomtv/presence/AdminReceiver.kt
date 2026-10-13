package be.jeedomtv.presence

import android.app.admin.DeviceAdminReceiver

/**
 * Administrateur de l'appareil, pour une seule règle : verrouiller (mettre en veille) l'écran,
 * `DevicePolicyManager.lockNow()`. Activé une fois par adb (`dpm set-active-admin`, voir tools/).
 * Pour désinstaller l'application : `dpm remove-active-admin` d'abord.
 */
class AdminReceiver : DeviceAdminReceiver()
