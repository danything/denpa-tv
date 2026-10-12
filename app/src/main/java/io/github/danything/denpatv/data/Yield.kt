package io.github.danything.denpatv.data

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * **ライブがチューナーを譲った録画** (denpa の知らせ `yielded` の `recording`。denpa の docs/api.md)。
 *
 * denpa のライブは録画にだけ譲る。蹴られると denpa はライブの流れを閉じ、どの録画に譲ったかを知らせで流す
 * (流れの中では伝えられないので)。アプリは同じ局をリクエストし直し、**入れなかったときだけ**この録画の追っかけに切り替える
 * (`YieldPlan`。ブラウザの denpa のライブと同じ動き。denpa の docs/stream.md「録画に譲る」)
 */
@Serializable
data class YieldedRecording(
    val id: Long,
    /** 番組名 (記号を落としたもの。録画の一覧の `title` と同じ) */
    val title: String,
    val serviceName: String = "",
    /** 録り終える時刻 (UNIX ミリ秒。終わりのマージン込み)。それまでその本は空かない */
    val endAt: Long,
)

/**
 * 録画に譲ったときの決め事。**画面の状態は持たない** (ライブの画面が持つ)。決め方だけをここに置いて試す
 */
object YieldPlan {
    /**
     * 追っかけをリクエストする位置 (ミリ秒)。**録れているところの少し手前から観る** — denpa は録れている範囲に収める
     * (いま書いているところの 5 秒手前より先へは行かない)。どの録画よりも長い値 (1週間) にしてある
     */
    const val EDGE_MS = 7L * 24 * 60 * 60 * 1000

    /** 譲った録画の追っかけのエンドポイント (`GET api/recordings/<id>/chase`)。録れているところの少し手前から */
    fun chaseUrl(recordingId: Long, codec: String): String = Chase.url("api/recordings/$recordingId/chase", codec, EDGE_MS)

    /**
     * リクエストし直したライブが**映らずに終わった** (エラー・空・流れが閉じた) とき、どの録画の追っかけに切り替えるか。切り替えずに
     * いつもどおりリクエストし直すなら null。
     *
     * - `trying` … いまのリクエストが、蹴られたあと・「ライブに戻る」で出し直したリクエストで、まだ映っていない
     * - `returning` … 「ライブに戻る」を試している (戻れなければ、観ていた録画へ)
     * - `chased` … 観ていた録画 (「ライブに戻る」の前)
     * - `kicked` … 知らせで来た、いま映している局が譲った録画
     */
    fun fallback(trying: Boolean, returning: Boolean, chased: YieldedRecording?, kicked: YieldedRecording?): YieldedRecording? = when {
        !trying -> null
        returning -> chased
        else -> kicked
    }

    /** 切り替えたときの知らせ。番組名は放送の字で出すので、呼ぶ側が挟む (前・後) */
    const val SWITCHED_HEAD = "録画のため局を切り替えました: "
    const val SWITCHED_TAIL = " (録画中の番組を再生しています)"

    /** 「ライブに戻る」を試して入れなかったときの知らせ */
    fun refused(endAt: Long, zone: TimeZone = TimeZone.getDefault()): String =
        "録画が終わるまでライブは見られません (${SimpleDateFormat("HH:mm", Locale.JAPAN).apply { timeZone = zone }.format(Date(endAt))})"

    /** メニューのボタン。押すとリクエストし直す */
    const val BACK_TO_LIVE = "ライブに戻る"
}
