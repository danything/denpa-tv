package io.github.danything.denpatv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI

/**
 * denpa の外向けの口 (denpa の docs/api.md) を叩く。
 *
 * **家の LAN からはトークン無しで通る** (denpa の TRUSTED_NETWORKS)。家の外の denpa (OIDC でログインする構成) では、
 * テレビを denpa に登録して受け取ったトークンを `Authorization: Bearer` で付ける (`token`、docs/pairing.md)
 */
class DenpaApi(
    /** 答えの形が思っていたのと違うとき (denpa の版のずれ)。止めずに言うだけ */
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    /** 後ろに置く (`DenpaApi { token }` と書けるように) */
    private val token: () -> String? = { null },
) {
    /** `api/health` (鍵は要らない)。denpa でない・繋がらなければ null */
    suspend fun health(base: URI): DenpaHealth? = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/health") ?: return@withContext null
        val response = try {
            Http.request(url)
        } catch (_: Exception) {
            // 繋がらない・URL が変 (IOException 以外も): どれも「繋がらない」
            return@withContext null
        }
        if (response.ok) parseDenpaHealth(response.text()) else null
    }

    suspend fun services(base: URI): List<Service> = get(base, "api/services")

    /** 録画を新しい順に、全部 (画面は一番古い録画から開くので) */
    suspend fun recordings(base: URI): List<Recording> = get(base, "api/recordings")

    /**
     * どこまで観たかを預ける (`POST /api/recordings/<id>/resume`)。秒で渡す。
     * 末尾 (尺の 30 秒手前より後) を渡すと、denpa は続きを消し (次に開いたときエンドロールから始まらないように)、
     * 観終えた印 (`watchedAt`) を付ける
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
        val res = call(base, "api/services/$serviceId/record", "POST") ?: return@withContext RecordResult.Failed("denpa に届きません")
        val body = runCatching { lenientJson.decodeFromString(RecordResponse.serializer(), res.text()) }.getOrNull()
        when {
            res.ok && body?.recorded != null -> RecordResult.Recorded(body.recorded, body.reserved)
            // 通ったのに答えが読めない (版のずれ?)。予約できたかは分からない
            res.ok -> RecordResult.Failed("denpa の答えを読めません (${res.code})")
            else -> RecordResult.Failed(body?.message ?: "${res.code}")
        }
    }

    /** 番組の中身。無い・読めないときは null */
    suspend fun recordingDetail(base: URI, id: Long): RecordingDetail? = withContext(Dispatchers.IO) {
        val res = call(base, "api/recordings/$id/detail")?.takeIf { it.ok } ?: return@withContext null
        runCatching { lenientJson.decodeFromString(RecordingDetail.serializer(), res.text()) }.getOrNull()
    }

    /**
     * 番組表の番組の中身 (`GET api/programs/<id>`。ライブの詳しく)。`id` は局の `now.id`。
     * 番組表から消えた (404)・届かない・読めないは `Missing` (呼ぶ側は `now` のぶんだけを出す)。
     * 形がずれていても読めるところは読む (`parseProgramInfo`)
     */
    suspend fun program(base: URI, id: Long): ProgramLookup = withContext(Dispatchers.IO) {
        val res = call(base, "api/programs/$id")?.takeIf { it.ok } ?: return@withContext ProgramLookup.Missing
        parseProgramInfo(res.text(), warn)?.let { ProgramLookup.Found(it) } ?: ProgramLookup.Missing
    }

    /** 録画を消す (`DELETE /api/recordings/<id>`。消えれば 204)。もう無い (404) のも消せたとみなして true。録画中 (409) などは false */
    suspend fun deleteRecording(base: URI, id: Long): Boolean = withContext(Dispatchers.IO) {
        val res = call(base, "api/recordings/$id", "DELETE") ?: return@withContext false
        res.ok || res.code == 404
    }

    /** 鍵を付けて頼む。URL を組めない・届かなければ null。401 は `Unauthorized` */
    private fun call(base: URI, path: String, method: String = "GET"): Http.Response? {
        val url = BaseUrl.resolve(base, path) ?: return null
        val res = try {
            Http.request(url, method, token = token())
        } catch (_: IOException) {
            return null
        }
        if (res.code == 401) throw Unauthorized(url)
        return res
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

private const val TAG = "DenpaApi"

@Serializable
private data class DeviceCodeRequest(val name: String)

/** `api/services/<id>/record` の答え。断られたとき (404・400) は `message` だけ来る */
@Serializable
private data class RecordResponse(val recorded: String? = null, val reserved: Boolean = true, val message: String? = null)

/** いまの番組を録った結果 (`DenpaApi.recordNow`) */
sealed interface RecordResult {
    /** 予約した。`reserved` が false なら予約はあるが録らない (たいていはチューナーの競合。録り終えた・失敗したものも) */
    data class Recorded(val title: String, val reserved: Boolean) : RecordResult
    /** 断られた。`message` は denpa の文言 (画面にそのまま出せる) */
    data class Failed(val message: String) : RecordResult
}

@Serializable
private data class DeviceTokenRequest(val deviceCode: String)

@Serializable
private data class DeviceToken(val token: String)

/** `api/device/code` の答え。URL は denpa の根からの相対 */
@Serializable
data class DeviceCode(
    val deviceCode: String,
    val verificationUriComplete: String,
    val expiresIn: Int,
    val interval: Int = 5,
)

sealed interface TokenResult {
    data class Granted(val token: String) : TokenResult
    /** `authorization_pending` / `slow_down` / `expired_token` / `invalid_grant` など */
    data class Error(val error: String) : TokenResult
}

/** `api/health` の答え。`version` はリリースのタグ (`v1.44.0`。手元・develop は `dev`)、版を返さないとても古い denpa では null */
data class DenpaHealth(val version: String?)

/**
 * `api/health` の返事を読む。denpa らしくなければ null。denpa は初めから `{"ok":true,…}` を返す。**JSON のオブジェクトで `ok` が true のものだけ**
 * (同じ機械の 80・3000 に居るほかのもの — NAS やルータの画面の HTML、Grafana の `{"database":"ok"}`、素の `OK` — を選ばない)
 */
fun parseDenpaHealth(body: String): DenpaHealth? {
    val json = runCatching { lenientJson.parseToJsonElement(body.removePrefix("\uFEFF")).jsonObject }.getOrNull() ?: return null
    if (runCatching { json["ok"]?.jsonPrimitive?.booleanOrNull }.getOrNull() != true) return null
    return DenpaHealth(runCatching { json["version"]?.jsonPrimitive?.contentOrNull }.getOrNull())
}
