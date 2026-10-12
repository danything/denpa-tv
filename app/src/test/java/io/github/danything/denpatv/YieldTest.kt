package io.github.danything.denpatv

import io.github.danything.denpatv.data.DenpaEvent
import io.github.danything.denpatv.data.SseEvent
import io.github.danything.denpatv.data.YieldPlan
import io.github.danything.denpatv.data.YieldedRecording
import io.github.danything.denpatv.data.denpaEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** ライブが録画にチューナーを譲ったとき (denpa の docs/stream.md「録画に譲る」) */
class YieldTest {
    private val news = YieldedRecording(12, "ニュース", "TOKYO MX", 1_790_001_815_000)
    private val drama = YieldedRecording(13, "ドラマ", "フジテレビ", 1_790_003_615_000)

    @Test
    fun 知らせの_yielded_を読む() {
        // denpa の docs/api.md の例そのまま (知らない鍵は読み捨てる)
        val body = """{"serviceId":3239123608,"recording":{"id":12,"title":"ニュース","serviceName":"TOKYO MX","endAt":1790001815000,"extra":1}}"""
        assertEquals(DenpaEvent.Yielded(3239123608, news), denpaEvent(SseEvent("yielded", body)))
    }

    @Test
    fun 形が違っても止めずに読み捨てる() {
        val warnings = mutableListOf<String>()
        assertNull(denpaEvent(SseEvent("yielded", """{"serviceId":1}"""), warnings::add))
        assertNull(denpaEvent(SseEvent("yielded", "not json"), warnings::add))
        assertEquals(2, warnings.size)
    }

    @Test
    fun 頼み直しが映らなかったら_譲った録画へ() {
        // 蹴られたあとの頼み直し。知らせで来た録画へ
        assertEquals(news, YieldPlan.fallback(trying = true, returning = false, chased = null, kicked = news))
        // 「ライブに戻る」が入れなかった。観ていた録画へ戻る (知らせのものではなく)
        assertEquals(drama, YieldPlan.fallback(trying = true, returning = true, chased = drama, kicked = news))
    }

    @Test
    fun 映ったあとで切れたのは_いつもどおり頼み直す() {
        // 映っていたライブが閉じた (蹴られた直後)。まず同じ局を頼み直す
        assertNull(YieldPlan.fallback(trying = false, returning = false, chased = null, kicked = news))
        // 譲った録画が分からなければ、頼み直し続ける
        assertNull(YieldPlan.fallback(trying = true, returning = false, chased = null, kicked = null))
    }

    @Test
    fun 追っかけは録れているところの少し手前から() {
        // denpa は録れている範囲に収める (いま書いているところの 5 秒手前まで)。秒で頼む
        assertEquals("api/recordings/12/chase?codec=raw&from=604800", YieldPlan.chaseUrl(12, "raw"))
    }

    @Test
    fun 戻れなかったら録り終える時刻を言う() {
        assertEquals("録画が終わるまでライブは見られません (23:43)", YieldPlan.refused(1_790_001_815_000, TimeZone.getTimeZone("Asia/Tokyo")))
    }
}
