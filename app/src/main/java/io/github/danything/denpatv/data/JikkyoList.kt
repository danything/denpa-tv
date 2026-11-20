package io.github.danything.denpatv.data

import java.util.Calendar
import java.util.TimeZone

/**
 * **右の列の「コメントの一覧」の決まり** (ブラウザの denpa の `list.ts` と同じ値。部品は `ui/JikkyoList.kt`)。
 *
 * - 並べるのは、いまの放送時刻 W までに書かれたもののうち新しいほうから `MAX` 件 (メモリの少ない端末は `MAX_LOW_RAM`)。`SPAN` 前まで。
 *   流れるほうと同じ W で控えを読み直すので、シーク・巻き戻し・CM を切った録画にもそのまま合う。流れるほうで行が足りずに捨てたものも並ぶ
 * - 新しいものが下。**テレビではフォーカスを入れず、いつも「いま」を出す** (巻いて読み返すのはリモコンに向かない)。録画の「この先」も並べない
 * - 勢いは W までの 1 分に書かれたものの数 (NG ワードのものも数える)
 */
object JikkyoList {
    /** 並べるのは W からこれだけ前まで */
    const val SPAN_MS = 30 * 60_000L
    /** 並べる件数 */
    const val MAX = 200
    /** 並べる件数 (メモリの少ない端末。行を描くのが重い) */
    const val MAX_LOW_RAM = 50
    /** 読み直す間隔 (ミリ秒)。流れるほう (0.1 秒) ほど細かくなくてよい */
    const val EVERY_MS = 250L
    /** 勢いを数える幅 (1 分あたりのコメント数) */
    const val FORCE_SPAN_MS = 60_000L

    /** 同じ行の並びか (読み直すたびに描き直さないように)。控えは足すだけなので、件数と両端で分かる */
    fun sameRows(a: List<JikkyoComment>, b: List<JikkyoComment>): Boolean =
        a.size == b.size && a.firstOrNull() == b.firstOrNull() && a.lastOrNull() == b.lastOrNull()

    /** 書かれた時刻 (h:mm:ss。端末の時計の時間帯で) */
    fun airClock(at: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = at }
        return "%d:%02d:%02d".format(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND))
    }
}
