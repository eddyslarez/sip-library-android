package com.eddyslarez.siplibrary.data.store

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore for settings persistence
 *
 * @author Eddys Larez
 */
class SettingsDataStore(private val application: Application) {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sip_settings")

    private val IS_IN_BACKGROUND = booleanPreferencesKey("is_in_background")
    private val PUSH_MODE_STATE = stringPreferencesKey("push_mode_state")
    private val ACCOUNTS_IN_PUSH_MODE = stringPreferencesKey("accounts_in_push_mode")

    suspend fun setInBackgroundValue(value: Boolean) {
        application.dataStore.edit { preferences ->
            preferences[IS_IN_BACKGROUND] = value
        }
    }

    fun getInBackgroundFlow(): Flow<Boolean> {
        return application.dataStore.data.map { preferences ->
            preferences[IS_IN_BACKGROUND] ?: false
        }
    }

    suspend fun savePushModeState(mode: String, accounts: Set<String>) {
        application.dataStore.edit { preferences ->
            preferences[PUSH_MODE_STATE] = mode
            preferences[ACCOUNTS_IN_PUSH_MODE] = accounts.joinToString(",")
        }
    }

    fun getPushModeStateFlow(): Flow<Pair<String, Set<String>>> {
        return application.dataStore.data.map { preferences ->
            val mode = preferences[PUSH_MODE_STATE] ?: "FOREGROUND"
            val accountsString = preferences[ACCOUNTS_IN_PUSH_MODE] ?: ""
            val accounts = if (accountsString.isNotEmpty()) {
                accountsString.split(",").toSet()
            } else {
                emptySet()
            }
            Pair(mode, accounts)
        }
    }
}