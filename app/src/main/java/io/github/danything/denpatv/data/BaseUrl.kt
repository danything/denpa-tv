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
     * **スキームが重なっていたら内側を使う** — 繋ぐ画面の欄ははじめから `http://` が入っているので、そのあとに URL をまるごと
     * 打つと `http://https://…` になる。読めなければ null
     */
    fun normalize(input: String): URI? {
        val trimmed = input.trim().replace(DOUBLED_SCHEME, "$1")
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

    /**
     * 繋いでみる順。**http でポートを書いていなければ、80 のあとに `fallbackPort` (denpa の compose の既定の 3000) も試す**
     * (`192.168.1.10` とだけ打った人の多くは、3000 で開いている)
     */
    fun candidates(base: URI, fallbackPort: Int = DENPA_PORT): List<URI> =
        if (base.scheme == "http" && base.port == -1) {
            listOf(base, URI(base.scheme, null, base.host, fallbackPort, base.path, null, null))
        } else {
            listOf(base)
        }

    /** denpa が返した相対の URL を、開いている URL に足す */
    fun resolve(base: URI, relative: String): URI? = try {
        base.resolve(relative)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** denpa の compose の既定のポート */
    const val DENPA_PORT = 3000

    /** 頭に重なったスキーム (`http://http://`・`http://https://`)。いちばん内側だけ残す */
    private val DOUBLED_SCHEME = Regex("^(?:https?://)+(https?://)", RegexOption.IGNORE_CASE)
}
