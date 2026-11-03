package io.github.danything.denpatv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **局の勢いの「実況」の列の決まり** (ブラウザの denpa のライブの「実況」タブと同じ。`ranking.ts`・`ranking.svelte.ts`)。
 * 部品はライブのメニューの局の列 (`ui/ChannelRows.kt`)、取得は `ui/JikkyoRanking.kt`。
 *
 * - 実況のある局を、種別をまたいで勢い (1 分あたりのコメント数) の大きい順に並べる
 * - 勢いが同じ局は番号順 (種別は 地上波 → BS → CS → SKY。局の列と同じ順)
 * - 放送中のスレッドが無い局 (返事に入っていない) は末尾に番号順で
 * - 勢いを読めないときは番号順のまま
 */
object JikkyoRanking {
    /** 取得する間隔 (ミリ秒)。denpa が NX-Jikkyo の勢いを持つ長さと同じ (細かく呼んでも同じものが返る) */
    const val EVERY_MS = 30_000L

    /** 列から離れてから並べ直すまで (ミリ秒)。離れた直後に戻って押そうとしても、並びが替わっていない */
    const val SETTLE_MS = 1_000L

    /** 列の名前 (ブラウザのタブと同じ) */
    const val LABEL = "実況"

    /** 読めないときに列の名前の横に出す1行 (ブラウザと同じ文。通知は出さない) */
    const val FAILED = "いまは勢いを読めません"

    /**
     * `GET api/jikkyo/ranking` の返事 (`{"at":…,"channels":[{"serviceId":…,"force":…}]}`) を読む。局の ID → 勢い。
     * 形式がずれていれば null (1件でも読めなければ全部捨てる。ブラウザと同じ)
     */
    fun parse(body: String): Map<Long, Int>? {
        val json = runCatching { lenientJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val channels = json["channels"] as? JsonArray ?: return null
        val forces = LinkedHashMap<Long, Int>()
        for (entry in channels) {
            val row = entry as? JsonObject ?: return null
            val id = (row["serviceId"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
            val force = (row["force"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
            forces[id] = force
        }
        return forces
    }

    /**
     * 局の ID を勢いの順に並べる。`ids` は番号順 (局の列の並び) で渡す。
     * `forces` が null (読めない・まだ届いていない) なら番号順のまま
     */
    fun order(ids: List<Long>, forces: Map<Long, Int>?): List<Long> {
        if (forces == null) return ids
        val at = ids.withIndex().associate { (index, id) -> id to index }
        return ids.sortedWith { a, b ->
            val fa = forces[a]
            val fb = forces[b]
            when {
                fa != null && fb != null && fa != fb -> fb - fa
                fa != null && fb == null -> -1
                fa == null && fb != null -> 1
                else -> at.getValue(a) - at.getValue(b)
            }
        }
    }

    /** 実況の列に並べる局 (実況のある局) を番号順に。`services` は局の列に並べるもの */
    fun candidates(services: List<Service>): List<Service> =
        SERVICE_TYPES.flatMap { (type, _) -> services.filter { it.type == type && it.jikkyo != null } }
}

/**
 * **列を使っている間は並べ直しを待つ** (ブラウザの `ListHold` と同じ。テレビの理由はフォーカスだけ)。
 * 外れてから `settleMs` たったら `release` を呼ぶ。待っている間にまた使い始めたら呼ばない
 */
class ListHold(
    private val scope: CoroutineScope,
    private val settleMs: Long = JikkyoRanking.SETTLE_MS,
    private val release: () -> Unit,
) {
    private var using = false
    private var waiting: Job? = null

    /** 使っているか、離れてから待っている途中か */
    val held: Boolean get() = using || waiting?.isActive == true

    fun set(on: Boolean) {
        if (on) {
            using = true
            stop()
        } else if (using) {
            using = false
            stop()
            waiting = scope.launch {
                delay(settleMs)
                waiting = null
                release()
            }
        }
    }

    /** 忘れる (メニューを閉じた)。待つのをやめ、使っている印も消す。残すと、次に開いたときに並べ直さないままになる */
    fun clear() {
        using = false
        stop()
    }

    private fun stop() {
        waiting?.cancel()
        waiting = null
    }
}
