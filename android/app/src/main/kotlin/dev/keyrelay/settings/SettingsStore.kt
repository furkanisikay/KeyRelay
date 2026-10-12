package dev.keyrelay.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// A corrupt file resets the settings instead of crashing on every start; the user pairs again.
private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The app's settings in one DataStore file; every write transforms the whole [AppSettings]. */
object SettingsStore {
    fun flow(context: Context): Flow<AppSettings> = context.applicationContext.settingsDataStore.data.map(AppSettings::from)

    suspend fun current(context: Context): AppSettings = flow(context).first()

    suspend fun update(context: Context, transform: (AppSettings) -> AppSettings) {
        context.applicationContext.settingsDataStore.edit { prefs -> transform(AppSettings.from(prefs)).writeTo(prefs) }
    }
}
