package io.github.danything.denpatv.data

import kotlinx.coroutines.delay

/** 登録を待った結果 */
sealed interface PairingOutcome {
    data class Paired(val token: String) : PairingOutcome
    /** 断られた (denpa は今は断る手順を持たないが、RFC 8628 どおり受ける) */
    data object Denied : PairingOutcome
    /** 期限 (10 分) が切れた。最初からやり直す */
    data object Expired : PairingOutcome
    data class Failed(val reason: String) : PairingOutcome
}

/**
 * 登録が済むまで `api/device/token` を聞き続ける (RFC 8628 §3.5)。
 *
 * - `authorization_pending` — まだ。`interval` 秒おきに聞き直す
 * - `slow_down` — 聞きすぎ。間隔を 5 秒延ばす
 * - `access_denied` — 断られた / `expired_token` — 期限切れ / ほか (`invalid_grant` など) — やり直し
 *
 * 通信の失敗 (一時的に繋がらない) は待って聞き直す。期限を過ぎたら Expired
 */
suspend fun pollForToken(
    code: DeviceCode,
    ask: suspend () -> TokenResult,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
): PairingOutcome {
    var interval = code.interval.coerceAtLeast(1) * 1000L
    val deadline = now() + code.expiresIn * 1000L
    while (now() < deadline) {
        sleep(interval)
        val result = try {
            ask()
        } catch (_: java.io.IOException) {
            continue
        }
        when (result) {
            is TokenResult.Granted -> return PairingOutcome.Paired(result.token)
            is TokenResult.Error -> when (result.error) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5_000
                "access_denied" -> return PairingOutcome.Denied
                "expired_token" -> return PairingOutcome.Expired
                else -> return PairingOutcome.Failed(result.error)
            }
        }
    }
    return PairingOutcome.Expired
}
