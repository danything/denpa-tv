package io.doany.denpatv.data

/** denpa の版 (`v1.44.0` の数の3つ) */
data class DenpaVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<DenpaVersion> {
    override fun compareTo(other: DenpaVersion): Int =
        compareValuesBy(this, other, DenpaVersion::major, DenpaVersion::minor, DenpaVersion::patch)

    override fun toString() = "v$major.$minor.$patch"

    companion object {
        /**
         * `api/health` の `version` (リリースのタグ。`v1.44.0`。v の無いものも読む) を読む。
         * 手元・develop の `dev` やタグでないもの (手でビルドしたイメージ) は null
         */
        fun parse(tag: String): DenpaVersion? {
            val (major, minor, patch) = TAG.matchEntire(tag.trim())?.destructured ?: return null
            return DenpaVersion(major.toInt(), minor.toInt(), patch.toInt())
        }

        private val TAG = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?""")
    }
}

/**
 * このアプリが要る denpa のバージョン (docs/pairing.md の「要る denpa のバージョン」)。いちばん新しく頼るようになった denpa の動きに合わせる:
 * どの形式で流すかを denpa に選ばせる (`codec=auto`・`playback?playable=` の `auto`。denpa #2115) のが 2.29.0 から
 */
val MIN_DENPA = DenpaVersion(2, 29, 0)

/**
 * **番組表と予約が要る denpa のバージョン** (`GET api/programs`・`POST`/`DELETE api/programs/<id>/record` が 2.33.0 から)。
 * `MIN_DENPA` より新しいが、ほかの画面は古い denpa でも使えるので `MIN_DENPA` は上げず、番組表の画面だけがこれを見る
 */
val MIN_GUIDE = DenpaVersion(2, 33, 0)

/**
 * 番組表の画面の真ん中に出す1行。`version` が `MIN_GUIDE` より古ければ出す。**dev ビルドと読めないバージョン (null も) は確かめない** (null を返す。
 * 呼ぶ側は API を呼び、404 なら `guideTooOldMessage` を出す)
 */
fun guideTooOld(version: String?): String? {
    val running = version?.let { DenpaVersion.parse(it) } ?: return null
    return if (running < MIN_GUIDE) guideTooOldMessage(version) else null
}

/** 番組表に要る denpa のバージョンの1行。いまのバージョンが分かれば添える */
fun guideTooOldMessage(version: String?): String {
    val running = version?.let { DenpaVersion.parse(it)?.toString() ?: it.trim() }?.takeIf { it.isNotEmpty() }
    return "番組表には denpa $MIN_GUIDE 以上が必要です" + (running?.let { " (いまは $it)" } ?: "")
}

/**
 * 繋いだ denpa が古すぎれば、画面に出す1行。足りていれば・分からなければ (`dev` など) null。
 * `version` が無い (null) のは版を返さないとても古い denpa。
 * **古くても止めない** (あるエンドポイントは読み、無いものは出ないだけ)。言うだけ
 */
fun denpaTooOld(version: String?): String? {
    if (version == null) return "denpa $MIN_DENPA 以上が必要です"
    val running = DenpaVersion.parse(version) ?: return null
    return if (running < MIN_DENPA) "denpa $MIN_DENPA 以上が必要です (いまは $running)" else null
}
