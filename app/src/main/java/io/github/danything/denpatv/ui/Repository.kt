package io.github.danything.denpatv.ui

import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.RecordingList
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
    private val list = RecordingList { api.recordings(base) }

    val recordings: List<Recording> get() = list.items

    /** 録画を読み直す (全部) */
    suspend fun refreshRecordings() = list.refresh()

    /** 一覧が古くなった (追っかけで観た録画が録り終えた・焼き上がったかもしれない)。一覧に戻ったら読み直す */
    var recordingsStale = false

    /** 録画の一覧に戻ったとき合わせる先 (再生の画面で消したときの隣) */
    var focusOnReturn: Long? = null

    /** 消した録画を手元の一覧から抜く。隣の id を返す */
    fun forgetRecording(id: Long): Long? = list.remove(id)

    /** 局だけ取り直す (いま放送中の番組が変わるので) */
    suspend fun refreshServices() {
        services = api.services(base)
    }

    fun url(relative: String?): String? = relative?.let { BaseUrl.resolve(base, it)?.toString() }
}
