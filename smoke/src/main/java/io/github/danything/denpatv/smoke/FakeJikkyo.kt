package io.github.danything.denpatv.smoke

/**
 * 偽の denpa (`FakeDenpa`) が返す実況のコメント (`api/jikkyo/kakolog`・`api/jikkyo/feed`。denpa の docs/api.md の「実況のコメント」)。
 * アプリは実況のコメントも denpa のサーバから受けるので、偽の denpa がそのまま答える。
 *
 * 過去ログは区間の中に 0.25 秒おきのコメントを返す。どれも緑の大きな「■」なので、スクリーンショットで流れたかが分かる (`SmokeTest` の緑の点)。
 * ライブの受け口は、いつもコメントの無い返事 (2 回目からは少し待たせる。ロングポーリングのつもり)
 */
object FakeJikkyo {
    /** コメントの間 (ミリ秒) */
    private const val STEP_MS = 250L
    /** ライブの受け口が 2 回目から返事を待たせる長さ (ミリ秒)。denpa は 20 秒待つが、テストを待たせないよう短く */
    private const val HOLD_MS = 2_000L
    const val TEXT = "■■■■■■"

    /** スクリーンショットのコメント (作り物)。`mail` と本文 */
    private val SHOWCASE = listOf(
        "184" to "わこつ",
        "184" to "きたー",
        "184" to "作画いいな",
        "184 yellow" to "鍛冶師かっこいい",
        "184" to "草",
        "184" to "この剣ほしい",
        "184 big" to "8888888",
        "184" to "OP いい曲",
        "184" to "勇者ざまぁ",
        "184 cyan" to "村の人やさしい",
        "184" to "ロイドさん有能",
        "184" to "王都編たのしみ",
        "184 ue" to "神回",
        "184" to "もう一話見たい",
    )

    /**
     * `GET api/jikkyo/kakolog?channel=&from=&to=` の返事。`showcase` (スクリーンショット) なら作り物の普通のコメントを、
     * ところどころ色・大きさを替えて返す
     */
    fun kakolog(query: String, showcase: Boolean): String {
        val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val from = params["from"]?.toLongOrNull() ?: 0
        val to = params["to"]?.toLongOrNull() ?: 0
        val comments = generateSequence(from - Math.floorMod(from, STEP_MS)) { it + STEP_MS }.takeWhile { it < to }.filter { it >= from }
            .joinToString(",") { ms ->
                val (mail, text) = if (showcase) SHOWCASE[Math.floorMod(ms / STEP_MS, SHOWCASE.size.toLong()).toInt()] else "184 green big" to TEXT
                """{"at":$ms,"text":"$text","mail":"$mail","src":"nx"}"""
            }
        return """{"comments":[$comments],"complete":true}"""
    }

    /** `POST api/jikkyo/feed` の返事。続き (`after`) を訊かれたら少し待たせる。`official` を載せてきたら、連携していない世帯の答えを添える */
    fun feed(body: String): String {
        if ("\"after\"" in body) Thread.sleep(HOLD_MS)
        val now = System.currentTimeMillis()
        val official = if ("\"official\"" in body) {
            ""","official":{"epoch":"fake","state":"unlinked","since":null,"now":$now,"seq":0,"comments":[]}"""
        } else {
            ""
        }
        return """{"epoch":"fake","seq":0,"hub":"up","nx":"up","comments":[]$official}"""
    }
}
