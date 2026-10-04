package io.github.danything.denpatv.ui

import io.github.danything.denpatv.DenpaApp
import io.github.danything.denpatv.data.BaseUrl
import io.github.danything.denpatv.data.Recording
import io.github.danything.denpatv.data.Service
import okhttp3.HttpUrl

/**
 * 画面が使う denpa の窓口。一覧は画面をまたいで使うので (ライブの局送り・録画を開く) ここに控える
 */
class Repository(val app: DenpaApp, val base: HttpUrl) {
    var services: List<Service> = emptyList()
        private set
    var recordings: List<Recording> = emptyList()
        private set

    suspend fun refresh() {
        services = app.api.services(base)
        recordings = app.api.recordings(base)
    }

    fun url(relative: String?): String? = relative?.let { BaseUrl.resolve(base, it)?.toString() }
}
