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
 * このアプリが要る denpa のバージョン (docs/pairing.md の「要る denpa のバージョン」)。いちばん新しく頼るようになった denpa の動きに合わせる:
 * どの形式で流すかを denpa に選ばせる (`codec=auto`・`playback?playable=` の `auto`。denpa #2115) のが 2.29.0 から
 */
val MIN_DENPA = DenpaVersion(2, 29, 0)

/**
 * 実況を流すのに要る denpa の版。元の TS のライブの字幕のストリームが時計 (`clock`) を送り、録画の観方が `ruler` を返し、
 * NG ワード (`api/jikkyo/ng`) を読めるのがこの版から。古ければ操作の列に「実況」を出さない (`jikkyoSupported`)
 */
val JIKKYO_DENPA = DenpaVersion(2, 26, 0)

/** 繋いだ denpa で実況を流せるか。版が分からない (`dev` など) なら流せるとみなす (`denpaTooOld` と同じ)。版を確かめる前 (null) は流さない */
fun jikkyoSupported(version: String?): Boolean {
    if (version == null) return false
    val running = DenpaVersion.parse(version) ?: return true
    return running >= JIKKYO_DENPA
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
