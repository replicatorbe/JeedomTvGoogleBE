package be.jeedomtv.model

/** Persistance de la configuration de Jeedom. */
interface SettingsRepository {
    suspend fun load(): JeedomConfig?
    suspend fun save(config: JeedomConfig)
    suspend fun clear()

    /** Lanceurs et écrans de veille ignorés au premier plan ; null : jamais réglés (liste par défaut). */
    suspend fun loadIgnoredPackages(): Set<String>? = null

    suspend fun saveIgnoredPackages(packages: Set<String>) = Unit
}
