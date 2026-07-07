package com.example.smb

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smb_settings")

class ConnectionStorage(private val context: Context) {

    companion object {
        val KEY_NAME = stringPreferencesKey("smb_name")
        val KEY_IP = stringPreferencesKey("smb_ip")
        val KEY_PORT = stringPreferencesKey("smb_port")
        val KEY_SHARE = stringPreferencesKey("smb_share")
        val KEY_USERNAME = stringPreferencesKey("smb_username")
        val KEY_PASSWORD = stringPreferencesKey("smb_password")
    }

    val connectionFlow: Flow<SmbConnectionInfo?> = context.dataStore.data.map { preferences ->
        val ip = preferences[KEY_IP]
        if (ip.isNullOrBlank()) {
            null
        } else {
            SmbConnectionInfo(
                name = preferences[KEY_NAME] ?: ip,
                ip = ip,
                port = preferences[KEY_PORT]?.toIntOrNull() ?: 445,
                shareName = preferences[KEY_SHARE] ?: "",
                username = preferences[KEY_USERNAME] ?: "",
                password = preferences[KEY_PASSWORD] ?: ""
            )
        }
    }

    suspend fun saveConnection(info: SmbConnectionInfo) {
        context.dataStore.edit { preferences ->
            preferences[KEY_NAME] = info.name
            preferences[KEY_IP] = info.ip
            preferences[KEY_PORT] = info.port.toString()
            preferences[KEY_SHARE] = info.shareName
            preferences[KEY_USERNAME] = info.username
            preferences[KEY_PASSWORD] = info.password
        }
    }

    suspend fun clearConnection() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_NAME)
            preferences.remove(KEY_IP)
            preferences.remove(KEY_PORT)
            preferences.remove(KEY_SHARE)
            preferences.remove(KEY_USERNAME)
            preferences.remove(KEY_PASSWORD)
        }
    }
}

data class SmbConnectionInfo(
    val name: String,
    val ip: String,
    val port: Int = 445,
    val shareName: String = "",
    val username: String = "",
    val password: String = ""
)
