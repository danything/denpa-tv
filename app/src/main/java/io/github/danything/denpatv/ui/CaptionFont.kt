package io.github.danything.denpatv.ui

import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **字幕の字** (denpa が字幕を焼いていたのと同じ丸ゴシック。Rounded M+ 1m for ARIB)。APK の assets に入れてある
 * (焼くときに取ってくる。app/build.gradle.kts の `fetchCaptionFont`)。読むのは初めて字幕を描くときに1度だけ
 * (5MB あるので画面の糸では読まない)。読めなければ端末の字で描く
 */
class CaptionFont(private val assets: AssetManager) {
    /** 読めた字。まだ・読めなければ null (端末の字で描く) */
    var typeface by mutableStateOf<Typeface?>(null)
        private set

    private val lock = Mutex()
    private var done = false

    /** 何度呼んでもよい (読むのは1度だけ) */
    suspend fun load() = lock.withLock {
        if (done) return@withLock
        val loaded = withContext(Dispatchers.IO) {
            runCatching { Typeface.createFromAsset(assets, ASSET) }
                .onFailure { Log.w("denpa", "字幕の字を読めません (端末の字で描きます)", it) }
                .getOrNull()
        }
        // 読み終えてから印を付ける (途中で取り消されたら、次に字幕が出たときに読み直す)
        typeface = loaded
        done = true
    }

    private companion object {
        /** app/build.gradle.kts の `fetchCaptionFont` が置く名前 */
        const val ASSET = "caption-font.ttf"
    }
}
