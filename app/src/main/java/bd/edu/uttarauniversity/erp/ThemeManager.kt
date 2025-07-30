package bd.edu.uttarauniversity.erp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ThemeManager(private val dataStore: DataStore<Preferences>) {

    private val isDarkThemeKey = booleanPreferencesKey("is_dark_theme")

    val isDarkTheme: Flow<Boolean> = dataStore.data.map {
        it[isDarkThemeKey] ?: false
    }

    suspend fun toggleTheme() {
        dataStore.edit {
            val currentTheme = it[isDarkThemeKey] ?: false
            it[isDarkThemeKey] = !currentTheme
        }
    }
}