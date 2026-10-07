package io.github.danything.denpatv.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.DenpaEvent
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingList
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.denpaTooOld
import io.github.danything.denpatv.data.followEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.net.URI

/**
 * 画面が使う denpa の窓口。一覧は画面をまたいで使うので (ライブの局送り・録画を開く) ここに控える。
 * トークンがあれば API・絵・映像のどれにも付ける
 */
class Repository(val app: DenpaApp, val base: URI, val token: String?) {
    val api = DenpaApi { token }

    var services: List<Service> = emptyList()
        private set
    private val list = RecordingList { api.recordings(base) }

    val recordings: List<Recording> get() = list.items

    /** 録画を読み直す (全部)。読めたら「続きを視聴」も合わせる (消えた・観終えた録画を消し、ほかの端末で観た位置に直す) */
    suspend fun refreshRecordings() {
        val fetchedAt = System.currentTimeMillis()
        list.refresh()
        val items = list.items
        app.scope.launch { app.watchNext.sync(items, fetchedAt) }
    }

    /**
     * 録画を閉じた・最後まで観た。「続きを視聴」に出す・直す・消す (Android 8.0 から)。
     * **観た位置を denpa に預けたあとに呼ぶ** (先に書くと、その間に読み直した一覧の古い続きで消しうる)
     */
    suspend fun watchNext(recording: Recording, positionMs: Long, durationMs: Long, finished: Boolean) {
        app.watchNext.stopped(recording, positionMs, durationMs, finished, url(recording.poster), token)
    }

    /**
     * 一覧が古くなった (追っかけで観た録画が録り終えた・焼き上がったかもしれない、denpa から `recordings` が来た)。
     * 一覧に戻ったら読み直す
     */
    @Volatile
    var recordingsStale = false

    /**
     * 観た位置を預けた (閉じた・裏に回った・観終えた)。一覧の観た割合の帯と未視聴の点を直すため、読み直してもらう。
     * denpa は観終えたときしか知らせない (途中で止めた位置では来ない) ので、手元から知らせる。
     * 一覧が出ていればすぐ (知らせ)、まだなら戻ったときに (`recordingsStale`)
     */
    fun positionSaved() {
        recordingsStale = true
        incoming.tryEmit(DenpaEvent.Changed("recordings"))
    }

    /** 局が古くなった (denpa から `services` / `programs` が来た)。ライブを開いたら読み直す */
    var servicesStale = false

    /**
     * メニューの画面に戻ったら、左のメニュー (「ライブ」) に合わせる。ライブから戻ったとき — 戻るでいちばん上のメニューへ
     * (録画の一覧のカードに合わせると、ライブに戻るにも左へ行き直すことになる)
     */
    var menuOnReturn = false

    /** 録画の一覧に戻ったとき合わせる先 (再生の画面で消したときの隣) */
    var focusOnReturn: Long? = null

    /** 消した録画を手元の一覧から抜く。隣の id を返す */
    fun forgetRecording(id: Long): Long? {
        app.scope.launch { app.watchNext.remove(id) }
        return list.remove(id)
    }

    /** 繋いだ denpa の版 (`api/health` の `version`)。確かめる前・届かなければ null */
    var denpaVersion by mutableStateOf<String?>(null)
        private set

    /** 繋いだ denpa が古すぎれば、画面 (録画の一覧の頭・設定) に出す1行 (`denpaTooOld`)。古くても止めない */
    var denpaWarning by mutableStateOf<String?>(null)
        private set

    /**
     * 繋いだ denpa の版を確かめる。知らせ (`api/events`) に繋がるたび (denpa を上げて立ち上げ直したときも) に1度。
     * 届かなければ前のまま
     */
    private suspend fun checkVersion() {
        val health = api.health(base) ?: return
        denpaVersion = health.version
        denpaWarning = denpaTooOld(health.version)?.also { Log.w(TAG, it) }
    }

    /** 局だけ取り直す (いま放送中の番組が変わるので) */
    suspend fun refreshServices() {
        services = api.services(base)
        servicesStale = false
    }

    fun url(relative: String?): String? = relative?.let { BaseUrl.resolve(base, it)?.toString() }

    private val incoming = MutableSharedFlow<DenpaEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** denpa の変化の知らせ。開いている画面が受けて読み直す (`listen` が繋いでいる間だけ流れる) */
    val events: SharedFlow<DenpaEvent> get() = incoming

    /** 焼いている録画の進み (録画の id → 0..1)。カードに出す。しばらく届かなければ消す (失敗・取り消し) */
    val encoding = mutableStateMapOf<Long, Float>()
    private val encodingExpiry = mutableMapOf<Long, Job>()

    /**
     * denpa の知らせ (`api/events`) に繋ぎっぱなしにする。**アプリが前に出ている間だけ** 呼ぶ (DenpaTv.kt)。
     * トークンが効かなければ `onUnauthorized` (繋ぐ画面へ) を呼んで戻る。止めるまで戻らない
     */
    suspend fun listen(onUnauthorized: () -> Unit) {
        val url = BaseUrl.resolve(base, "api/events") ?: return
        coroutineScope {
            // 受けた知らせはここ (呼ぶ側のスレッド = メイン) で仕分ける
            val sorter = launch { incoming.collect { sort(it) } }
            try {
                followEvents(url, token, onEvent = { incoming.tryEmit(it) }, warn = { Log.w(TAG, it) })
            } catch (_: Unauthorized) {
                onUnauthorized()
            } finally {
                sorter.cancel()
                encodingExpiry.values.forEach { it.cancel() }
                encodingExpiry.clear()
                encoding.clear()
            }
        }
    }

    private fun CoroutineScope.sort(event: DenpaEvent) {
        when (event) {
            // 切れていた間の知らせは来ないので、どちらも古いかもしれない。denpa が上がったかもしれない
            DenpaEvent.Opened -> {
                recordingsStale = true
                servicesStale = true
                launch { checkVersion() }
            }
            is DenpaEvent.Changed -> when (event.name) {
                "recordings" -> recordingsStale = true
                "services", "programs" -> servicesStale = true
            }
            is DenpaEvent.Encode -> {
                encodingExpiry.remove(event.recordingId)?.cancel()
                if (event.percent >= 1f) {
                    // 焼き上がった。続く `recordings` で一覧が読み直される
                    encoding.remove(event.recordingId)
                } else {
                    encoding[event.recordingId] = event.percent
                    encodingExpiry[event.recordingId] = launch {
                        delay(ENCODING_EXPIRY_MS)
                        encoding.remove(event.recordingId)
                        encodingExpiry.remove(event.recordingId)
                    }
                }
            }
        }
    }
}

/** 焼いている間、denpa は 2 秒おきに進みを送る。これだけ来なければ止まったと見なす */
private const val ENCODING_EXPIRY_MS = 15_000L

private const val TAG = "DenpaEvents"
