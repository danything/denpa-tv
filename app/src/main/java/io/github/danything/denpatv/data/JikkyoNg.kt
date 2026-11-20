package io.github.danything.denpatv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/**
 * **NG ワード** (世帯で1つの一覧。denpa の設定の「連携」の「実況」で書く。`GET api/jikkyo/ng`)。含むコメントは流さない。
 * 決まりはブラウザの denpa (`ng.ts`) と同じ:
 *
 * - 部分一致だけ (正規表現は使わない)。全角・半角、半角カナ、英字の大小、ひらがな・カタカナの違いは同じとみなす
 * - `NG_MAX` 語まで、1語 `NG_LENGTH` 文字まで (絵文字も1文字)。超えたぶんは捨てる
 */
object JikkyoNg {
    /** 語の数の上限 */
    const val NG_MAX = 100
    /** 1語の長さの上限 (文字) */
    const val NG_LENGTH = 40

    /** 比べられるようにそろえる。NFKC (全角・半角・半角カナ) → 小文字 → カタカナをひらがなに */
    fun fold(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val out = StringBuilder(normalized.length)
        for (c in normalized) out.append(if (c in 'ァ'..'ヶ' || c == 'ヽ' || c == 'ヾ') c - 0x60 else c)
        return out.toString()
    }

    /** 語の一覧から、コメントの本文が NG ワードを含むかを返すものを作る */
    fun compile(words: List<String>): (String) -> Boolean {
        val folded = words.map(::fold).filter { it.isNotEmpty() }
        if (folded.isEmpty()) return { false }
        return { text ->
            val target = fold(text)
            folded.any { target.contains(it) }
        }
    }

    /**
     * `GET api/jikkyo/ng` の答え (`{"words":[…]}`) を読む。文字列でないもの・空のもの・長すぎるものは捨て、上限を超えたぶんも捨てる。
     * 形式が違えば null (呼ぶ側が1回だけ警告して、NG ワード無しで流す)
     */
    fun parse(body: String): List<String>? {
        val json = runCatching { lenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val words = json["words"] as? JsonArray ?: return null
        return words.asSequence()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { word -> word.isString }?.content?.trim() }
            .filter { it.isNotEmpty() && it.codePointCount(0, it.length) <= NG_LENGTH }
            .take(NG_MAX)
            .toList()
    }
}
