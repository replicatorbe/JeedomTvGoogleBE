package be.jeedomtv.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Configuration de Jeedom stockée dans les Preferences DataStore de l'application. */
class DataStoreSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    override suspend fun load(): JeedomConfig? {
        val prefs = readPreferences()
        val host = prefs[HOST]?.takeIf { it.isNotBlank() } ?: return null
        return JeedomConfig(host = host, key = prefs[KEY].orEmpty())
    }

    override suspend fun save(config: JeedomConfig) {
        dataStore.edit {
            it[HOST] = config.host
            it[KEY] = config.key
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(HOST)
            prefs.remove(KEY)
        }
    }

    private suspend fun readPreferences(): Preferences =
        dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .first()

    private companion object {
        val HOST = stringPreferencesKey("host")
        val KEY = stringPreferencesKey("key")
    }
}
