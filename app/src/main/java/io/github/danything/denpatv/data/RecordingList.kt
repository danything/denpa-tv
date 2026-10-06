package io.github.danything.denpatv.data

/**
 * 録画の一覧 (新しい順、全部)。古いものから片付けられるよう、画面は一番下 (いちばん古い録画) から開くので、
 * 少しずつではなく一度に全部読む (数百件まで。ポスターは見えたぶんだけ読む)。
 *
 * - 消したものは手元から抜く (読み直さない。並びが変わると合わせ直す先がずれる)
 * - **読み直している最中に消したら、その答えから弾く** (消す前に作られた答えに混ざって戻りうる)
 * - **読み直しても変わっていない録画は、前のものをそのまま使う** (`reuse`)。denpa の知らせ (録画が増えた・焼き上がった) で
 *   読み直すたびに全件が新しくなると、画面は変わっていないカードまで比べ直す。何も変わっていなければ一覧ごと前のまま
 */
class RecordingList(private val fetch: suspend () -> List<Recording>) {
    var items: List<Recording> = emptyList()
        private set
    /** 読み直しの答えに混ざりうる、消したもの (読み直したら空にする) */
    private val removed = mutableSetOf<Long>()

    /** 読み直す */
    suspend fun refresh() {
        val all = fetch()
        items = reuse(items, all.filterNot { it.id in removed })
        removed.clear()
    }

    /** 消す。消したものの隣 (後ろ、無ければ前) の id を返す (合わせ直す先) */
    fun remove(id: Long): Long? {
        val index = items.indexOfFirst { it.id == id }
        val neighbor = (items.getOrNull(index + 1) ?: items.getOrNull(index - 1))?.id?.takeIf { index >= 0 }
        removed += id
        items = items.filterNot { it.id == id }
        return neighbor
    }
}

/** `fresh` のうち `old` と同じ中身の録画は `old` のものに替える。全部同じ (並びも) なら `old` をそのまま返す */
fun reuse(old: List<Recording>, fresh: List<Recording>): List<Recording> {
    if (old.isEmpty()) return fresh
    val before = old.associateBy { it.id }
    val merged = fresh.map { recording -> before[recording.id]?.takeIf { it == recording } ?: recording }
    return if (merged.size == old.size && merged.indices.all { merged[it] === old[it] }) old else merged
}
