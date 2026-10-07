package io.github.danything.denpatv.data

import java.text.Normalizer

/**
 * 題の話数 (`#1`・`＃12`・`第3話`・`第8回`・NHK の `(39)`)。最初に出てきたものを話数と見なす (話数は番組名のすぐ後ろに来る。
 * 後ろのサブタイトルの中の数字は拾わない)。`第2期` のような期は話数にしない
 */
private val EPISODE = Regex("""[#＃]\s*\d{1,4}|第\s*\d{1,4}\s*[話回]|[(（]\d{1,4}[)）]""")

/**
 * **収まらない題は、話数を残して途中を切る** (`凶乱令嬢ニア・リストン 病弱令嬢に転生した神殺し… #1`)。
 * 録画の絵は放送から切り出したもの (暗転・CM・字幕の無い場面) で何の番組か分からないことがあるので、題で見分ける。
 * 末尾を切ると、同じ番組の続けて録った回 (`… #1` と `… #2`) が同じ字になって見分けられない。
 * 呼ぶのは題が収まらないときだけ。
 *
 * - 話数が無い、または話数までは見えている (後ろのサブタイトルだけはみ出す) なら、そのまま (末尾を `…` で切ってよい)
 * - そうでなければ、話数の前を `…` で縮めて話数をつなぐ (話数より後ろのサブタイトルは落とす)。番組名の頭はできるだけ長く残す
 *
 * `visible` は枠に入る字数 (… を付けずに組んだとき。末尾で切ると、そのうち最後の1字が `…` になる)、
 * `room` は末尾に `tail` を置いたときに頭を何字まで残せそうかの見積もり、`fits` はその字が枠に収まるか。
 * 見積もりから1字ずつ減らして、収まるものを探す (画面では組んで確かめるので、確かめる回数を少なく)
 */
fun keepEpisode(title: String, visible: Int, room: (tail: String) -> Int, fits: (String) -> Boolean): String {
    val episode = EPISODE.find(title) ?: return title
    val head = title.substring(0, episode.range.first).trimEnd()
    // 頭が無い (題が話数から始まる)、または話数と終わりの … まで見えている。話数がちょうど最後の字なら、縮める
    // (末尾の … が話数の終わりに掛かる)。縮めるときは頭を少なくとも1字落とす (何も落とさずに … を付けない)
    if (head.isEmpty() || episode.range.last + 1 < visible) return title
    val tail = ELLIPSIS + " " + episode.value
    var kept = minOf(room(tail), head.length - 1)
    while (kept > 0 && !fits(head.take(kept).trimEnd() + tail)) kept--
    // 1字も残せなければ、そのまま (末尾で切る)
    return if (kept <= 0) title else head.take(kept).trimEnd() + tail
}

private const val ELLIPSIS = "…"

/**
 * 局の名前を短く (全角の英数字・記号を半角に。`ＴＯＫＹＯ　ＭＸ１` → `TOKYO MX1`)。放送波の局名は全角混じりで、
 * 狭いカードでは幅を取る (番組名は denpa が半角に寄せて返す)
 */
fun shortServiceName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC).replace(SPACES, " ").trim()

private val SPACES = Regex("\\s+")
