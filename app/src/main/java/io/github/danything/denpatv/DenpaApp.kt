package io.github.danything.denpatv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * アプリ全体で1つずつ持つもの。**DI の仕組みは入れない** (docs/libraries.md)。
 * 数が少ないので、ここで作って画面に渡すほうが追いやすい
 */
class DenpaApp : Application(), SingletonImageLoader.Factory {
    /** API・ロゴ/ポスター・再生で同じ接続の溜めを使う */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // ライブは流し続けるので読みの時間切れは長めに (止まったら ExoPlayer が言う)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val api: DenpaApi by lazy { DenpaApi(http) }
    val settings: Settings by lazy { Settings(this) }
    val decoders: Decoders by lazy { Decoders.detect() }

    /** 画面より長く生きる仕事 (閉じたときに観た位置を預けるなど) */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .build()
}
