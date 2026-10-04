package io.github.danything.denpatv.data

import java.net.URI
import java.net.URISyntaxException

/**
 * denpa を開いている URL。**前段の接頭辞 (`/denpa/` など) の下でも動く**ように、
 * 末尾を `/` で終わらせておき、denpa が返す相対の URL (`api/…`) をそこに足す。
 */
object BaseUrl {
    /**
     * 人が打った文字を URL に直す。スキームが無ければ http、末尾は `/` にそろえる。
     * 読めなければ null
     */
    fun normalize(input: String): URI? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val url = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return null
        }
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.host.isNullOrEmpty()) return null
        val path = url.rawPath.orEmpty().ifEmpty { "/" }
        return URI(url.scheme, null, url.host, url.port, null, null, null)
            .resolve(if (path.endsWith("/")) path else "$path/")
    }

    /** denpa が返した相対の URL を、開いている URL に足す */
    fun resolve(base: URI, relative: String): URI? = try {
        base.resolve(relative)
    } catch (_: IllegalArgumentException) {
        null
    }
}
