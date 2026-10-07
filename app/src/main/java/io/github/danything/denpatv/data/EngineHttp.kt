package io.github.danything.denpatv.data

import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresExtension
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import java.util.concurrent.Executors

/**
 * OS の HttpEngine で映像を取る口。**`HttpEngine` の型はこの中から外へ出さない。**
 *
 * `HttpEngine?` 型の値をほかのところで持つと、null でも型の確かめ (check-cast) で
 * ART がクラスを引きにいき、HttpEngine の無い端末で NoClassDefFoundError になる
 * (denpa-tv#24。Android TV 12 の BRAVIA は S 拡張 7 以上と答えるのにクラスが無い)。
 * このクラスを作るのは `hasHttpEngine()` が通ったときだけ (DenpaApp.engineHttp)
 */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
class EngineHttp(context: Context) {
    // アプリで1つを使い回す (Media3 のネットワーク スタックの頁の勧め)
    private val engine = HttpEngine.Builder(context).build()

    // 答えを返す先。映像を開くたびに作ると、そのたびにスレッドが残る
    private val executor = Executors.newSingleThreadExecutor()

    @OptIn(UnstableApi::class)
    fun factory(): HttpDataSource.Factory =
        HttpEngineDataSource.Factory(engine, executor)
            .setConnectionTimeoutMs(Http.CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(Http.READ_TIMEOUT_MS)
            .setContentTypePredicate(::isMediaContentType)
}
