package io.github.danything.denpatv.data

/** denpa の版 (`v1.44.0` の数の3つ) */
data class DenpaVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<DenpaVersion> {
    override fun compareTo(other: DenpaVersion): Int =
        compareValuesBy(this, other, DenpaVersion::major, DenpaVersion::minor, DenpaVersion::patch)

    override fun toString() = "v$major.$minor.$patch"

    companion object {
        /**
         * `api/health` の `version` (リリースのタグ。`v1.44.0`、昔は `1.23.1` のように v の無いものも) を読む。
         * 手元・develop の `dev` やタグでないもの (手で焼いたイメージ) は null
         */
        fun parse(tag: String): DenpaVersion? {
            val (major, minor, patch) = TAG.matchEntire(tag.trim())?.destructured ?: return null
            return DenpaVersion(major.toInt(), minor.toInt(), patch.toInt())
        }

        private val TAG = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?""")
    }
}

/**
 * このアプリが要る denpa の版 (README の「対応する denpa の版」)。いちばん新しく足された口に合わせる:
 * ライブの「録画」(`POST api/services/<id>/record`) と局の `now.reserved`・`now.recording`・`now.id` が 1.40.0 から
 */
val MIN_DENPA = DenpaVersion(1, 40, 0)

/**
 * 繋いだ denpa が古すぎれば、画面に出す1行。足りていれば・分からなければ (`dev` など) null。
 * `version` が無い (null) のは `api/health` が版を返す前 (1.8.0 より前) の denpa。
 * **古くても止めない** (読める口は読み、無い口は出ないだけ)。言うだけ
 */
fun denpaTooOld(version: String?): String? {
    if (version == null) return "denpa $MIN_DENPA 以上が要ります (いまは v1.8.0 より前)"
    val running = DenpaVersion.parse(version) ?: return null
    return if (running < MIN_DENPA) "denpa $MIN_DENPA 以上が要ります (いまは $running)" else null
}
