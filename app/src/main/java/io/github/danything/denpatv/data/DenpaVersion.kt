package io.github.danything.denpatv.data

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
 * このアプリが要る denpa の版 (docs/pairing.md の「要る denpa の版」)。いちばん新しく頼るようになった denpa の動きに合わせる:
 * 番組が替わって映像・音声・字幕の並び (PMT) が変わったら、ライブを閉じて作り直させる (denpa #795) のが 1.59.0 から
 */
val MIN_DENPA = DenpaVersion(1, 59, 0)

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
