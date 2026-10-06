package be.jeedomtv.model.driver

import be.jeedomtv.model.Changes
import be.jeedomtv.model.JeedomConfig
import be.jeedomtv.model.Layout
import be.jeedomtv.model.PingInfo
import be.jeedomtv.model.TileAction

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

    /** Exécute [action] sur la tuile [tile] ; retourne la valeur lue juste après (peut être null). */
    suspend fun exec(tile: String, action: TileAction, value: Double? = null): String?

    /**
     * Attente longue des changements de valeurs depuis le curseur [since]
     * (null : réponse immédiate donnant le curseur de départ).
     */
    suspend fun changes(since: String?): Changes
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
