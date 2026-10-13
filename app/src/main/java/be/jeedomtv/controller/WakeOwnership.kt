package be.jeedomtv.controller

import be.jeedomtv.model.WokeBy

/**
 * Propriété de l'allumage (contrat, « Présence humaine et allumage ») : qui a allumé l'écran, et
 * si un humain a pris la main depuis un allumage par Jeedom. Sans dépendance Android ; les heures
 * sont celles d'une horloge monotone en ms, veille comprise.
 *
 * 1. L'écran s'allume parce que l'application l'a demandé → `jeedom`.
 * 2. Il s'allume sans demande (télécommande, CEC, Google Home) → `human`…
 * 3. … sauf si un ordre avec `wake` arrive dans les [reattributeWindowMs] qui suivent, sans touche
 *    entre-temps : l'allumage revient à Jeedom (le plugin a réveillé la TV par un autre chemin).
 * 4. Après un allumage par Jeedom, la première touche, le premier changement d'application qui ne
 *    vient pas d'un ordre ou la première lecture d'un média → `humanTookOver`. Les
 *    [ignoreAfterWakeMs] qui suivent l'allumage sont ignorées (événements parasites du CEC).
 *
 * Tout est remis à zéro à la mise en veille. Application démarrée écran allumé : `wokeBy` inconnu.
 */
class WakeOwnership(
    private val ignoreAfterWakeMs: Long = IGNORE_AFTER_WAKE_MS,
    private val reattributeWindowMs: Long = REATTRIBUTE_WINDOW_MS,
    /** Délai entre la demande de réveil et l'allumage effectif au-delà duquel ce n'est plus nous. */
    private val ownWakeWindowMs: Long = OWN_WAKE_WINDOW_MS,
) {
    /** null : écran éteint, ou allumé avant que l'application ne l'observe. */
    var wokeBy: WokeBy? = null
        private set

    private var tookOver = false

    /** `humanTookOver` du contrat : sans objet (false) hors d'un allumage par Jeedom. */
    val humanTookOver: Boolean
        get() = wokeBy == WokeBy.Jeedom && tookOver

    /** null tant que l'état de l'écran n'a jamais été observé. */
    private var screenOn: Boolean? = null
    private var screenOnAt: Long? = null
    private var wakeRequestedAt: Long? = null

    /** Une touche (hors période ignorée) depuis l'allumage : la règle 3 ne s'applique plus. */
    private var keySinceScreenOn = false

    /** État de l'écran observé ; le premier n'est attribué à personne. */
    fun onScreen(on: Boolean, now: Long) {
        val before = screenOn
        screenOn = on
        if (before == on) return
        if (!on) {
            wokeBy = null
            tookOver = false
            screenOnAt = null
            keySinceScreenOn = false
            return
        }
        screenOnAt = now
        keySinceScreenOn = false
        tookOver = false
        // Premier état connu : l'écran était déjà allumé, on ne sait pas par qui.
        if (before == null) {
            wokeBy = null
            return
        }
        val requested = wakeRequestedAt
        wakeRequestedAt = null
        wokeBy = if (requested != null && now - requested in 0..ownWakeWindowMs) WokeBy.Jeedom else WokeBy.Human
    }

    /** L'application va allumer l'écran (ordre avec `wake`, écran éteint). */
    fun onWakeRequested(now: Long) {
        wakeRequestedAt = now
    }

    /** Ordre avec `wake` reçu écran allumé : règle 3. */
    fun onWakeOrder(now: Long) {
        if (screenOn != true || wokeBy != WokeBy.Human || keySinceScreenOn) return
        val since = screenOnAt ?: return
        if (now - since <= reattributeWindowMs) {
            wokeBy = WokeBy.Jeedom
            tookOver = false
        }
    }

    fun onKey(now: Long) {
        if (!countsAsHuman(now)) return
        keySinceScreenOn = true
        tookOver = true
    }

    /** Changement d'application au premier plan qui ne vient pas d'un ordre. */
    fun onForegroundChangedByHuman(now: Long) {
        if (countsAsHuman(now)) tookOver = true
    }

    /** Un média passe en lecture. */
    fun onPlaybackStarted(now: Long) {
        if (countsAsHuman(now)) tookOver = true
    }

    /**
     * Règle d'or : la TV ne se met en veille d'elle-même que si Jeedom l'a allumée, qu'aucun humain
     * n'a pris la main, et qu'aucune question n'est affichée.
     */
    fun mayAutoSleep(questionShown: Boolean): Boolean =
        wokeBy == WokeBy.Jeedom && !tookOver && !questionShown

    private fun countsAsHuman(now: Long): Boolean {
        if (screenOn != true) return false
        val since = screenOnAt ?: return true
        return now - since >= ignoreAfterWakeMs
    }

    companion object {
        const val IGNORE_AFTER_WAKE_MS = 2_000L
        const val REATTRIBUTE_WINDOW_MS = 60_000L
        const val OWN_WAKE_WINDOW_MS = 15_000L
    }
}
