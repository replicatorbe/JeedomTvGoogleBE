package be.jeedomtv.controller

import be.jeedomtv.model.Capabilities
import be.jeedomtv.model.PresenceReport
import be.jeedomtv.model.ShowMode
import be.jeedomtv.model.SleepOutcome
import be.jeedomtv.model.SleepResult
import be.jeedomtv.model.WokeBy

/**
 * Signaux de présence humaine (contrat, « Présence humaine et allumage ») et calcul de `inUse`.
 * Sans dépendance Android : le service d'accessibilité, les statistiques d'utilisation, les
 * sessions média et les diffusions du système l'alimentent par le contrôleur.
 */
class PresenceTracker(
    /** Notre paquet : au premier plan, il ne compte jamais comme un usage. */
    private val ownPackage: String,
    /** Lanceurs et écrans de veille : au premier plan, ils ne comptent pas non plus. */
    ignoredPackages: Set<String> = DEFAULT_IGNORED_PACKAGES,
    val ownership: WakeOwnership = WakeOwnership(),
) {
    var ignoredPackages: Set<String> = ignoredPackages
        set(value) {
            field = value.filter { it.isNotBlank() }.toSet()
        }

    var screenOn: Boolean = true
        private set
    var dreaming: Boolean = false
        private set
    var foreground: String? = null
        private set
    var playing: Boolean? = null
        private set
    var capabilities: Capabilities = Capabilities()
        private set

    /** Dernière touche observée ; null si aucune depuis le démarrage. */
    var lastKeyAt: Long? = null
        private set

    /** Début de l'inactivité : dernière touche, ou allumage de l'écran s'il est plus récent. */
    private var idleSince: Long? = null

    /** Une mise en veille a échoué : `capabilities.sleep` reste false jusqu'au redémarrage. */
    private var sleepFailed = false

    /** Issue du dernier ordre `sleep` (`lastSleep`) ; null tant qu'aucun n'a été traité. */
    var lastSleep: SleepResult? = null
        private set

    fun onScreen(on: Boolean, now: Long) {
        val before = screenOn
        screenOn = on
        ownership.onScreen(on, now)
        if (on && (!before || idleSince == null)) idleSince = now
    }

    fun onDreaming(dreaming: Boolean) {
        this.dreaming = dreaming
    }

    fun onCapabilities(capabilities: Capabilities) {
        this.capabilities = capabilities
        // Touches plus observables (service désactivé) : la dernière connue ne dit plus rien.
        if (!capabilities.keys) lastKeyAt = null
    }

    fun onKey(now: Long) {
        if (!screenOn) return
        lastKeyAt = now
        idleSince = now
        ownership.onKey(now)
    }

    /**
     * Retourne true si le paquet au premier plan a changé. Un changement d'application ne fait pas
     * une reprise en main (contrat, règle 4).
     */
    fun onForeground(packageName: String?): Boolean {
        if (packageName == foreground) return false
        foreground = packageName
        return true
    }

    fun onPlaying(playing: Boolean?, now: Long) {
        val started = playing == true && this.playing != true
        this.playing = playing
        if (started) ownership.onPlaybackStarted(now)
    }

    /** Ordre avec `wake` reçu écran allumé (règle 3). */
    fun onWakeOrder(now: Long) = ownership.onWakeOrder(now)

    fun onWakeRequested(now: Long) = ownership.onWakeRequested(now)

    /** Ordre `sleep` traité ; un échec retire `capabilities.sleep` jusqu'au redémarrage. */
    fun onSleepOutcome(id: Long?, outcome: SleepOutcome) {
        lastSleep = SleepResult(id, outcome)
        if (outcome == SleepOutcome.Failed) sleepFailed = true
    }

    /** Mise en veille d'elle-même (`sleepAfter`) qui a échoué : même conséquence, sans `lastSleep`. */
    fun onSleepFailed() {
        sleepFailed = true
    }

    /**
     * `inUse` : écran allumé hors écran de veille, et au moins l'un de : une touche depuis moins de
     * [KEY_IN_USE_MS], un média en lecture, une application au premier plan autre que nous, un
     * lanceur ou un écran de veille (chaîne TV, entrée HDMI, application en pause).
     *
     * Exception : allumée par Jeedom sans reprise en main → false, quelle que soit l'application
     * revenue au premier plan au réveil (l'application de l'entrée TV revient souvent d'elle-même).
     */
    fun inUse(now: Long): Boolean {
        if (!screenOn || dreaming) return false
        if (ownership.wokeBy == WokeBy.Jeedom && !ownership.humanTookOver) return false
        val recentKey = lastKeyAt?.let { now - it < KEY_IN_USE_MS } ?: false
        val app = foreground?.let { !isIgnored(it) } ?: false
        return recentKey || playing == true || app
    }

    /**
     * Secondes depuis la dernière touche, ou depuis l'allumage de l'écran s'il n'y en a pas eu depuis ;
     * null seulement si les touches ne sont pas observables.
     */
    fun idleSeconds(now: Long): Int? {
        if (!capabilities.keys) return null
        val since = idleSince ?: return null
        return ((now - since) / 1000).coerceAtLeast(0).toInt()
    }

    fun report(now: Long): PresenceReport = PresenceReport(
        inUse = inUse(now),
        foreground = foreground,
        playing = playing,
        dreaming = dreaming,
        wokeBy = ownership.wokeBy,
        humanTookOver = ownership.humanTookOver,
        capabilities = if (sleepFailed) capabilities.copy(sleep = false) else capabilities,
        lastSleep = lastSleep,
    )

    private fun isIgnored(packageName: String) = packageName == ownPackage || packageName in ignoredPackages

    companion object {
        const val KEY_IN_USE_MS = 10 * 60_000L

        /** Lanceur et écran de veille Ambiant de Google TV ; complétés à l'écran de configuration. */
        val DEFAULT_IGNORED_PACKAGES = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.apps.tv.dreamx",
        )

        /** Liste saisie (virgules, espaces ou retours à la ligne) → paquets. */
        fun parsePackages(text: String): Set<String> =
            text.split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}

