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
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URI

/**
 * denpa の外向けの API (denpa の docs/api.md) を呼ぶ。
 *
 * **家の LAN からはアクセストークン無しで通る** (denpa の TRUSTED_NETWORKS)。家の外の denpa (OIDC でログインする構成) では、
 * テレビを denpa に登録して受け取ったアクセストークンを `Authorization: Bearer` で付ける (`token`、docs/pairing.md)
 */
class DenpaApi(
    /** 答えの形式が思っていたのと違うとき (denpa の版のずれ)。止めずに言うだけ */
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    /** 後ろに置く (`DenpaApi { token }` と書けるように) */
    private val token: () -> String? = { null },
) {
    /** `api/health` (アクセストークンは要らない)。denpa でない・繋がらなければ null */
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
     * 観終えた印 (`watchedAt`) を付ける。
     * `source` は観ているファイル (`encoded` / `alt` / `ts`。追っかけは `ts`)。位置と尺はそのファイルの時刻で、denpa が元の TS の時刻に直して覚える
     * (省くとエンコード済みの時刻とみなされ、元の TS で観た位置がずれる)
     */
    suspend fun saveResume(base: URI, id: Long, atSeconds: Double, lengthSeconds: Double, source: String) =
        withContext(Dispatchers.IO) {
            val url = BaseUrl.resolve(base, "api/recordings/$id/resume") ?: return@withContext
            try {
                Http.request(url, "POST", """{"at":$atSeconds,"length":$lengthSeconds,"source":"$source"}""", token())
            } catch (_: IOException) {
                // 覚えられなくても観るのは止めない
            }
        }

    /**
     * **いま流れている番組を録る** (`POST api/services/<id>/record`)。ブラウザの denpa のライブの録画ボタンと同じで、
     * 予約は番組ごとに1本なので、何度押しても二重には録らない
     */
    suspend fun recordNow(base: URI, serviceId: Long): RecordResult = withContext(Dispatchers.IO) {
        val res = call(base, "api/services/$serviceId/record", "POST") ?: return@withContext RecordResult.Failed("denpa に接続できません")
        val body = runCatching { lenientJson.decodeFromString(RecordResponse.serializer(), res.text()) }.getOrNull()
        when {
            res.ok && body?.recorded != null -> RecordResult.Recorded(body.recorded, body.reserved)
            // 通ったのに答えが読めない (版のずれ?)。予約できたかは分からない
            res.ok -> RecordResult.Failed("denpa の応答を読めません (${res.code})")
            else -> RecordResult.Failed(body?.message ?: "${res.code}")
        }
    }

    /** 番組の中身。無い・読めないときは null */
    suspend fun recordingDetail(base: URI, id: Long): RecordingDetail? = withContext(Dispatchers.IO) {
        val res = call(base, "api/recordings/$id/detail")?.takeIf { it.ok } ?: return@withContext null
        runCatching { lenientJson.decodeFromString(RecordingDetail.serializer(), res.text()) }.getOrNull()
    }

    /**
     * 番組表の番組の中身 (`GET api/programs/<id>`。ライブの詳細)。`id` は局の `now.id`。
     * 番組表から消えた (404)・届かない・読めないは `Missing` (呼ぶ側は `now` のぶんだけを出す)。
     * 形式がずれていても読めるところは読む (`parseProgramInfo`)
     */
    suspend fun program(base: URI, id: Long): ProgramLookup = withContext(Dispatchers.IO) {
        val res = call(base, "api/programs/$id")?.takeIf { it.ok } ?: return@withContext ProgramLookup.Missing
        parseProgramInfo(res.text(), warn)?.let { ProgramLookup.Found(it) } ?: ProgramLookup.Missing
    }

    /**
     * 録り終えたか (`GET api/recordings/<id>` の `state`。denpa の docs/api.md「録画の状態」)。追っかけの画面が、ファイルの再生へ移れるかを聞く入口。
     * 録画中・録画が無い (404)・届かない・読めないは false (移らずに追っかけを続ける)。エンコード済みのファイルで観られるかは、呼ぶ側が `playback` で見る (`Chase.handOff`)
     */
    suspend fun recordingFinished(base: URI, id: Long): Boolean = withContext(Dispatchers.IO) {
        val res = call(base, "api/recordings/$id")?.takeIf { it.ok } ?: return@withContext false
        parseFinished(res.text(), warn)
    }

    /**
     * 録画を何で観られるか (`GET api/recordings/<id>/playback`)。観られない (404。録画中・ファイルが無い・このエンドポイントの無い古い denpa) は `Missing`、
     * 届かない・読めないは `Failed`
     */
    suspend fun playback(base: URI, id: Long): PlaybackLookup = withContext(Dispatchers.IO) {
        val res = call(base, PlaybackPaths.info(id)) ?: return@withContext PlaybackLookup.Failed("denpa に接続できません")
        if (res.code == 404) return@withContext PlaybackLookup.Missing
        if (!res.ok) return@withContext PlaybackLookup.Failed("denpa が答えません (${res.code})")
        runCatching { lenientJson.decodeFromString(PlaybackInfo.serializer(), res.text()) }.getOrNull()?.let { PlaybackLookup.Found(it) }
            ?: PlaybackLookup.Failed("denpa の応答を読めません").also { warn("playback の答えを読めません (denpa の版のずれ?)") }
    }

    /**
     * 録画のチャプター (`GET api/recordings/<id>/chapters`。時刻は `playback` の物差し)。無い・届かない・読めないは空
     * (CM 飛ばしが効かないだけで、観るのは止めない)
     */
    suspend fun chapters(base: URI, id: Long): List<ChapterMark> = withContext(Dispatchers.IO) {
        val res = call(base, PlaybackPaths.chapters(id))?.takeIf { it.ok } ?: return@withContext emptyList()
        parsePlaybackChapters(res.text())
    }

    /**
     * 録画の状態 (`GET api/recordings/<id>`。`state` と録れた長さ)。追っかけのストリームが終わったとき、録り終えて最後まで観たのかを確かめる。
     * 無い・消した (404) は null。届かない・読めなければ投げる (呼ぶ側は「分からない」と読む)
     */
    suspend fun recordingState(base: URI, id: Long): RecordingState? = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/recordings/$id") ?: throw IOException("URL を組み立てられません")
        val res = Http.request(url, token = token())
        if (res.code == 401) throw Unauthorized(url)
        if (res.code == 404) return@withContext null
        if (!res.ok) throw IOException("${res.code} $url")
        parseRecordingState(res.text(), warn) ?: throw IOException("録画の状態を読めません: $url")
    }

    /** 録画を消す (`DELETE /api/recordings/<id>`。消えれば 204)。もう無い (404) のも消せたとみなして true。録画中 (409) などは false */
    suspend fun deleteRecording(base: URI, id: Long): Boolean = withContext(Dispatchers.IO) {
        val res = call(base, "api/recordings/$id", "DELETE") ?: return@withContext false
        res.ok || res.code == 404
    }

    /** アクセストークンを付けてリクエストする。URL を組めない・届かなければ null。401 は `Unauthorized` */
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
     * アクセストークン無しで中に入れるか (局の一覧を引いてみる)。入れれば true (家の LAN = TRUSTED_NETWORKS)、
     * 断られれば false。denpa は家の外からのアクセストークン無しを 401 で断る。認証を置いていない denpa や
     * 一部のエンドポイントは 403 を返すことがあるので、それも「入れない」と読む
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

    /** このテレビのアクセストークンを denpa から外す (`POST api/device/logout`、204) */
    suspend fun logout(base: URI) = withContext(Dispatchers.IO) {
        val url = BaseUrl.resolve(base, "api/device/logout") ?: return@withContext
        try {
            Http.request(url, "POST", token = token())
        } catch (_: IOException) {
            // 届かなくても、こちらはアクセストークンを捨てる
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

/** `api/device/code` の答え。URL は denpa のルートからの相対 */
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

/**
 * `api/recordings/<id>` の答え (`{"id":12,"encoded":false,"state":"recording"}`) から、録り終えたかを読む。
 * `state` が `recorded` (録り終えた) か `available` (ファイルが揃った) なら終わった。`recording`・`failed` は終わっていない扱い。
 *
 * **`encoded` (エンコード済みのファイルがあるか) は見ない。** エンコード済みがあっても、この端末でデコードできるとは限らない。
 * ファイルの再生へ移るかは、録画の再生と同じ選び方 (`playback` と `pickPlayback`) がエンコード済みのファイルを開くかで決める (`Chase.handOff`)。
 * `state` が読めなければ版のずれとして `warn` に書き、終わっていないとみなす
 */
fun parseFinished(body: String, warn: (String) -> Unit = {}): Boolean = parseRecordingState(body, warn)?.finished == true

/**
 * `api/recordings/<id>` の答えを読む。`state` が読めなければ版のずれとして `warn` に書き、null。
 * `durationMs` は一覧と同じ録れた長さ (denpa v1.55.0 から。無ければ null)
 */
fun parseRecordingState(body: String, warn: (String) -> Unit = {}): RecordingState? {
    val json = runCatching { lenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
    val state = json?.get("state")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    if (state == null) {
        warn("録画の状態の形式が違います (denpa の版が違う?): ${body.take(200)}")
        return null
    }
    val duration = json?.get("durationMs")?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
    return RecordingState(state, duration)
}

/** `api/health` の答え。`version` はリリースのタグ (`v1.44.0`。手元・develop は `dev`)、版を返さないとても古い denpa では null */
data class DenpaHealth(val version: String?)

/**
 * `api/health` の返事を読む。denpa らしくなければ null。denpa は初めから `{"ok":true,…}` を返す。**JSON のオブジェクトで `ok` が true のものだけ**
 * (同じ機械の 80・3000 で動いているほかのもの — NAS やルータの画面の HTML、Grafana の `{"database":"ok"}`、素の `OK` — を選ばない)
 */
fun parseDenpaHealth(body: String): DenpaHealth? {
    val json = runCatching { lenientJson.parseToJsonElement(body.removePrefix("\uFEFF")).jsonObject }.getOrNull() ?: return null
    if (runCatching { json["ok"]?.jsonPrimitive?.booleanOrNull }.getOrNull() != true) return null
    return DenpaHealth(runCatching { json["version"]?.jsonPrimitive?.contentOrNull }.getOrNull())
}
