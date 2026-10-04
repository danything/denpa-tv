package io.github.danything.denpatv.data

import kotlinx.coroutines.sync.Mutex

/**
 * 録画を少しずつ読む (新しい順、`page` 件ずつ)。
 *
 * - 続きの読み込みは**同時に1つだけ** — 一覧の終わり近くで合わせ直すたびに呼ばれるので、
 *   読んでいる最中の呼び出しは捨てる (同じ offset を二度頼まない)
 * - 重なった録画 (読む間に新しい録画が増えて、ずれたぶん) は落とす
 * - 消したものは手元から抜く (読み直すと、続きまで読んだぶんが先頭の1ページに切り詰められる)
 */
class RecordingPager(
    private val page: Int,
    private val fetch: suspend (limit: Int, offset: Int) -> List<Recording>,
) {
    var items: List<Recording> = emptyList()
        private set
    var hasMore = true
        private set
    private val loading = Mutex()

    /** 頭から読み直す */
    suspend fun refresh() {
        loading.lock()
        try {
            val first = fetch(page, 0)
            items = first
            hasMore = first.size == page
        } finally {
            loading.unlock()
        }
    }

    /** 続きを読む。読み込み中か、もう無ければ何もしない (false) */
    suspend fun loadMore(): Boolean {
        if (!hasMore || !loading.tryLock()) return false
        try {
            val next = fetch(page, items.size)
            items = (items + next).distinctBy { it.id }
            hasMore = next.size == page
            return true
        } finally {
            loading.unlock()
        }
    }

    fun remove(id: Long) {
        items = items.filterNot { it.id == id }
    }
}
