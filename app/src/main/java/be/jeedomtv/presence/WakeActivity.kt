package be.jeedomtv.presence

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager

/**
 * Activité invisible qui allume l'écran (`setTurnScreenOn`, `FLAG_TURN_SCREEN_ON`), puis se ferme
 * dès qu'il est allumé : la TV montre alors ce qu'elle montrait avant la veille. Démarrée depuis
 * l'arrière-plan grâce à la permission « afficher par-dessus les autres applications ».
 */
class WakeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var waitedMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
    }

    override fun onResume() {
        super.onResume()
        handler.post(::finishWhenAwake)
    }

    private fun finishWhenAwake() {
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        if (interactive || waitedMs >= MAX_WAIT_MS) {
            Log.i(TAG, if (interactive) "écran allumé" else "écran toujours éteint après ${MAX_WAIT_MS / 1000} s")
            finish()
            overridePendingTransition(0, 0)
            return
        }
        waitedMs += STEP_MS
        handler.postDelayed(::finishWhenAwake, STEP_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "JeedomTv"
        const val STEP_MS = 200L
        const val MAX_WAIT_MS = 8_000L
    }
}
