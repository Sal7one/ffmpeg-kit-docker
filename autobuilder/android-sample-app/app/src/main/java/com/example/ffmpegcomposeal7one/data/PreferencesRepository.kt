package com.example.ffmpegcomposeal7one.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DATASTORE_NAME: String = "user_prefs"

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

object PreferenceKeys {
    val LAST_TREE_URI: Preferences.Key<String> = stringPreferencesKey("last_tree_uri")
    val LAST_FORMAT: Preferences.Key<String> = stringPreferencesKey("last_format")
    val LAST_BITRATE_KBPS: Preferences.Key<Int> = intPreferencesKey("last_bitrate_kbps")
}

class PreferencesRepository(private val context: Context) {

    val lastTreeUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[PreferenceKeys.LAST_TREE_URI]
    }

    val lastFormat: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[PreferenceKeys.LAST_FORMAT]
    }

    val lastBitrateKbps: Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[PreferenceKeys.LAST_BITRATE_KBPS]
    }

    suspend fun setLastTreeUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(PreferenceKeys.LAST_TREE_URI)
            } else {
                prefs[PreferenceKeys.LAST_TREE_URI] = uri
            }
        }
    }

    suspend fun setLastFormat(formatExtension: String) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LAST_FORMAT] = formatExtension
        }
    }

    suspend fun setLastBitrateKbps(bitrateKbps: Int) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LAST_BITRATE_KBPS] = bitrateKbps
        }
    }
}


