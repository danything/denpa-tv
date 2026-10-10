package io.github.danything.denpatv.ui

import android.graphics.Typeface
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.danything.denpatv.data.Http
import io.github.danything.denpatv.data.isSfnt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * **字幕の字** (denpa が字幕を焼いていたのと同じ丸ゴシック。Rounded M+ 1m for ARIB)。`GET api/font?format=ttf` を
 * **1度だけ取ってアプリの中に置き**、次からはそれを使う (同じ字なので denpa を替えても取り直さない)。
 * ブラウザ向けの woff2 は Android の Typeface で読めないので ttf を頼む。ttf を返さない denpa (古い・手元の開発で
 * 字が入っていない) なら、その起動の間は頼み直さず端末の字で描く
 */
class CaptionFont(private val dir: File) {
    /** 読めた字。まだ・読めなければ null (端末の字で描く) */
    var typeface by mutableStateOf<Typeface?>(null)
        private set

    private val lock = Mutex()
    private var tried = false

    /** 置いてあれば読み、無ければ取ってくる。何度呼んでもよい (1度しかしない) */
    suspend fun load(url: String?, token: String?) = lock.withLock {
        if (tried) return@withLock
        tried = true
        val file = File(dir, FILE)
        typeface = withContext(Dispatchers.IO) {
            if (!file.exists() && url != null) {
                try {
                    download(URI(url), token, file)
                } catch (e: IOException) {
                    Log.i(TAG, "字幕の字を取れませんでした (端末の字で描きます): ${e.message}")
                }
            }
            if (!file.exists()) return@withContext null
            runCatching { Typeface.createFromFile(file) }.getOrNull().also {
                // 読めないものは捨てる (次の起動で取り直す)
                if (it == null) file.delete()
            }
        }
    }

    private fun download(url: URI, token: String?, file: File) {
        val connection = Http.connection(url, token)
        try {
            if (connection.responseCode != 200) throw IOException("${connection.responseCode} $url")
            val part = File(dir, "$FILE.part")
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("大きすぎます")
                        output.write(buffer, 0, n)
                    }
                }
            }
            // woff2 (ttf を知らない denpa はこちらを返す) などは置かない
            val head = ByteArray(4)
            val read = part.inputStream().use { it.read(head) }
            if (read != 4 || !isSfnt(head)) {
                part.delete()
                throw IOException("ttf ではありません")
            }
            if (!part.renameTo(file)) throw IOException("置けません")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** 字の置き場 (denpa の根からの相対)。付けなければ woff2 が来る */
        const val PATH = "api/font?format=ttf"
        private const val TAG = "denpa"
        private const val FILE = "caption-font.ttf"
        /** 字は 5.5MB ほど。壊れた返事で何百 MB も置かない */
        private const val MAX_BYTES = 32L * 1024 * 1024
    }
}
