package io.github.danything.denpatv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 覚えておくのは繋ぐ先の denpa だけ */
class Settings(private val context: Context) {
    private val serverKey = stringPreferencesKey("server")

    val server: Flow<String?> = context.dataStore.data.map { it[serverKey] }

    suspend fun setServer(url: String) {
        context.dataStore.edit { it[serverKey] = url }
    }
}
