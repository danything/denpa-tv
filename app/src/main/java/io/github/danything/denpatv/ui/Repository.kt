package io.github.danything.denpatv.ui

import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.Service
import java.net.URI

/**
 * 画面が使う denpa の窓口。一覧は画面をまたいで使うので (ライブの局送り・録画を開く) ここに控える
 */
class Repository(val app: DenpaApp, val base: URI) {
    var services: List<Service> = emptyList()
        private set
    var recordings: List<Recording> = emptyList()
        private set

    suspend fun refresh() {
        services = app.api.services(base)
        recordings = app.api.recordings(base)
    }

    /** 局だけ取り直す (いま放送中の番組が変わるので) */
    suspend fun refreshServices() {
        services = app.api.services(base)
    }

    fun url(relative: String?): String? = relative?.let { BaseUrl.resolve(base, it)?.toString() }
}
