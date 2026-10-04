package io.github.danything.denpatv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI

/**
 * denpa の外向けの口 (docs/api.md) を叩く。
 *
 * **家の LAN からはトークン無しで通る** (denpa の TRUSTED_NETWORKS)。家の外の denpa (OIDC でログインする構成) では、
 * テレビを denpa に登録して受け取ったトークンを `Authorization: Bearer` で付ける (`token`、README の「繋ぐ」)
 */
class DenpaApi(private val token: () -> String? = { null }) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun health(base: URI): Boolean = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/health") ?: return@withContext false
        try {
            Http.request(url).ok
        } catch (_: IOException) {
            false
        }
    }

    suspend fun services(base: URI): List<Service> = get(base, "api/services")

    /** 録画を新しい順に。`offset` から `limit` 件 (多いので少しずつ読む) */
    suspend fun recordings(base: URI, limit: Int = 60, offset: Int = 0): List<Recording> =
        get(base, "api/recordings?limit=$limit&offset=$offset")

    /**
     * どこまで観たかを預ける (`POST /api/recordings/<id>/resume`)。秒で渡す。
     * 末尾まで観たものは denpa が消す (次に開いたときエンドロールから始まらないように)
     */
    suspend fun saveResume(base: URI, id: Long, atSeconds: Double, lengthSeconds: Double) =
        withContext(Dispatchers.IO) {
            val url = BaseUrl.resolve(base, "api/recordings/$id/resume") ?: return@withContext
            try {
                Http.request(url, "POST", """{"at":$atSeconds,"length":$lengthSeconds}""", token())
            } catch (_: IOException) {
                // 覚えられなくても観るのは止めない
            }
        }

    /** 録画を消す (`DELETE /api/recordings/<id>`。消えれば 204)。消せたら true */
    suspend fun deleteRecording(base: URI, id: Long): Boolean = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/recordings/$id") ?: return@withContext false
        val res = try {
            Http.request(url, "DELETE", token = token())
        } catch (_: IOException) {
            return@withContext false
        }
        if (res.code == 401) throw Unauthorized(url)
        res.ok
    }

    /**
     * トークン無しで中に入れるか (局の一覧を引いてみる)。入れれば true (家の LAN = TRUSTED_NETWORKS)、
     * 断られれば false。denpa は家の外からのトークン無しを 401 で断る。認証を置いていない denpa や
     * 一部の口は 403 を返すことがあるので、それも「入れない」と読む
     */
    suspend fun openWithoutToken(base: URI): Boolean = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/services") ?: throw IOException("URL を組み立てられません")
        val res = Http.request(url)
        when {
            res.ok -> true
            res.code == 401 || res.code == 403 -> false
            else -> throw IOException("${res.code} $url")
        }
    }

    /** テレビを denpa に登録しはじめる (`POST api/device/code`、RFC 8628 の device authorization) */
    suspend fun deviceCode(base: URI, name: String): DeviceCode = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/device/code") ?: throw IOException("URL を組み立てられません")
        val res = Http.request(url, "POST", json.encodeToString(DeviceCodeRequest.serializer(), DeviceCodeRequest(name)))
        if (res.code == 429) throw IOException("登録を待っているテレビが多すぎます。しばらくしてからやり直してください")
        if (!res.ok) throw IOException("${res.code} $url")
        json.decodeFromString(DeviceCode.serializer(), res.text())
    }

    /** 登録が済んだかを聞く (`POST api/device/token`) */
    suspend fun deviceToken(base: URI, deviceCode: String): TokenResult = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/device/token") ?: throw IOException("URL を組み立てられません")
        val res = Http.request(url, "POST", json.encodeToString(DeviceTokenRequest.serializer(), DeviceTokenRequest(deviceCode)))
        if (res.ok) {
            TokenResult.Granted(json.decodeFromString(DeviceToken.serializer(), res.text()).token)
        } else {
            val error = runCatching {
                json.decodeFromString(JsonObject.serializer(), res.text())["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            TokenResult.Error(error ?: "http_${res.code}")
        }
    }

    /** このテレビのトークンを denpa から外す (`POST api/device/logout`、204) */
    suspend fun logout(base: URI) = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/device/logout") ?: return@withContext
        try {
            Http.request(url, "POST", token = token())
        } catch (_: IOException) {
            // 届かなくても、こちらはトークンを捨てる
        }
    }

    private suspend inline fun <reified T> get(base: URI, path: String): T = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, path) ?: throw IOException("URL を組み立てられません: $path")
        json.decodeFromString<T>(Http.get(url, token()))
    }
}

@Serializable
private data class DeviceCodeRequest(val name: String)

@Serializable
private data class DeviceTokenRequest(val deviceCode: String)

@Serializable
private data class DeviceToken(val token: String)

/** `api/device/code` の答え。URL は denpa の根からの相対 */
@Serializable
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Int,
    val interval: Int = 5,
)

sealed interface TokenResult {
    data class Granted(val token: String) : TokenResult
    /** `authorization_pending` / `slow_down` / `access_denied` / `expired_token` / `invalid_grant` など */
    data class Error(val error: String) : TokenResult
}
