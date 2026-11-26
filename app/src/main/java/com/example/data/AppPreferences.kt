package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "prootbox_prefs")

data class UserSettings(
    val selectedContainerId: String? = null,
    val terminalFontSizeSp: Int = 13,
    val darkTheme: Boolean = true,
    val autoStartWayland: Boolean = true,
    val defaultVncHost: String = "127.0.0.1",
    val defaultVncPort: Int = 5901
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val SELECTED_CONTAINER_ID = stringPreferencesKey("selected_container_id")
        val TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val AUTO_START_WAYLAND = booleanPreferencesKey("auto_start_wayland")
        val DEFAULT_VNC_HOST = stringPreferencesKey("default_vnc_host")
        val DEFAULT_VNC_PORT = intPreferencesKey("default_vnc_port")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            selectedContainerId = prefs[Keys.SELECTED_CONTAINER_ID],
            terminalFontSizeSp = prefs[Keys.TERMINAL_FONT_SIZE] ?: 13,
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            autoStartWayland = prefs[Keys.AUTO_START_WAYLAND] ?: true,
            defaultVncHost = prefs[Keys.DEFAULT_VNC_HOST] ?: "127.0.0.1",
            defaultVncPort = prefs[Keys.DEFAULT_VNC_PORT] ?: 5901
        )
    }

    suspend fun setSelectedContainerId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.SELECTED_CONTAINER_ID)
            else prefs[Keys.SELECTED_CONTAINER_ID] = id
        }
    }

    suspend fun setTerminalFontSize(sp: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TERMINAL_FONT_SIZE] = sp.coerceIn(9, 24)
        }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DARK_THEME] = enabled
        }
    }

    suspend fun setVncEndpoint(host: String, port: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_VNC_HOST] = host
            prefs[Keys.DEFAULT_VNC_PORT] = port
        }
    }
}
