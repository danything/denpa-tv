package io.github.danything.denpatv.data

import java.net.URLDecoder
import java.text.Normalizer

/**
 * 外から局や録画を直に開くリンク (`denpa://…`)。Home Assistant の Android TV Remote (`media_player.play_media` の url) や
 * `adb shell am start -a android.intent.action.VIEW -d <uri>` から来る。README の「リンクで開く」の表と同じ
 */
sealed interface DeepLink {
    /** ライブ。`channel` は局の id・名前・番号 (無ければ最後に観ていた局) */
    data class Live(val channel: String?) : DeepLink

    /** 録画の一覧 */
    data object Recordings : DeepLink

    /** 録画を観る。`id` は渡されたまま (数でなければ見つからないだけ) */
    data class Watch(val id: String) : DeepLink

    companion object {
        /**
         * `denpa://live`・`denpa://live/<局>`・`denpa://recordings`・`denpa://recording/<id>` を読む。
         * 局の名前は % で符号化してもしなくてもよい。ほかの形は null
         */
        fun parse(uri: String?): DeepLink? {
            val s = uri?.trim() ?: return null
            if (!s.startsWith(SCHEME, ignoreCase = true)) return null
            // 問い合わせ (?…) と断片 (#…) は使わない
            val path = s.substring(SCHEME.length).substringBefore('#').substringBefore('?').trimEnd('/')
            val host = path.substringBefore('/').lowercase()
            val arg = path.substringAfter('/', "").let(::decode).trim().takeIf { it.isNotEmpty() }
            return when (host) {
                "live" -> Live(arg)
                "recordings" -> Recordings
                "recording" -> arg?.let(::Watch) ?: Recordings
                else -> null
            }
        }

        private const val SCHEME = "denpa://"

        /** % を解く。`+` はそのまま (局の名前に入りうる)。崩れていれば渡されたまま */
        private fun decode(s: String): String =
            runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
    }
}

/**
 * リンクの局を一覧から探す。**id → 名前そのまま → 名前を揃えて (全角・半角、大文字・小文字、空白) →
 * 番号 (リモコン番号・BS/CS の3桁) → 揃えた名前の頭** の順。番号と頭は、放送している局を先に (サブチャンネルより本放送)。
 * 放送の局名は「ＮＨＫ総合１・東京」のように来るので、「NHK総合」でも頭で当たる。見つからなければ null。
 * id (`ネットワーク × 100000 + サービス ID`) は 6 桁以上、番号は 3 桁までなので、数だけの入力でも取り違えない
 */
fun findService(services: List<Service>, query: String): Service? {
    val q = query.trim()
    if (q.isEmpty()) return null
    q.toLongOrNull()?.let { id -> services.firstOrNull { it.id == id }?.let { return it } }
    services.firstOrNull { it.name == q }?.let { return it }
    val n = normalizeName(q)
    services.firstOrNull { normalizeName(it.name) == n }?.let { return it }
    val preferred = airing(services) + services
    n.toIntOrNull()?.let { number -> preferred.firstOrNull { it.number == number }?.let { return it } }
    return preferred.firstOrNull { normalizeName(it.name).startsWith(n) }
}

/** 名前を比べる形に: NFKC (全角英数を半角に) ・小文字・空白を抜く */
internal fun normalizeName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase().filterNot { it.isWhitespace() }
