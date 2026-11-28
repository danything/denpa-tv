package io.github.danything.denpatv.data

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
 * コメントの出どころと控えはここが持つ。出どころはどれも denpa のサーバの受け口 (`JikkyoServer`) で、テレビは外へ繋がない。
 * 控えは画面のスレッドと読むスレッドの両方から触るので、鍵を掛ける。
 *
 * - ライブ・追っかけ (`receives`) は `api/jikkyo/feed` から受ける (`JikkyoLive`)。最初の返事に直近 15 分が入るので、
 *   追っかけの過去ログに入る前の 10 分もここで埋まる。ライブは公式のニコニコ実況のコメントも同じ呼び出しで受ける (`official`)
 * - 録画・追っかけは `api/jikkyo/kakolog` から取る (`kakolog`)。録画は開いたときに全区間、10 分より新しい所は 5 分おきに取り足す
 * - 読めなくなったら `down` (操作の列の「実況」に「(いまは読めません)」を添える。通知は出さない)。過去ログが読めない、
 *   または hub に繋がらず公式からも受けていないとき (NX-Jikkyo が止まっていても hub に書かれたものは流れる)
 * - 受け口が無い (古い denpa) なら `tooOld` にして、もう呼ばない。401 なら `refused`
 * - 形式がずれたら1回だけ警告して、読めたぶんだけ流す
 */
