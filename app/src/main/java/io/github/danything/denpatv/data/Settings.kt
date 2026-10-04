package io.github.danything.denpatv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 繋ぐ先。`token` は家の外の denpa に登録したときだけ */
data class Connection(val server: String, val token: String?)

/**
 * 覚えておくもの: 繋ぐ先 (とトークン)・ライブの画質・CM を飛ばすか。
 *
 * **トークンは暗号化せずアプリの領域に置く。** EncryptedSharedPreferences (androidx.security-crypto) は
 * 2025 年に全部非推奨になった。アプリの領域は他のアプリから読めず、トークンは denpa の画面からいつでも外せる
 * (docs/libraries.md)
 */
class Settings(private val context: Context) {
    private val serverKey = stringPreferencesKey("server")
    private val tokenKey = stringPreferencesKey("token")
    private val lastServerKey = stringPreferencesKey("last_server")
    private val liveQualityKey = stringPreferencesKey("live_quality")
    private val skipCmKey = booleanPreferencesKey("skip_cm")
    private val lastServiceKey = longPreferencesKey("last_service")

    /** 繋ぐ先。まだ無ければ null */
    val connection: Flow<Connection?> = context.dataStore.data.map { prefs ->
        prefs[serverKey]?.let { Connection(it, prefs[tokenKey]) }
    }

    /** 前に繋いでいた先 (外れたあと、繋ぐ画面に最初から入れておく) */
    val lastServer: Flow<String?> = context.dataStore.data.map { it[lastServerKey] ?: it[serverKey] }

    /** `LiveQuality` の名前。未設定なら null (端末に合わせて選ぶ) */
    val liveQuality: Flow<String?> = context.dataStore.data.map { it[liveQualityKey] }

    /** 既定で飛ばす (denpa が CM の区切りをチャプターに書いた録画だけ効く) */
    val skipCm: Flow<Boolean> = context.dataStore.data.map { it[skipCmKey] ?: true }

    suspend fun connect(server: String, token: String?) {
        context.dataStore.edit {
            it[serverKey] = server
            it[lastServerKey] = server
            if (token == null) it.remove(tokenKey) else it[tokenKey] = token
        }
    }

    /** 繋ぐ先を忘れる (外したとき・トークンが効かなくなったとき)。繋ぐ画面に戻る */
    suspend fun disconnect() {
        context.dataStore.edit {
            it.remove(serverKey)
            it.remove(tokenKey)
        }
    }

    /** 最後に観ていた局。ライブを開くとまずそこが映る (denpa の画面のライブと同じ) */
    val lastService: Flow<Long?> = context.dataStore.data.map { it[lastServiceKey] }

    suspend fun setLastService(id: Long) {
        context.dataStore.edit { it[lastServiceKey] = id }
    }

    suspend fun setLiveQuality(quality: LiveQuality) {
        context.dataStore.edit { it[liveQualityKey] = quality.name }
    }

    suspend fun setSkipCm(skip: Boolean) {
        context.dataStore.edit { it[skipCmKey] = skip }
    }
}
