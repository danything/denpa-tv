package io.github.danything.denpatv.ui

import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.Recording
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
    var recordings: List<Recording> = emptyList()
        private set

    suspend fun refresh() {
        services = api.services(base)
        recordings = api.recordings(base)
    }

    /** 局だけ取り直す (いま放送中の番組が変わるので) */
    suspend fun refreshServices() {
        services = api.services(base)
    }

    fun url(relative: String?): String? = relative?.let { BaseUrl.resolve(base, it)?.toString() }
}