class JikkyoFeed(
    /** ライブのコメントを受けるか (ライブと追っかけ) */
    private val receives: Boolean,
    /** 受けた出来事と、過去ログの取り足しを回すところ。1 本のスレッドで回すもの */
    private val scope: CoroutineScope,
    private val server: JikkyoServer,
    private val now: () -> Long = System::currentTimeMillis,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    /** HTTP を待つところ (単体テストでは時計を進められるものに替える) */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val cursor = JikkyoCursor()
    private var ng: (String) -> Boolean = { false }
    /** 1件ずつの NG の判定の控え。一覧は 0.25 秒ごとに同じものを読み直すので、そのたびに字をそろえ直さない */
    private var ngSeen = WeakHashMap<JikkyoComment, Boolean>()
    private var live: JikkyoLive? = null
    private var channel: String? = null
    private var active = false
    private var official = false
    /** 読めなくなっている出どころ (`hub`・`kakolog`) */
    private val failing = mutableSetOf<String>()
    /** 公式から受けている (境がある) */
    private var fromOfficial = false
    private var kakologJob: Job? = null
    private val downState = MutableStateFlow(false)
    private val tooOldState = MutableStateFlow(false)
    private val refusedState = MutableStateFlow(false)

    /** 読めないか */
    val down: StateFlow<Boolean> get() = downState

    /** 繋いだ denpa に実況の受け口が無い (古い)。一度なったら、この画面の間は戻さない */
    val tooOld: StateFlow<Boolean> get() = tooOldState

    /** denpa に 401 で断られた (呼ぶ側は繋ぐ画面に戻す) */
    val refused: StateFlow<Boolean> get() = refusedState

    /** いま流している実況のチャンネル (流していなければ null) */
    val current: String? get() = synchronized(lock) { channel.takeIf { active } }

    /**
     * 局を替えた・オン/オフを替えた。替わったら繋ぎ直す。局が替わったら控えも捨てる。
     * @param channel 実況のチャンネル。実況の無い局・世帯の設定がオフなら null
     * @param on 流すか (端末の設定がオンのとき)
     * @param official 公式のニコニコ実況のコメントも受ける (ライブだけ)
     */
    fun set(channel: String?, on: Boolean, official: Boolean = false) {
        val active = on && channel != null
        synchronized(lock) {
            if (channel == this.channel && active == this.active && official == this.official) return
            if (channel != this.channel) cursor.reset() else cursor.pause()
            this.channel = channel
            this.active = active
            this.official = official
        }
        stopSources()
        if (!active || !receives || channel == null || tooOldState.value) return
        scope.launch {
            if (current != channel) return@launch
            live = JikkyoLive(
                channel,
                server,
                object : JikkyoLive.Events {
                    override fun comments(comments: List<JikkyoComment>) = synchronized(lock) { cursor.receive(comments) }
                    override fun hub(ok: Boolean) = mark("hub", ok)
                    override fun boundary(at: Long?) = synchronized(lock) {
                        fromOfficial = at != null
                        refresh()
                    }
                    override fun odd(what: String) = warnShape(what)
                    override fun tooOld() = markTooOld()
                    override fun unauthorized() {
                        refusedState.value = true
                    }
                },
                scope,
                io,
                now,
                official,
            ).also { it.start() }
        }
    }

    /**
     * 過去ログから `from` から `to()` までを取る。録画は開いたときに1回 (`every` を省く)、録画中の追っかけは `every` ごとに新しいところを取り足す。
     * 取ったところは控えが覚えているので、同じ区間を2度訊かない。**10 分より新しいところは、取っても「埋めた」ことにしない** (過去ログにまだ入りきっていない。
     * 追っかけではそこをライブの受け口の直近 15 分が埋める)。録り終えてすぐ開いた録画は、そこが埋まるまで `REFRESH_MS` ごとに取り足す。
     * 読めなければ (hub が取りきれなかったときも) 「いまは読めない」にして、60 秒後にもう一度試す
     */
    fun kakolog(from: Long, to: () -> Long, every: Long? = null) {
        stopKakolog()
        val channel = current ?: return
        if (tooOldState.value) return
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
                        val got = withContext(io) { Kakolog.fetch(server, channel, start, stop, at) }
                        if (got.odd > 0) warnShape("過去ログの返事の形式が一部違います")
                        synchronized(lock) {
                            if (this@JikkyoFeed.channel != channel) return@launch
                            // hub が過去ログ API から取りきれなかったら、取れた分だけ流し、埋めたことにはしない
                            cursor.receive(got.comments, if (got.complete) start to minOf(stop, settled) else null)
                        }
                        if (!got.complete) ok = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: JikkyoTooOld) {
                        return@launch markTooOld()
                    } catch (_: Unauthorized) {
                        refusedState.value = true
                        return@launch
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

    /** 出どころが読めるか */
    private fun mark(source: String, ok: Boolean) = synchronized(lock) {
        if (ok) failing.remove(source) else failing.add(source)
        refresh()
    }

    /** 過去ログが読めない、または hub に繋がらず公式からも受けていなければ `down`。鍵の中で呼ぶ */
    private fun refresh() {
        downState.value = "kakolog" in failing || ("hub" in failing && !fromOfficial)
    }

    /** 受け口が無い。止めて、もう呼ばない */
    private fun markTooOld() {
        if (tooOldState.value) return
        tooOldState.value = true
        warn("denpa に実況の受け口がありません (denpa $MIN_JIKKYO より前?)。実況のコメントは流しません")
        stopSources()
    }

    private fun warnShape(what: String) {
        if (warned) return
        warned = true
        warn("$what。denpa のサーバか denpa-hub のバージョンが違うかもしれません (読めたぶんだけ流します)")
    }

    private fun stopKakolog() {
        kakologJob?.cancel()
        kakologJob = null
    }

    private fun stopSources() {
        // ライブの受け口は `scope` の上でだけ触る (作るのも `scope`。頼んだ順に回る)
        scope.launch {
            live?.stop()
            live = null
        }
        stopKakolog()
        synchronized(lock) {
            failing.clear()
            fromOfficial = false
            refresh()
        }
    }

    companion object {
        private const val TAG = "Jikkyo"
        /** 過去ログが読めなかったとき、もう一度試すまで */
        private const val RETRY_MS = 60_000L
        /** 過去ログにまだ入りきっていないところを取り足す間隔 (過去ログは 5 分おきに集めている) */
        const val REFRESH_MS = 5 * 60_000L

        /** 形式のずれは端末ごとに1回だけ言う (denpa のサーバや denpa-hub のバージョンが変わったときに気付けるように) */
        @Volatile
        private var warned = false
    }
}
