package io.github.danything.denpatv.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.danything.denpatv.data.DenpaApi
import io.github.danything.denpatv.data.GuideLookup
import io.github.danything.denpatv.data.GuidePage
import io.github.danything.denpatv.data.GuideProgram
import io.github.danything.denpatv.data.GuideService
import io.github.danything.denpatv.data.GuideWindows
import io.github.danything.denpatv.data.mergeGuide
import io.github.danything.denpatv.data.nextDay
import java.net.URI

/**
 * **手元に置く番組表。** 種別 × 放送日 (4:00 からの 24 時間) の窓ごとに取得し、種別ごとに 2 つまで、合わせて 4 つまで置く (`GuideWindows`)。
 * 画面をまたいで残す (番組表から観に行って戻ったとき、取得し直さずに出す)。どれもメインスレッドから呼ぶ
 */
class GuideStore(private val api: DenpaApi, private val base: URI) {
    private val windows = GuideWindows()
    private val pages = mutableStateMapOf<GuideWindows.Key, GuidePage>()
    /** 取得した順 (同じ種別の窓を1つの表にするとき、新しい窓を先に読む) */
    private val fetched = mutableMapOf<GuideWindows.Key, Long>()
    private var fetches = 0L
    private val loading = mutableSetOf<GuideWindows.Key>()

    /** 取得できなかった (届かない・denpa が答えない)。取得できているぶんは出したまま、上の段に「denpa に繋がっていません」 */
    var offline by mutableStateOf(false)
        private set

    /** denpa が古くて番組表のエンドポイントが無い (404) */
    var tooOld by mutableStateOf(false)
        private set

    /** 断られた (403) */
    var forbidden by mutableStateOf(false)
        private set

    /** 答えの形式が違った (denpa のバージョンのずれ) */
    var unreadable by mutableStateOf(false)
        private set

    /** 種別ごとの、denpa が番組表を持っている終わり (`until`)。分からなければ無い */
    private val untils = mutableStateMapOf<String, Long>()

    fun until(type: String): Long? = untils[type]

    /** その種別の、取得した窓をまとめた表 (局ごとに番組を始まりの順) */
    fun columns(type: String): List<GuideService> =
        mergeGuide(pages.entries.filter { it.key.type == type }.sortedBy { fetched[it.key] ?: 0L }.map { it.value })

    /** その種別の窓を1つでも取得してあるか */
    fun has(type: String): Boolean = pages.keys.any { it.type == type }

    fun loaded(type: String, day: Long): Boolean = GuideWindows.Key(type, day) in pages

    /** 置いてある窓 (その種別の) の放送日 */
    fun days(type: String): List<Long> = pages.keys.filter { it.type == type }.map { it.day }

    /**
     * 窓 (`type` の放送日 `day`) を取得する。取得してあれば何もしない (`force` なら取得し直す)。取得している最中なら待たない。
     * `keep` は捨てない窓 (いま見ている窓)。401 は `Unauthorized`
     */
    suspend fun load(type: String, day: Long, keep: Set<GuideWindows.Key>, force: Boolean = false) {
        val key = GuideWindows.Key(type, day)
        if (!force && key in pages) {
            windows.touch(key, keep + key).forEach(::forget)
            return
        }
        if (!loading.add(key)) return
        try {
            when (val result = api.guide(base, type, day, nextDay(day))) {
                is GuideLookup.Found -> {
                    pages[key] = result.page
                    fetched[key] = ++fetches
                    result.page.until?.let { untils[type] = it }
                    windows.touch(key, keep + key).forEach(::forget)
                    offline = false
                    tooOld = false
                    forbidden = false
                    unreadable = false
                }
                GuideLookup.TooOld -> tooOld = true
                GuideLookup.Forbidden -> forbidden = true
                GuideLookup.Offline -> offline = true
                GuideLookup.Unreadable -> unreadable = true
            }
        } finally {
            loading.remove(key)
        }
    }

    /**
     * その種別の置いてある窓と、いま見ている窓 (`want`。取得できていなかったものも) を取得し直す
     * (denpa の `reservations`・`programs` の通知と、繋ぎ直したとき)
     */
    suspend fun reload(type: String, want: List<Long>, keep: Set<GuideWindows.Key>) {
        (want + days(type)).distinct().forEach { load(type, it, keep, force = true) }
    }

    /** 番組表の画面を開いた。前に断られた・古かったのは忘れて、もう一度確かめる */
    fun opened() {
        tooOld = false
        forbidden = false
    }

    /** 番組 `id` を、手元の窓の全部で書き換える (予約を押した結果を、取得し直す前に出す) */
    fun update(id: Long, change: (GuideProgram) -> GuideProgram) {
        pages.entries.toList().forEach { (key, page) ->
            if (page.services.none { s -> s.programs.any { it.id == id } }) return@forEach
            pages[key] = page.copy(
                services = page.services.map { s -> if (s.programs.none { it.id == id }) s else s.copy(programs = s.programs.map { if (it.id == id) change(it) else it }) },
            )
        }
    }

    private fun forget(key: GuideWindows.Key) {
        pages.remove(key)
        fetched.remove(key)
        Log.i(TAG, "番組表の窓を捨てました: ${key.type} ${key.day}")
    }
}

private const val TAG = "DenpaGuide"
