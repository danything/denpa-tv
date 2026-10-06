package io.github.danything.denpatv

import android.app.Application
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import io.github.danything.denpatv.data.Decoders
import io.github.danything.denpatv.data.EngineHttp
import io.github.danything.denpatv.data.Images
import io.github.danything.denpatv.data.Settings
import io.github.danything.denpatv.data.WatchNextRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * アプリ全体で1つずつ持つもの。**DI の仕組みは入れない** (docs/libraries.md)。
 * 数が少ないので、ここで作って画面に渡すほうが追いやすい
 */
class DenpaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 絵を覚えておく量を、端末のメモリに合わせる
        Images.init(this)
    }

    val settings: Settings by lazy { Settings(this) }
    val decoders: Decoders by lazy { Decoders.detect() }
    val updater: Updater by lazy { Updater(this) }

    /**
     * 映像を HttpEngine で取る口。**アプリで1つを使い回す** (Media3 のネットワーク スタックの頁の勧め)。
     * OS に入っているのは Android 14 (S 拡張 7) から。使えない端末では null で、
     * Media3 の DefaultHttpDataSource (OS の HttpURLConnection) を使う。
     * 型を `HttpEngine?` にしないわけは EngineHttp に書いた
     */
    val engineHttp: EngineHttp? by lazy {
        if (hasHttpEngine()) {
            try {
                EngineHttp(this)
            } catch (error: LinkageError) {
                // 拡張の版は足りていると言うのに、OS にクラスが無い機種がある (下の hasHttpEngine)
                Log.w("denpa", "HttpEngine を使えないので OS の HttpURLConnection で取ります", error)
                null
            }
        } else {
            null
        }
    }

    /** Google TV のホームの「続きを視聴」。TvProvider の WatchNextPrograms は Android 8.0 からで、それより前は何もしない */
    val watchNext: WatchNextRows by lazy { WatchNextRows(this) }

    /** 画面より長く生きる仕事 (閉じたときに観た位置を預けるなど) */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * HttpEngine が使えるか。**S 拡張の版だけでは決めない** — Android TV 12 の BRAVIA (KJ-75X80WK) は
 * S 拡張 7 以上と答えるのに `android.net.http.HttpEngine` が無く、映像を開いた瞬間に
 * NoClassDefFoundError で落ちていた (denpa-tv#24)。クラスが本当に引けるかも確かめる
 */
fun hasHttpEngine(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7 &&
        runCatching { Class.forName("android.net.http.HttpEngine") }.isSuccess
