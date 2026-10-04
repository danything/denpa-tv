package io.github.danything.denpatv.data

/**
 * 録画の一覧 (新しい順、全部)。古いものから片付けられるよう、画面は一番下 (いちばん古い録画) から開くので、
 * 少しずつではなく一度に全部読む (数百件まで。ポスターは見えたぶんだけ読む)。
 *
 * - 消したものは手元から抜く (読み直さない。並びが変わると合わせ直す先がずれる)
 * - **読み直している最中に消したら、その答えから弾く** (消す前に作られた答えに混ざって戻りうる)
 */
class RecordingList(private val fetch: suspend () -> List<Recording>) {
    var items: List<Recording> = emptyList()
        private set
    /** 読み直しの答えに混ざりうる、消したもの (読み直したら空にする) */
    private val removed = mutableSetOf<Long>()

    /** 読み直す */
    suspend fun refresh() {
        val all = fetch()
        items = all.filterNot { it.id in removed }
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
