package io.doany.denpatv.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/**
 * **流すコメントを、いまの放送時刻 W に合わせて出す** (ライブ・追っかけ・録画で同じ1つ。ブラウザの denpa の `feed.svelte.ts` と同じ決まり)。
 *
 * 画面は W (いま映しているコマの放送時刻。`AirTime`) を渡して `take` を呼び、返ってきたものを流す (`JikkyoLayer`)。
 * コメントの出どころ (NX-Jikkyo・過去ログ API) と控えはここが持つ。控えは画面のスレッドと読むスレッドの両方から触るので、鍵を掛ける。
 *
 * - ライブ・追っかけ (`live`) は NX-Jikkyo の WebSocket に繋ぐ。追っかけは、W が過去ログにまだ入っていない新しい所 (10 分以内) に来たら1回だけ遡る
 * - 録画・追っかけは過去ログ API から取る (`kakolog`)。録画は開いたときに全区間、10 分より新しい所は 5 分おきに取り足す
 * - 読めなくなったら `down` (操作の列の「実況」に「(いまは読めません)」を添える。通知は出さない)
 * - 形式がずれたら1回だけ警告して、読めたぶんだけ流す
 */
class JikkyoFeed(
    /** NX-Jikkyo の WebSocket に繋ぐか (ライブと追っかけ) */
    private val live: Boolean,
    /** 繋ぐ出来事と、過去ログの取り足しを回すところ。1 本のスレッドで回すもの */
    private val scope: CoroutineScope,
    private val endpoints: JikkyoEndpoints,
    private val opener: JikkyoSocketOpener = WebSocketOpener,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: (channel: String, from: Long, to: Long) -> Kakolog.Result = { channel, from, to ->
        Kakolog.fetch(endpoints, channel, from, to)
    },
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    /** 過去ログを読むところ (単体テストでは時計を進められるものに替える) */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val cursor = JikkyoCursor()
    private var ng: (String) -> Boolean = { false }
    /** 1件ずつの NG の判定の控え。一覧は 0.25 秒ごとに同じものを読み直すので、そのたびに字をそろえ直さない */
    private var ngSeen = WeakHashMap<JikkyoComment, Boolean>()
    private var nx: NxJikkyo? = null
    private var channel: String? = null
    private var active = false
    /** 読めなくなっている出どころ (`nx`・`kakolog`) */
    private val failing = mutableSetOf<String>()
    private var kakologJob: Job? = null
    private val downState = MutableStateFlow(false)

    /** 読めないか */
    val down: StateFlow<Boolean> get() = downState

    /** いま流している実況のチャンネル (流していなければ null) */
    val current: String? get() = synchronized(lock) { channel.takeIf { active } }

    /**
     * 局を替えた・オン/オフを替えた。替わったら繋ぎ直す。局が替わったら控えも捨てる。
     * @param channel 実況のチャンネル。実況の無い局・世帯の設定がオフなら null
     * @param on 流すか (端末の設定がオンのとき)
     */
    fun set(channel: String?, on: Boolean) {
        val active = on && channel != null
        synchronized(lock) {
            if (channel == this.channel && active == this.active) return
            if (channel != this.channel) cursor.reset() else cursor.pause()
            backfillAsked = false
            this.channel = channel
            this.active = active
        }
        stopSources()
        if (!active || !live || channel == null) return
        scope.launch {
            if (current != channel) return@launch
            nx = NxJikkyo(
                channel,
                object : NxJikkyo.Events {
                    override fun comments(comments: List<JikkyoComment>) = synchronized(lock) { cursor.receive(comments) }
                    override fun state(ok: Boolean) = mark("nx", ok)
                    override fun odd(what: String) = warnShape(what)
                },
                scope,
                endpoints,
                opener,
            ).also { it.start() }
        }
    }

    /** NX-Jikkyo で1回だけ遡る (録画中の追っかけ)。W が過去ログにまだ入っていない新しいところ (10 分以内) に来たときだけ */
    fun backfill(w: Long) {
        if (backfillAsked) return
        val at = now()
        if (w <= at - Kakolog.LAG_MS) return
        backfillAsked = true
        scope.launch { nx?.backfill(at) }
    }

    /** 遡りを頼んだか (画面は流すたびに `backfill` を呼ぶので、2度目からはすぐ戻る) */
    @Volatile
    private var backfillAsked = false

    /**
     * 過去ログ API から `from` から `to()` までを取る。録画は開いたときに1回 (`every` を省く)、録画中の追っかけは `every` ごとに新しいところを取り足す。
     * 取ったところは控えが覚えているので、同じ区間を2度訊かない。**10 分より新しいところは、取っても「埋めた」ことにしない** (過去ログにまだ入りきっていない)。
     * そこが埋まるまで `REFRESH_MS` ごとに取り足す。読めなければ「いまは読めない」にして、60 秒後にもう一度試す
     */
    fun kakolog(from: Long, to: () -> Long, every: Long? = null) {
        stopKakolog()
        val channel = current ?: return
        kakologJob = scope.launch {
            while (true) {
                var ok = true
                val at = now()
                val end = minOf(to(), at)
                // ここより新しいところは、まだ過去ログに入りきっていない
                val settled = at - Kakolog.LAG_MS
                val gaps = synchronized(lock) { cursor.store.missing(from, end) }
                for ((start, stop) in gaps) {
                    try {
                        val got = withContext(io) { fetch(channel, start, stop) }
                        if (got.odd > 0) warnShape("過去ログの返事の形式が一部違います")
                        synchronized(lock) {
                            if (this@JikkyoFeed.channel != channel) return@launch
                            cursor.receive(got.comments, start to minOf(stop, settled))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ok = false
                        break
                    }
                }
                mark("kakolog", ok)
                val wait = when {
                    !ok -> RETRY_MS
                    every != null -> every
                    end > settled -> REFRESH_MS
                    else -> return@launch
                }
                delay(wait)
            }
        }
    }

    /** 重ねるのを止めた (オフにした)。戻ったときに、止めていた間のぶんをまとめて流さない */
    fun pause() = synchronized(lock) { cursor.pause() }

    /** NG ワードを替えた。次に出すものから効く */
    fun setNg(words: List<String>) = synchronized(lock) {
        ng = JikkyoNg.compile(words)
        ngSeen = WeakHashMap()
    }

    /** いまの放送時刻 `w` までに書かれたもののうち、まだ流していないもの。流していないときは何も返さない。NG ワードを含むものは外す */
    fun take(w: Long): JikkyoCursor.Take = synchronized(lock) {
        if (!active) return JikkyoCursor.Take(emptyList(), false)
        val take = cursor.take(w)
        JikkyoCursor.Take(take.comments.filterNot(::isNg), take.jumped)
    }

    /** コメントの一覧に並べるもの。`w` までの `span` ms に書かれたもののうち、新しいほうから `limit` 件 (時刻の順)。NG ワードを含むものは外す */
    fun recent(w: Long, span: Long, limit: Int): List<JikkyoComment> = synchronized(lock) {
        if (!active) return emptyList()
        cursor.store.latest(w - span, w, limit) { !isNg(it) }
    }

    /** 勢い。`w` までの `span` ms に書かれたものの数 (NG ワードのものも数える) */
    fun force(w: Long, span: Long): Int = synchronized(lock) {
        if (!active) 0 else cursor.store.count(w - span, w)
    }

    /** 画面を閉じた。繋いでいるものを閉じ、閉じ終えたら `then` (`scope` を止めるなど。`scope` の上で呼ぶ) */
    fun close(then: () -> Unit = {}) {
        synchronized(lock) { active = false }
        stopSources()
        scope.launch { then() }
    }

    private fun isNg(comment: JikkyoComment): Boolean = ngSeen.getOrPut(comment) { ng(comment.text) }

    /** 出どころが読めるか。どれかが読めなければ `down` */
    private fun mark(source: String, ok: Boolean) {
        synchronized(lock) {
            if (ok) failing.remove(source) else failing.add(source)
            downState.value = failing.isNotEmpty()
        }
    }

    private fun warnShape(what: String) {
        if (warned) return
        warned = true
        warn("$what。実況の取得先のバージョンが変わったかもしれません (読めたぶんだけ流します)")
    }

    private fun stopKakolog() {
        kakologJob?.cancel()
        kakologJob = null
    }

    private fun stopSources() {
        // NX-Jikkyo は `scope` の上でだけ触る (作るのも `scope`。頼んだ順に回る)
        scope.launch {
            nx?.close()
            nx = null
        }
        stopKakolog()
        synchronized(lock) {
            failing.clear()
            downState.value = false
        }
    }

    companion object {
        private const val TAG = "Jikkyo"
        /** 過去ログ API が読めなかったとき、もう一度試すまで */
        private const val RETRY_MS = 60_000L
        /** 過去ログにまだ入りきっていないところを取り足す間隔 (過去ログは 5 分おきに集めている) */
        const val REFRESH_MS = 5 * 60_000L

        /** 形式のずれは端末ごとに1回だけ言う (NX-Jikkyo や過去ログ API のバージョンが変わったときに気付けるように) */
        @Volatile
        private var warned = false
    }
}