/** Règles de décision du contrat, pures (testées sans contrôleur). */
object PresenceRules {
    /** `show` en `mode: auto` : notification discrète (et aucun changement d'application) si la TV est en usage. */
    fun showDiscreetly(mode: ShowMode, inUse: Boolean): Boolean = mode == ShowMode.Auto && inUse

    /** Ordre `sleep` : sans `force`, seulement si la règle d'or est vraie. */
    fun sleepAllowed(force: Boolean, mayAutoSleep: Boolean): Boolean = force || mayAutoSleep
}

/**
 * Quand signaler une touche à Jeedom (« Quand envoyer ») : à la première touche après au moins
 * [quietMs] sans touche, puis, tant que des touches arrivent, au plus une fois par [minIntervalMs].
 * Jamais à chaque touche. Les autres envois (changement d'un champ) comptent dans l'intervalle.
 */
class KeyReportThrottle(
    private val quietMs: Long = 60_000L,
    private val minIntervalMs: Long = 60_000L,
) {
    private var lastKeyAt: Long? = null
    private var lastSentAt: Long? = null

    /** Retourne true si l'état doit être envoyé maintenant à cause de cette touche. */
    fun onKey(now: Long): Boolean {
        val previous = lastKeyAt
        lastKeyAt = now
        val firstAfterQuiet = previous == null || now - previous >= quietMs
        val sent = lastSentAt
        if (firstAfterQuiet || sent == null || now - sent >= minIntervalMs) {
            lastSentAt = now
            return true
        }
        return false
    }

    /** Un état vient d'être envoyé (pour une autre raison) : il porte déjà `idleSeconds` à jour. */
    fun onSent(now: Long) {
        lastSentAt = now
    }
}
