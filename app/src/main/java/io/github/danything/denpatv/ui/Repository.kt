package io.github.danything.denpatv.ui

import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingPager
import io.github.danything.denpatv.data.Service
import java.net.URI

/**
 * 画面が使う denpa の窓口。一覧は画面をまたいで使うので (ライブの局送り・録画を開く) ここに控える。
 * トークンがあれば API・絵・映像のどれにも付ける
 */
class Repository(val app: DenpaApp, val base: URI, val token: String?) {
    val api = DenpaApi { token }

    var services: List<Service> = emptyList()
        private set
    private val pager = RecordingPager(PAGE) { limit, offset -> api.recordings(base, limit, offset) }

    val recordings: List<Recording> get() = pager.items

    /** まだ読める録画があるか (少しずつ読むので) */
    val hasMoreRecordings: Boolean get() = pager.hasMore

    /** 録画を頭から読み直す */
    suspend fun refreshRecordings() = pager.refresh()

    /** 録画の続きを読む (一覧の終わりに近づいたら)。読み込み中なら何もしない */
    suspend fun loadMoreRecordings() = pager.loadMore()

    /** 消した録画を手元の一覧から抜く */
    fun forgetRecording(id: Long) = pager.remove(id)

    /** 局だけ取り直す (いま放送中の番組が変わるので) */
    suspend fun refreshServices() {
        services = api.services(base)
    }

    companion object {
        const val PAGE = 60
    }

    fun url(relative: String?): String? = relative?.let { BaseUrl.resolve(base, it)?.toString() }
}
