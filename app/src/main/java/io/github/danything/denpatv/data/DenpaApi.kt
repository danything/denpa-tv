package io.github.danything.denpatv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI

/**
 * denpa の外向けの口 (denpa の docs/api.md) を叩く。
 *
 * **家の LAN からはトークン無しで通る** (denpa の TRUSTED_NETWORKS)。家の外の denpa (OIDC でログインする構成) では、
 * テレビを denpa に登録して受け取ったトークンを `Authorization: Bearer` で付ける (`token`、README の「繋ぐ」)
 */
class DenpaApi(private val token: () -> String? = { null }) {
    suspend fun health(base: URI): Boolean = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/health") ?: return@withContext false
        try {
            Http.request(url).ok
        } catch (_: IOException) {
            false
        }
    }

    /**
     * 繋ぐ先として denpa らしいか。`api/health` が通り、返事が HTML でない (denpa は JSON を返す)。
     * 同じ機械の 80 で NAS やルータの画面がどのパスにも 200 の HTML を返していても、そちらを選ばない (`firstReachable`)
     */
    suspend fun looksLikeDenpa(base: URI): Boolean = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/health") ?: return@withContext false
        try {
            val response = Http.request(url)
            response.ok && !response.text().trimStart().startsWith("<")
        } catch (_: IOException) {
            false
        }
    }

    suspend fun services(base: URI): List<Service> = get(base, "api/services")

    /** 録画を新しい順に、全部 (画面は一番古い録画から開くので) */
    suspend fun recordings(base: URI): List<Recording> = get(base, "api/recordings")

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

    /**
     * **いま流れている番組を録る** (`POST api/services/<id>/record`)。ブラウザの denpa のライブの録画ボタンと同じで、
     * 予約は番組ごとに1本なので、何度押しても二重には録らない
     */
    suspend fun recordNow(base: URI, serviceId: Long): RecordResult = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/services/$serviceId/record") ?: return@withContext RecordResult.Failed("URL を組み立てられません")
        val res = try {
            Http.request(url, "POST", token = token())
        } catch (_: IOException) {
            return@withContext RecordResult.Failed("denpa に届きません")
        }
        if (res.code == 401) throw Unauthorized(url)
        val body = runCatching { lenientJson.decodeFromString(RecordResponse.serializer(), res.text()) }.getOrNull()
        when {
            res.ok && body?.recorded != null -> RecordResult.Recorded(body.recorded, body.reserved)
            // 口の無い古い denpa は SvelteKit の「Not Found」(断りの文言は日本語で来る)
            res.code == 404 && (body?.message == null || body.message == "Not Found") -> RecordResult.Unsupported
            // 通ったのに答えが読めない (版のずれ?)。予約できたかは分からない
            res.ok -> RecordResult.Failed("denpa の答えを読めません (${res.code})")
            else -> RecordResult.Failed(body?.message ?: "${res.code}")
        }
    }

    /** 番組の中身。古い denpa (口が無い) や読めないときは null */
    suspend fun recordingDetail(base: URI, id: Long): RecordingDetail? = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/recordings/$id/detail") ?: return@withContext null
        val res = try {
            Http.request(url, token = token())
        } catch (_: IOException) {
            return@withContext null
        }
        if (res.code == 401) throw Unauthorized(url)
        if (!res.ok) return@withContext null
        runCatching { lenientJson.decodeFromString(RecordingDetail.serializer(), res.text()) }.getOrNull()
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
        val res = Http.request(url, "POST", lenientJson.encodeToString(DeviceCodeRequest.serializer(), DeviceCodeRequest(name)))
        if (res.code == 429) throw IOException("登録を待っているテレビが多すぎます。しばらくしてからやり直してください")
        if (!res.ok) throw IOException("${res.code} $url")
        lenientJson.decodeFromString(DeviceCode.serializer(), res.text())
    }

    /** 登録が済んだかを聞く (`POST api/device/token`) */
    suspend fun deviceToken(base: URI, deviceCode: String): TokenResult = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/device/token") ?: throw IOException("URL を組み立てられません")
        val res = Http.request(url, "POST", lenientJson.encodeToString(DeviceTokenRequest.serializer(), DeviceTokenRequest(deviceCode)))
        if (res.ok) {
            TokenResult.Granted(lenientJson.decodeFromString(DeviceToken.serializer(), res.text()).token)
        } else {
            val error = runCatching {
                lenientJson.decodeFromString(JsonObject.serializer(), res.text())["error"]?.jsonPrimitive?.contentOrNull
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
        lenientJson.decodeFromString<T>(Http.get(url, token()))
    }
}

@Serializable
private data class DeviceCodeRequest(val name: String)

/** `api/services/<id>/record` の答え。断られたとき (404・400) は `message` だけ来る */
@Serializable
private data class RecordResponse(val recorded: String? = null, val reserved: Boolean = true, val message: String? = null)

/** いまの番組を録った結果 (`DenpaApi.recordNow`) */
sealed interface RecordResult {
    /** 予約した (`reserved` が false ならチューナーが足りず競合で録らない) */
    data class Recorded(val title: String, val reserved: Boolean) : RecordResult
    /** 断られた。`message` は denpa の文言 (画面にそのまま出せる) */
    data class Failed(val message: String) : RecordResult
    /** 口の無い古い denpa */
    data object Unsupported : RecordResult
}

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
