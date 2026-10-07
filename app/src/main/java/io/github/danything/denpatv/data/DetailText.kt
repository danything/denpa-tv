package io.github.danything.denpatv.data

import java.text.Normalizer

/** 放送の詳細の見出しの種類。詳しくの本文で、どこにどの大きさで出すかを決める */
enum class DetailKind {
    /** 番組内容・あらすじ。説明と1つの読みものにまとめて先頭に */
    Story,

    /** 出演者・声の出演。読みものの次に */
    Cast,

    /** 制作・スタッフ・原作・監督・音楽・主題歌など (と知らない見出し)。小さく詰めて */
    Credit,

    /** おしらせ。いちばん最後に、ほかと同じく小さく */
    Notice,

    /** ホームページ・HP。テレビでは開けないので出さない */
    Link,
}

/**
 * 見出しの種類。局ごとの書き方の揺れ (「番組内容1」「番組内容①」「今回の番組内容」「あらすじ◇」「スタッフ2」) は、
 * NFKC で揃え (全角英数字・半角カナ・丸数字)、空白・飾りの記号・番号・「今回の」を外してから見る。
 * 知らない見出しは [DetailKind.Credit]
 */
fun detailKind(heading: String): DetailKind {
    val key = nfkc(heading)
        .filterNot { it.isWhitespace() || it in DECORATION || it.isDigit() }
        .removePrefix("今回の")
        .uppercase()
    return when {
        // おしらせが先 (「お知らせ・ホームページ」の本文を消さない。URL だけは外れる)
        NOTICE_WORDS.any { it in key } -> DetailKind.Notice
        "ホームページ" in key || key.endsWith("HP") || key.endsWith("サイト") || key == "URL" || key == "WEB" -> DetailKind.Link
        // 出演者が番組内容より先 (「出演者紹介」は出演者)
        "出演" in key || "キャスト" in key || "ゲスト" in key || "司会" in key || key in CAST_KEYS -> DetailKind.Cast
        STORY_WORDS.any { it in key } || key.endsWith("内容") || key.endsWith("概要") -> DetailKind.Story
        else -> DetailKind.Credit
    }
}

/** 飾りの記号 (NFKC の後に比べるので、全角の括弧・コロンは半角で書く) */
private const val DECORATION = "◇◆■□●○◎★☆▼▽▲△♪※〓・【】「」『』〈〉《》〔〕≪≫«»[]()<>:"

private val NOTICE_WORDS = listOf("お知らせ", "おしらせ", "オシラセ", "告知", "ご案内")

/** 含んでいれば読みもの。ほかに「〜内容」「〜概要」で終わる見出し (番組内容・放送内容・番組概要) */
private val STORY_WORDS = listOf("あらすじ", "アラスジ", "ストーリー", "みどころ", "見どころ", "番組紹介", "作品紹介")
private val CAST_KEYS = setOf("語り", "ナレーション", "ナレーター", "MC", "声優")

/** 詳しくの本文。上から読みもの → 出演者 → ほか (知らせは最後) の順に1列で出す */
data class DetailText(
    /** 説明と番組内容・あらすじの段落。重なり (説明が番組内容の頭だけ、など) は除いてある */
    val story: List<String> = emptyList(),
    /** 出演者 (見出し → 1行に詰めた本文。1人1行のままだと縦に長くなる) */
    val cast: List<Pair<String, String>> = emptyList(),
    /** 制作・音楽など → おしらせ の順 (見出し → 1行に詰めた本文) */
    val notes: List<Pair<String, String>> = emptyList(),
) {
    val isEmpty: Boolean get() = story.isEmpty() && cast.isEmpty() && notes.isEmpty()
}

/**
 * 説明と放送の詳細を、詳しくの本文の並びにする。並びは放送のまま (番組内容1 → 2)。
 * - 説明と番組内容・あらすじは段落にまとめる。**ほかの段落に含まれる段落は出さない** (説明は番組内容の頭や一部のことが多い)
 * - ホームページは出さない。どの本文も URL は外し、URL だけだった行 (「番組HP：」が残る行も) は行ごと外す (開けない)
 * - 出演者・制作・音楽・おしらせなどは改行を「／」で繋いで詰める
 */
fun arrangeDetail(description: String, extended: List<Pair<String, String>>): DetailText {
    val sections = extended.mapNotNull { (heading, body) ->
        val kind = detailKind(heading)
        val text = withoutUrls(body)
        if (kind == DetailKind.Link || text.isEmpty()) null else Triple(kind, heading.trim().trim { it in DECORATION || it in "【】" }, text)
    }
    val paragraphs = listOf(withoutUrls(description)).filter { it.isNotEmpty() } +
        sections.filter { it.first == DetailKind.Story }.map { it.third }
    // 比べるときは NFKC で揃え (「…」「．」は「.」に、「･」は「・」に)、終わりの省略 (…・・・・) を外す
    val keys = paragraphs.map { squash(nfkc(it)).trimEnd('.', '・') }
    val story = paragraphs.filterIndexed { i, _ ->
        // 「…」だけの段落は除く。同じ段落は先の1つだけ、ほかの段落に含まれる段落は除く
        keys[i].isNotEmpty() &&
            keys.indices.none { j -> j != i && keys[i] in keys[j] && (keys[j].length > keys[i].length || j < i) }
    }
    fun of(kind: DetailKind) = sections.filter { it.first == kind }.map { it.second to it.third }
    return DetailText(
        story = story,
        cast = of(DetailKind.Cast).map { (heading, body) -> heading to oneLine(body) },
        notes = (of(DetailKind.Credit) + of(DetailKind.Notice)).map { (heading, body) -> heading to oneLine(body) },
    )
}

/** URL (全角も)。続く英数字・記号まで */
private val URL = Regex("""(?:https?|ｈｔｔｐｓ?)[:：][/／]{2}[!-~！-～]*|(?:www|ｗｗｗ)[.．][!-~！-～]*""", RegexOption.IGNORE_CASE)

private val SPACES = Regex(" {2,}")

/**
 * URL を外す (テレビでは開けない)。URL だけの行と、外すと「番組HP：」のような見出しだけが残る行は、行ごと外す。
 * 文の中の URL は URL だけ外す (1行の番組内容が丸ごと消えないように)
 */
private fun withoutUrls(text: String): String =
    text.lines().mapNotNull { line ->
        if (!URL.containsMatchIn(line)) return@mapNotNull line
        val rest = URL.replace(line, "").replace(SPACES, " ").trim()
        rest.takeUnless { it.isEmpty() || it.last() in "：:" }
    }.joinToString("\n").trim()

private fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

private fun squash(text: String): String = text.filterNot { it.isWhitespace() }

/** 改行を「／」で繋ぐ。「【スタッフ】」「演出：」のような見出しだけの行は、次の行と空白で繋ぐ */
private fun oneLine(text: String): String {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun heading(line: String) = line.last() in "：:" || (line.first() == '【' && line.last() == '】')
    return buildString {
        lines.forEachIndexed { i, line ->
            if (i > 0) append(if (heading(lines[i - 1])) "　" else " ／ ")
            append(line)
        }
    }
}
