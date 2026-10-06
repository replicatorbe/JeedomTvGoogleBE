package be.jeedomtv.model

/** Persistance de la configuration de Jeedom. */
interface SettingsRepository {
    suspend fun load(): JeedomConfig?
    suspend fun save(config: JeedomConfig)
    suspend fun clear()
}
