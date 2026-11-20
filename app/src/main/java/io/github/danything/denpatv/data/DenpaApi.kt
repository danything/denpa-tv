package io.github.danything.denpatv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
     * 番組表 (`GET api/programs?type=&from=&to=`。denpa v2.33.0 から)。種別 `type` (GR・BS・CS) の `from` から `to` まで (24 時間まで)。
     * 古い denpa (エンドポイントが無い) は 404 で `TooOld`、断られた (403) は `Forbidden`、届かない・ほかの失敗は `Offline`、
     * 形式が違えば `warn` に書いて `Unreadable`。401 は `Unauthorized`
     */
    suspend fun guide(base: URI, type: String, from: Long, to: Long): GuideLookup = withContext(Dispatchers.IO) {
        // サブチャンネルの列もリクエストする (古い denpa は subchannels を読まないので、本チャンネルだけが返る)
        val res = call(base, "api/programs?type=$type&from=$from&to=$to&subchannels=1") ?: return@withContext GuideLookup.Offline
        when {
            res.code == 404 -> GuideLookup.TooOld
            res.code == 403 -> GuideLookup.Forbidden
            !res.ok -> GuideLookup.Offline
            else -> parseGuide(res.text(), warn)?.let { page ->
                // 答えに範囲が無ければ、リクエストした範囲 (手元の窓を合わせるのに使う)
                GuideLookup.Found(page.copy(type = page.type.ifEmpty { type }, from = page.from.takeIf { it > 0 } ?: from, to = page.to.takeIf { it > 0 } ?: to))
            } ?: GuideLookup.Unreadable
        }
    }

    /**
     * **番組表の番組を予約する** (`POST api/programs/<id>/record`)。予約の状態 (`state`) と、録画できない理由 (`reason`。競合など) を返す。
     * 断られた (404・400) は denpa の文言 (`message`)
     */
    suspend fun reserveProgram(base: URI, id: Long): ReserveResult = withContext(Dispatchers.IO) {
        val res = call(base, "api/programs/$id/record", "POST") ?: return@withContext ReserveResult.Failed("denpa に接続できません")
        val body = runCatching { lenientJson.decodeFromString(ReserveResponse.serializer(), res.text()) }.getOrNull()
        when {
            res.ok && body?.state != null -> ReserveResult.Reserved(body.state, body.reserved, body.reason)
            res.ok -> ReserveResult.Failed("denpa の応答を読めません (${res.code})")
            else -> ReserveResult.Failed(body?.message ?: "${res.code}")
        }
    }

    /**
     * **番組の予約を取り消す** (`DELETE api/programs/<id>/record`)。録画中なら録画を止める (`stopped`。録れたところまでは残る)。
     * 予約が無い (404) は `Missing`
     */
    suspend fun cancelProgram(base: URI, id: Long): CancelResult = withContext(Dispatchers.IO) {
        val res = call(base, "api/programs/$id/record", "DELETE") ?: return@withContext CancelResult.Failed("denpa に接続できません")
        val body = runCatching { lenientJson.decodeFromString(CancelResponse.serializer(), res.text()) }.getOrNull()
        when {
            res.ok -> CancelResult.Canceled(body?.stopped == true)
            res.code == 404 -> CancelResult.Missing
            else -> CancelResult.Failed(body?.message ?: "${res.code}")
        }
    }

    /**
     * 録画を何で観られるか (`GET api/recordings/<id>/playback?playable=`)。`playable` を付けて、denpa が選んだ観方 (`auto`) も受け取る。
     * 観られない (404。録画中・ファイルが無い) は `Missing`、届かない・読めないは `Failed`
     */
    suspend fun playback(base: URI, id: Long, playable: Playable): PlaybackLookup = withContext(Dispatchers.IO) {
        val res = call(base, PlaybackPaths.info(id, playable)) ?: return@withContext PlaybackLookup.Failed("denpa に接続できません")
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
     * 録画の状態 (`GET api/recordings/<id>`。`state` と録れた長さ。denpa の docs/api.md「録画の状態」)。追っかけが使う:
     * ストリームが終わったとき、録り終えて最後まで観たのかを確かめる (`chaseEnd`) のと、録り終えたか (`RecordingState.finished`) を聞いて
     * ファイルの再生へ移れるかを見る (移るかは `playback` で決める。`Chase.handOff`)。
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

    /**
     * 実況の NG ワード (`GET api/jikkyo/ng`。世帯で1つの一覧)。世帯の設定がオフ・届かないは空 (NG ワード無しで流す)。
     * 形式が違えば `warn` に書いて空
     */
    suspend fun jikkyoNg(base: URI): List<String> = withContext(Dispatchers.IO) {
        val res = call(base, "api/jikkyo/ng")?.takeIf { it.ok } ?: return@withContext emptyList()
        JikkyoNg.parse(res.text()) ?: emptyList<String>().also { warn("実況の NG ワードの形式が違います (denpa の版のずれ?): ${res.text().take(200)}") }
    }

    /**
     * 局の勢い (`GET api/jikkyo/ranking`)。局の ID → 1 分あたりのコメント数。放送中のスレッドがある局だけ。
     * 読めない (`503`・世帯の設定がオフの `403`・届かない) は null。形式が違えば `warn` に書いて null (版のずれ)
     */
    suspend fun jikkyoRanking(base: URI): Map<Long, Int>? = withContext(Dispatchers.IO) {
        val res = call(base, "api/jikkyo/ranking")?.takeIf { it.ok } ?: return@withContext null
        val text = res.text()
        JikkyoRanking.parse(text) ?: null.also { warn("局の勢いの形式が違います (denpa の版のずれ?): ${text.take(200)}") }
    }

    /**
     * 観るときの決まり (`GET api/playback/options`)。画質のメニューから外すもの。
     * 届かない・2xx 以外・形式が違う (`warn` に書く) ときは null (呼ぶ側は控えずに後で聞き直す)
     * **ライブと追っかけはこの返事を待ってから流しはじめるので、`OPTIONS_TIMEOUT_MS` で打ち切る** (届かないまま HTTP のいつもの時間切れまで
     * 待たせない)。打ち切ったら届かないのと同じ null で、呼ぶ側は `FALLBACK` (何も外さない) で流しはじめる
     */
    suspend fun playbackOptions(base: URI): PlaybackOptions? = withContext(Dispatchers.IO) {
        val res = call(base, "api/playback/options", timeoutMs = OPTIONS_TIMEOUT_MS) ?: return@withContext null
        if (!res.ok) return@withContext null
        parsePlaybackOptions(res.text(), warn)
            ?: null.also { warn("観るときの決まりの形式が違います (denpa の版のずれ?): ${res.text().take(200)}") }
    }

    /** 録画を消す (`DELETE /api/recordings/<id>`。消えれば 204)。もう無い (404) のも消せたとみなして true。録画中 (409) などは false */
    suspend fun deleteRecording(base: URI, id: Long): Boolean = withContext(Dispatchers.IO) {
        val res = call(base, "api/recordings/$id", "DELETE") ?: return@withContext false
        res.ok || res.code == 404
    }

    /** アクセストークンを付けてリクエストする。URL を組めない・届かなければ null。401 は `Unauthorized` */
    private fun call(base: URI, path: String, method: String = "GET", timeoutMs: Int? = null): Http.Response? {
        val url = BaseUrl.resolve(base, path) ?: return null
        val res = try {
            Http.request(url, method, token = token(), timeoutMs = timeoutMs)
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

/** 観るときの決まり (`playbackOptions`) を待つ長さ (ミリ秒)。LAN の denpa なら数十ミリ秒で返る。届かないときに流しはじめるのを長く待たせない */
private const val OPTIONS_TIMEOUT_MS = 2_500

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

/** 番組表を引いた結果 (`DenpaApi.guide`) */
sealed interface GuideLookup {
    data class Found(val page: GuidePage) : GuideLookup
    /** エンドポイントが無い (404。denpa v2.33.0 より前) */
    data object TooOld : GuideLookup
    /** 断られた (403) */
    data object Forbidden : GuideLookup
    /** 届かない・2xx でない */
    data object Offline : GuideLookup
    /** 形式が違う (denpa のバージョンのずれ) */
    data object Unreadable : GuideLookup
}

/** `api/programs/<id>/record` (POST) の答え。断られたとき (404・400) は `message` だけ来る */
@Serializable
private data class ReserveResponse(val state: String? = null, val reserved: Boolean = true, val reason: String? = null, val message: String? = null)

/** `api/programs/<id>/record` (DELETE) の答え */
@Serializable
private data class CancelResponse(val stopped: Boolean = false, val message: String? = null)

/** 番組表の番組を予約した結果 (`DenpaApi.reserveProgram`) */
sealed interface ReserveResult {
    /** 予約した。`reserved` が false (たいていは `state` が競合) なら予約はあるが録画しない。`reason` はその理由 */
    data class Reserved(val state: String, val reserved: Boolean, val reason: String?) : ReserveResult
    /** 断られた。`message` は denpa の文言 */
    data class Failed(val message: String) : ReserveResult
}

/** 予約を取り消した結果 (`DenpaApi.cancelProgram`) */
sealed interface CancelResult {
    /** 取り消した。`stopped` なら録画中の録画を止めた */
    data class Canceled(val stopped: Boolean) : CancelResult
    /** 予約が無かった (404) */
    data object Missing : CancelResult
    data class Failed(val message: String) : CancelResult
}

@Serializable
private data class DeviceTokenRequest(val deviceCode: String)

@Serializable
private data class DeviceToken(val token: String)

/**
 * `api/device/code` の答え。URL は denpa のルートからの相対。
 * `userCode` (`ABCD-EFGH`) はテレビに出し、スマホの denpa の画面に出るものと見比べてもらう (RFC 8628 §5.4)
 */
@Serializable
data class DeviceCode(
    val deviceCode: String,
    val verificationUriComplete: String,
    val expiresIn: Int,
    val interval: Int = 5,
    val userCode: String = "",
)

sealed interface TokenResult {
    data class Granted(val token: String) : TokenResult
    /** `authorization_pending` / `slow_down` / `expired_token` / `invalid_grant` など */
    data class Error(val error: String) : TokenResult
}

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
    return RecordingState(state, duration, parseRecordingJikkyo(json, warn))
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

/**
 * 観るときの決まり (`api/playback/options`)。どちらも画質のメニューに並べるものを決めるのに使う (外すのはメニューだけ。自動では denpa が選ぶ)。
 * 家の中か (`indoor`) は読まない (自動の順は denpa が決める)
 */
data class PlaybackOptions(
    /** `hidden` に `h264` がある。`liveCodecs` が無い古い denpa のときだけ見る */
    val hideH264: Boolean,
    /**
     * denpa がライブでその場でエンコードできるコーデック (`liveCodecs`。AV1 → H.265 → H.264 の順。知らない値は読み飛ばす)。
     * メニューにはこの先頭から再生できるものを 1 つだけ並べる (`LiveQuality.menu`)。無い (v2.45.0 より前の denpa・形式が違う) ときは null
     */
    val liveCodecs: List<LiveQuality>? = null,
) {
    companion object {
        /** 聞けない: メニューから何も外さない */
        val FALLBACK = PlaybackOptions(hideH264 = false)
    }
}

/**
 * `api/playback/options` の返事 (`{"indoor":true,"liveCodecs":["hevc","h264"],"hidden":["av1","h264"],…}`) を読む。
 * `liveCodecs` も `hidden` も並びとして読めなければ null。`liveCodecs` だけ形式が違えば `warn` に書き、`hidden` で決める (`liveCodecs` が無い古い denpa と同じ)
 */
fun parsePlaybackOptions(body: String, warn: (String) -> Unit = {}): PlaybackOptions? {
    val json = runCatching { lenientJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val hidden = runCatching { json["hidden"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
    val liveCodecs = json["liveCodecs"]?.let { value ->
        runCatching { value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
            ?.mapNotNull { codec -> LiveQuality.ofHeader(codec)?.takeIf { it != LiveQuality.Raw } }
            ?: null.also { warn("観るときの決まりの liveCodecs の形式が違います (denpa のバージョンのずれ?)。hidden で決めます: ${value.toString().take(200)}") }
    }
    if (hidden == null && liveCodecs == null) return null
    return PlaybackOptions(hideH264 = hidden != null && "h264" in hidden, liveCodecs = liveCodecs)
}
