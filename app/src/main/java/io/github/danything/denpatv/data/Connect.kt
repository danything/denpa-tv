package io.github.danything.denpatv.data

import java.io.IOException
import java.net.URI

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
 * 1. `api/health` が通るか (denpa か、URL は正しいか)
 * 2. トークン無しで局の一覧が取れるか — 取れれば家の LAN。そのまま使う
 * 3. 断られたら (401 / 403) テレビを denpa に登録しはじめる (`api/device/code`)。
 *    スマホを denpa の登録の画面へ送り、ログインが済めば denpa が登録を通す
 */
suspend fun connect(api: DenpaApi, input: String, deviceName: String): ConnectStep {
    val base = BaseUrl.normalize(input) ?: return ConnectStep.Failed("URL を読めません (例: http://192.168.1.10:3000)")
    if (!api.health(base)) {
        return ConnectStep.Failed("$base に繋がりません。URL を確かめてください")
    }
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
