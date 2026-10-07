package be.jeedomtv.model.driver

import be.jeedomtv.model.Changes
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.PingInfo
import be.jeedomtv.model.TileAction
import be.jeedomtv.model.TvState

/**
 * Accès au plugin Jeedom `jeetvbe` (contrat : docs/api.md). Le reste de l'application ne
 * connaît que cette interface. Toutes les méthodes lèvent [JeedomException] en cas d'échec
 * ([AuthenticationException] si la clé est refusée).
 */
interface JeedomDriver {
    /** Vérifie la clé. */
    suspend fun ping(): PingInfo

    /** Pages et tuiles de la TV, avec les valeurs actuelles. */
    suspend fun layout(): Layout

    /**
     * Exécute [action] sur la tuile [tile] ; retourne la valeur lue juste après (peut être null).
     * [value] : valeur numérique (curseur, volet) ; [choice] : valeur texte d'un choix (`select`).
     */
    suspend fun exec(tile: String, action: TileAction, value: Double? = null, choice: String? = null): String?

    /**
     * Attente longue des changements de valeurs depuis le curseur [since]
     * (null : réponse immédiate donnant le curseur de départ).
     */
    suspend fun changes(since: String?): Changes

    /** Signale à Jeedom l'état de la TV (visible, écran allumé, page affichée). */
    suspend fun state(state: TvState)

    /**
     * Répond à la question [ask] par [answer]. Lève [JeedomException] avec [JeedomException.httpCode]
     * 404 (question expirée ou déjà répondue) ou 422 (réponse hors liste).
     */
    suspend fun answer(ask: String, answer: String)

    /**
     * Image jointe à un ordre (`GET ?action=image`), JPEG ou PNG, 5 Mo au plus.
     * Lève [JeedomException] (404 : inconnue ou expirée ; trop grande).
     */
    suspend fun image(id: String): ByteArray
}

fun interface JeedomDriverFactory {
    fun create(config: JeedomConfig): JeedomDriver
}

/** Échec d'un appel au plugin ; le message est en français, affichable tel quel. */
open class JeedomException(
    message: String,
    cause: Throwable? = null,
    /** Code HTTP de la réponse, null pour une erreur réseau. */
    val httpCode: Int? = null,
) : Exception(message, cause)

/** Clé absente, inconnue, ou équipement désactivé (HTTP 401). */
class AuthenticationException(message: String) : JeedomException(message, httpCode = 401)
