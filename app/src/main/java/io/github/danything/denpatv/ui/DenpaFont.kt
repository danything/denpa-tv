package io.github.danything.denpatv.ui

import android.content.res.AssetManager
import android.graphics.Typeface
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **放送の字** (denpa が字幕を焼いていたのと同じ丸ゴシック。Denpa Font。ARIB の外字を全部持つ)。APK の assets に入れてある
 * (焼くときに取ってくる。app/build.gradle.kts の `fetchCaptionFont`)。字幕の文字の配置 (`typeface`) と、
 * 放送から来た字 (番組名・説明・局名。`family` → `BroadcastFont`) の両方に使う。
 * 読むのはアプリを開いたときに1度だけ (`DenpaApp`。4.2MB あるので画面の糸では読まない)。読めなければ・読み終えるまでは端末の字
 */
class DenpaFont(private val assets: AssetManager) {
    /** 読めた字。まだ・読めなければ null (端末の字で描く) */
    var typeface by mutableStateOf<Typeface?>(null)
        private set

    /** 同じ字を Compose の字体にしたもの。まだ・読めなければ null */
    var family by mutableStateOf<FontFamily?>(null)
        private set

    private val lock = Mutex()
    private var done = false

    /** 何度呼んでもよい (読むのは1度だけ) */
    suspend fun load() = lock.withLock {
        if (done) return@withLock
        val loaded = withContext(Dispatchers.IO) {
            runCatching { Typeface.createFromAsset(assets, ASSET) }
                .onFailure { Log.w("denpa", "Denpa Font を読めません (端末の字で描きます)", it) }
                .getOrNull()
        }
        // 読み終えてから印を付ける (途中で取り消されたら、次に呼ばれたときに読み直す)
        typeface = loaded
        family = loaded?.let { FontFamily(it) }
        done = true
    }

    private companion object {
        /** app/build.gradle.kts の `fetchCaptionFont` が置く名前 */
        const val ASSET = "denpa-font.ttf"
    }
}

/** `DenpaTheme` が渡す放送の字 (`DenpaFont.family`) */
internal val LocalBroadcastFont = compositionLocalOf<FontFamily?> { null }

/**
 * **放送から来た字 (番組名・説明・局名・番組表の名前) に使う字体。** テレビと同じ Denpa Font で、外字 (EPG の記号) も白黒で出る。
 * アプリの札・見出し・設定 (こちらで書いた字) には使わない。読めていなければ null で、`Text` は端末の字で描く
 */
val BroadcastFont: FontFamily?
    @Composable @ReadOnlyComposable
    get() = LocalBroadcastFont.current

/** 放送から来た字の印 (`appendBroadcast`) */
private const val BROADCAST_TAG = "broadcast"

/** 1行にこちらの字 (日時・残り) と放送の字が混ざるとき、放送の字に印を付けて足す。描くときに `withBroadcastFont` で字体を当てる */
fun AnnotatedString.Builder.appendBroadcast(text: String) {
    pushStringAnnotation(BROADCAST_TAG, "")
    append(text)
    pop()
}

/** 印 (`appendBroadcast`) の付いたところを放送の字にする */
@Composable
@ReadOnlyComposable
fun AnnotatedString.withBroadcastFont(): AnnotatedString {
    val family = BroadcastFont ?: return this
    val marks = getStringAnnotations(BROADCAST_TAG, 0, length)
    if (marks.isEmpty()) return this
    return AnnotatedString.Builder(this).apply {
        marks.forEach { addStyle(SpanStyle(fontFamily = family), it.start, it.end) }
    }.toAnnotatedString()
}
