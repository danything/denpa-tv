package io.github.danything.denpatv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 覚えておくもの: 繋ぐ先の denpa・ライブの画質・CM を飛ばすか */
class Settings(private val context: Context) {
    private val serverKey = stringPreferencesKey("server")
    private val liveQualityKey = stringPreferencesKey("live_quality")
    private val skipCmKey = booleanPreferencesKey("skip_cm")

    val server: Flow<String?> = context.dataStore.data.map { it[serverKey] }

    /** `LiveQuality` の名前。未設定なら null (端末に合わせて選ぶ) */
    val liveQuality: Flow<String?> = context.dataStore.data.map { it[liveQualityKey] }

    /** 既定で飛ばす (denpa が CM の区切りをチャプターに書いた録画だけ効く) */
    val skipCm: Flow<Boolean> = context.dataStore.data.map { it[skipCmKey] ?: true }

    suspend fun setServer(url: String) {
        context.dataStore.edit { it[serverKey] = url }
    }

    suspend fun setLiveQuality(quality: LiveQuality) {
        context.dataStore.edit { it[liveQualityKey] = quality.name }
    }

    suspend fun setSkipCm(skip: Boolean) {
        context.dataStore.edit { it[skipCmKey] = skip }
    }
}
