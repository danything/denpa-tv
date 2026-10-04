package io.github.danything.denpatv.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * denpa を開いている URL。**前段の接頭辞 (`/denpa/` など) の下でも動く**ように、
 * 末尾を `/` で終わらせておき、denpa が返す相対の URL (`api/…`) をそこに足す。
 */
object BaseUrl {
    /**
     * 人が打った文字を URL に直す。スキームが無ければ http、末尾は `/` にそろえる。
     * 読めなければ null
     */
    fun normalize(input: String): HttpUrl? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        val path = url.encodedPath
        return if (path.endsWith("/")) url else url.newBuilder().encodedPath("$path/").build()
    }

    /** denpa が返した相対の URL を、開いている URL に足す */
    fun resolve(base: HttpUrl, relative: String): HttpUrl? = base.resolve(relative)
}
