package io.github.danything.denpatv

import android.app.Application
import android.net.http.HttpEngine
import android.os.Build
import android.os.ext.SdkExtensions
import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.Settings
import io.github.danything.denpatv.data.WatchNextRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * アプリ全体で1つずつ持つもの。**DI の仕組みは入れない** (docs/libraries.md)。
 * 数が少ないので、ここで作って画面に渡すほうが追いやすい
 */
class DenpaApp : Application() {
    val settings: Settings by lazy { Settings(this) }
    val decoders: Decoders by lazy { Decoders.detect() }

    /**
     * 映像を取る HttpEngine。**アプリで1つを使い回す** (Media3 のネットワーク スタックの頁の勧め)。
     * OS に入っているのは Android 14 (S 拡張 7) から。それより前の端末では null で、
     * Media3 の DefaultHttpDataSource (OS の HttpURLConnection) を使う
     */
    val httpEngine: HttpEngine? by lazy {
        if (hasHttpEngine()) HttpEngine.Builder(this).build() else null
    }

    /** HttpEngine が答えを返す先。映像を開くたびに作ると、そのたびにスレッドが残る */
    val httpExecutor: Executor by lazy { Executors.newSingleThreadExecutor() }

    /** Google TV のホームの「続きを視聴」。TvProvider の WatchNextPrograms は Android 8.0 からで、それより前は何もしない */
    val watchNext: WatchNextRows by lazy { WatchNextRows(this) }

    /** 画面より長く生きる仕事 (閉じたときに観た位置を預けるなど) */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

fun hasHttpEngine(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
