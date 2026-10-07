package io.github.danything.denpatv.data

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
 * 空白・飾りの記号・番号・「今回の」を外してから見る。知らない見出しは [DetailKind.Credit]
 */
fun detailKind(heading: String): DetailKind {
    val key = heading
        .filterNot { it.isWhitespace() || it in DECORATION || it.isDigit() || it in '①'..'⑳' }
        .removePrefix("今回の")
        .uppercase()
    return when {
        "ホームページ" in key || key.endsWith("HP") || "サイト" in key || key == "URL" || key == "WEB" -> DetailKind.Link
        "お知らせ" in key || "おしらせ" in key || "告知" in key || key == "ご案内" -> DetailKind.Notice
        "番組内容" in key || "あらすじ" in key || "ストーリー" in key || key in STORY_KEYS -> DetailKind.Story
        "出演" in key || "キャスト" in key || "ゲスト" in key || "司会" in key || key in CAST_KEYS -> DetailKind.Cast
        else -> DetailKind.Credit
    }
}

private const val DECORATION = "◇◆■□●○◎★☆▼▽▲△♪【】［］[]（）()「」〈〉＜＞<>:："
private val STORY_KEYS = setOf("内容", "みどころ", "見どころ", "解説", "番組紹介", "概要")
private val CAST_KEYS = setOf("語り", "ナレーション", "ナレーター", "MC", "声優")

/** 詳しくの本文。上から読みもの → 出演者 → ほか (知らせは最後) の順に1列で出す */
data class DetailText(
    /** 説明と番組内容・あらすじの段落。重なり (説明が番組内容の頭だけ、など) は除いてある */
    val story: List<String> = emptyList(),
    /** 出演者 (見出し → 1行に詰めた本文。1人1行のままだと縦に長くなる) */
    val cast: List<Pair<String, String>> = emptyList(),
    /** 制作・音楽など、最後におしらせ (見出し → 1行に詰めた本文) */
    val notes: List<Pair<String, String>> = emptyList(),
) {
    val isEmpty: Boolean get() = story.isEmpty() && cast.isEmpty() && notes.isEmpty()
}

/**
 * 説明と放送の詳細を、詳しくの本文の並びにする。並びは放送のまま (番組内容1 → 2)。
 * - 説明と番組内容・あらすじは段落にまとめる。**ほかの段落に含まれる段落は出さない** (説明は番組内容の頭や一部のことが多い)
 * - ホームページは出さない。どの本文も、URL だけの行は外す (開けない)
 * - 出演者・制作・音楽・おしらせなどは改行を「／」で繋いで詰める
 */
fun arrangeDetail(description: String, extended: List<Pair<String, String>>): DetailText {
    val sections = extended.mapNotNull { (heading, body) ->
        val kind = detailKind(heading)
        val text = withoutUrls(body)
        if (kind == DetailKind.Link || text.isEmpty()) null else Triple(kind, heading.trim(), text)
    }
    val paragraphs = listOf(withoutUrls(description)).filter { it.isNotEmpty() } +
        sections.filter { it.first == DetailKind.Story }.map { it.third }
    val keys = paragraphs.map { squash(it).trimEnd('…', '‥', '.') }
    val story = paragraphs.filterIndexed { i, _ ->
        // 同じ段落は先の1つだけ、ほかの段落に含まれる段落は除く
        keys.indices.none { j -> j != i && keys[i] in keys[j] && (keys[j].length > keys[i].length || j < i) }
    }
    fun of(kind: DetailKind) = sections.filter { it.first == kind }.map { it.second to it.third }
    return DetailText(
        story = story,
        cast = of(DetailKind.Cast).map { (heading, body) -> heading to oneLine(body) },
        notes = (of(DetailKind.Credit) + of(DetailKind.Notice)).map { (heading, body) -> heading to oneLine(body) },
    )
}

private val URL_LINE = Regex("""^\s*(https?://|www\.)\S*\s*$""")

private fun withoutUrls(text: String): String =
    text.lines().filterNot { URL_LINE.matches(it) }.joinToString("\n").trim()

private fun squash(text: String): String = text.filterNot { it.isWhitespace() }

/** 改行を「／」で繋ぐ。「【スタッフ】」「演出：」のような見出しの行は、次の行と空白で繋ぐ */
private fun oneLine(text: String): String {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return buildString {
        lines.forEachIndexed { i, line ->
            if (i > 0) append(if (lines[i - 1].last() in "】：:") "　" else " ／ ")
            append(line)
        }
    }
}
