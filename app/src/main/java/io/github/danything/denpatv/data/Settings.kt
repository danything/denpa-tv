package io.github.danything.denpatv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 繋ぐ先。`token` は家の外の denpa に登録したときだけ */
data class Connection(val server: String, val token: String?)

/**
 * 覚えておくもの: 繋ぐ先 (とトークン)・ライブの画質と最後に観た局・CM を飛ばすか・録画の速さ・字幕・音声。
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
    private val speedKey = floatPreferencesKey("playback_speed")
    private val subtitlesKey = booleanPreferencesKey("subtitles")
    private val audioKey = stringPreferencesKey("audio_label")

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

    /**
     * 録画の速さ。**端末ごとに覚える** (denpa のブラウザの再生と同じ)。好みは端末で違うので、
     * サーバに置く続きの位置とは分ける
     */
    val playbackSpeed: Flow<Float> = context.dataStore.data.map { knownSpeed(it[speedKey]) }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[speedKey] = speed }
    }

    /** 最後に観ていた局。ライブを開くとまずそこが映る (denpa の画面のライブと同じ) */
    val lastService: Flow<Long?> = context.dataStore.data.map { it[lastServiceKey] }

    suspend fun setLastService(id: Long) {
        context.dataStore.edit { it[lastServiceKey] = id }
    }

    suspend fun setLiveQuality(quality: LiveQuality) {
        context.dataStore.edit { it[liveQualityKey] = quality.name }
    }

    /** 字幕を出すか (焼いた録画の PGS など)。既定は出す。端末ごと (ブラウザの再生と同じく観ながら変える) */
    val subtitles: Flow<Boolean> = context.dataStore.data.map { it[subtitlesKey] ?: true }

    suspend fun setSubtitles(on: Boolean) {
        context.dataStore.edit { it[subtitlesKey] = on }
    }

    /** 最後に選んだ音声の名前 (「解説ステレオ」など)。同じ名前の音声があればそれで始める */
    val audioLabel: Flow<String?> = context.dataStore.data.map { it[audioKey] }

    suspend fun setAudioLabel(label: String?) {
        context.dataStore.edit { if (label == null) it.remove(audioKey) else it[audioKey] = label }
    }

    suspend fun setSkipCm(skip: Boolean) {
        context.dataStore.edit { it[skipCmKey] = skip }
    }
}
