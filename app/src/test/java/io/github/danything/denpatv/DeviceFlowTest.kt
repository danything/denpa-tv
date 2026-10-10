package io.github.danything.denpatv

import io.github.danything.denpatv.data.DeviceCode
import io.github.danything.denpatv.data.PairingOutcome
import io.github.danything.denpatv.data.TokenResult
import io.github.danything.denpatv.data.pollForToken
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** 登録を待つ手順 (RFC 8628 §3.5)。時計は止めて、待った時間だけ進める */
class DeviceFlowTest {
    private val code = DeviceCode("dc", "ABCD-EFGH", "device", "device?code=ABCD-EFGH", expiresIn = 600, interval = 5)

    private suspend fun run(vararg answers: Any): Pair<PairingOutcome, List<Long>> {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val queue = ArrayDeque(answers.toList())
        val outcome = pollForToken(
            code,
            ask = {
                when (val next = queue.removeFirstOrNull() ?: "authorization_pending") {
                    is IOException -> throw next
                    is String -> TokenResult.Error(next)
                    else -> next as TokenResult
                }
            },
            now = { clock },
            sleep = { waits += it; clock += it },
        )
        return outcome to waits
    }

    @Test
    fun まだなら待って聞き直し_通れば受け取る() = runTest {
        val (outcome, waits) = run("authorization_pending", "authorization_pending", TokenResult.Granted("denpa_x"))
        assertEquals(PairingOutcome.Paired("denpa_x"), outcome)
        assertEquals(listOf(5_000L, 5_000L, 5_000L), waits)
    }

    @Test
    fun slow_down_なら間隔を_5_秒延ばす() = runTest {
        val (_, waits) = run("slow_down", "authorization_pending", TokenResult.Granted("t"))
        assertEquals(listOf(5_000L, 10_000L, 10_000L), waits)
    }

    @Test
    fun 期限切れ_そのほかの誤り() = runTest {
        assertEquals(PairingOutcome.Expired, run("expired_token").first)
        assertEquals(PairingOutcome.Failed("invalid_grant"), run("invalid_grant").first)
    }

    @Test
    fun 繋がらなくても待ち続け_期限を過ぎたら_Expired() = runTest {
        val (outcome, waits) = run(IOException("offline"))
        assertEquals(PairingOutcome.Expired, outcome)
        assertEquals(600_000L, waits.sum())
    }
}
