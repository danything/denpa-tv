package io.github.danything.denpatv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicReferenceArray

/** denpa の URL を入れてもらったあとの行き先 */
sealed interface ConnectStep {
    /** 家の LAN (TRUSTED_NETWORKS) から入れた。トークンは要らない */
    data class Open(val base: URI) : ConnectStep
    /** 家の外の denpa。登録 (ログイン) が要る。`verificationUrl` は登録の画面の絶対の URL */
    data class NeedsLogin(val base: URI, val code: DeviceCode, val verificationUrl: String) : ConnectStep
    data class Failed(val message: String) : ConnectStep
}

/**
 * 入れてもらった URL で繋げるか確かめる。
 *
 * 1. `api/health` が通るか (denpa か、URL は正しいか)。ポートを書いていなければ 80 のあと 3000 も
 * 2. トークン無しで局の一覧が取れるか — 取れれば家の LAN。そのまま使う
 * 3. 断られたら (401 / 403) テレビを denpa に登録しはじめる (`api/device/code`)。
 *    スマホを denpa の登録の画面へ送り、ログインが済めば denpa が登録を通す
 */
suspend fun connect(
    api: DenpaApi,
    input: String,
    deviceName: String,
    candidates: (URI) -> List<URI> = { BaseUrl.candidates(it) },
): ConnectStep {
    val typed = BaseUrl.normalize(input) ?: return ConnectStep.Failed("URL を読めません (例: http://192.168.1.10:3000)")
    // ポートを書いていなければ 3000 も試し、繋がったほうを覚える (`BaseUrl.candidates`)
    val base = firstReachable(api, candidates(typed))
        ?: return ConnectStep.Failed("$typed に接続できません。URL を確認してください")
    return try {
        if (api.openWithoutToken(base)) {
            ConnectStep.Open(base)
        } else {
            val code = api.deviceCode(base, deviceName)
            val url = BaseUrl.resolve(base, code.verificationUriComplete)?.toString()
                ?: return ConnectStep.Failed("denpa の登録の URL を読めません")
            ConnectStep.NeedsLogin(base, code, url)
        }
    } catch (e: IOException) {
        ConnectStep.Failed(e.message ?: "denpa に繋がりません")
    }
}

/**
 * 候補を同時に確かめ、**前の候補を優先して** denpa らしいもの (`DenpaApi.looksLikeDenpa`) を返す。後ろの候補が先に通ったら、
 * 前の候補は `preferMs` だけ待つ — 80 を黙って捨てる機械でも、繋ぐのを諦めるまで (10 秒) 待たずに 3000 に決められる
 */
internal suspend fun firstReachable(api: DenpaApi, candidates: List<URI>, preferMs: Long = PREFER_MS): URI? {
    if (candidates.size <= 1) return candidates.firstOrNull()?.takeIf { api.looksLikeDenpa(it) }
    val results = AtomicReferenceArray<Boolean>(candidates.size)
    // 読むのはブロックするので止められない。待ち合わせない別のスコープで走らせ、要らなくなった答えは捨てる
    val probes = candidates.mapIndexed { i, url ->
        CoroutineScope(Dispatchers.IO).async { api.looksLikeDenpa(url).also { results.set(i, it) } }
    }
    for ((i, probe) in probes.withIndex()) {
        while (results.get(i) == null) {
            if ((i + 1 until candidates.size).any { results.get(it) == true }) {
                withTimeoutOrNull(preferMs) { probe.await() }
                break
            }
            delay(PROBE_POLL_MS)
        }
        if (results.get(i) == true) return candidates[i]
    }
    return null
}

/** 後ろの候補が通ったあと、前の候補を待つ間 (ミリ秒) */
private const val PREFER_MS = 1_000L

/** 候補の答えを見に行く間 (ミリ秒) */
private const val PROBE_POLL_MS = 50L
