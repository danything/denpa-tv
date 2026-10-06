package io.github.danything.denpatv.data

/**
 * 字幕を、下に重ねたもの (操作の帯・ライブのメニュー・知らせ) の上へどれだけ持ち上げるか (px、0 以上)。
 * **テレビの決まり (YouTube・Netflix のテレビの版と同じ)**: 下の操作を出している間は字幕をその上へ逃がし、閉じたら戻す。
 *
 * - 字幕の下の端が、重ねたものの上の端 (`height - inset`) より `gap` 上に来るまで持ち上げる。もとから上にあれば動かさない
 *   (上に出ている字幕・話し手の名前などはそのまま)
 * - 画面の上へははみ出させない (字幕の上の端 `top` が画面の上の端に着くまで)。重ねたものが高すぎる (ライブのメニュー) ときは、
 *   上の端に寄せたところで止める
 *
 * @param height 字幕の層の高さ
 * @param inset 下に重ねたものの高さ (下の端から)。何も出ていなければ 0
 * @param top 字幕の上の端、`bottom` は下の端 (どちらも層の上からの px)
 */
fun captionLift(height: Float, inset: Float, top: Float, bottom: Float, gap: Float): Float {
    if (inset <= 0f || bottom <= top) return 0f
    val need = bottom + gap - (height - inset)
    return need.coerceAtMost(top).coerceAtLeast(0f)
}

/**
 * 字幕の絵 (ARGB の画素を行ごとに並べたもの) の、何か描いてある行の上の端と下の端 (行の番号。下の端は含まない)。
 * 何も描いていなければ null。**生の TS の字幕は画面まるごとの絵で届く** (`CaptionCue`) ので、字の在りかはこれで見る。
 *
 * `read(y, rows, into)` は y 行目から `rows` 行ぶんの画素を `into` に読む (Bitmap.getPixels)。何行かずつまとめて読み
 * (1行ずつだと、空の絵で 1080 回呼ぶ)、上と下から見て字に当たったら止める
 */
fun inkRows(width: Int, height: Int, band: Int = INK_BAND, read: (Int, Int, IntArray) -> Unit): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val buffer = IntArray(width * band)
    /** y から rows 行のうち、字のある最初 (`fromTop`) か最後の行。無ければ -1 */
    fun scan(y: Int, rows: Int, fromTop: Boolean): Int {
        read(y, rows, buffer)
        val order = if (fromTop) 0 until rows else rows - 1 downTo 0
        return order.firstOrNull { row -> (row * width until (row + 1) * width).any { buffer[it] ushr 24 != 0 } }?.let { y + it } ?: -1
    }
    var top = -1
    var y = 0
    while (top < 0 && y < height) {
        val rows = minOf(band, height - y)
        top = scan(y, rows, fromTop = true)
        y += rows
    }
    if (top < 0) return null
    var bottom = -1
    var end = height
    while (bottom < 0) {
        val rows = minOf(band, end - top)
        bottom = scan(end - rows, rows, fromTop = false)
        end -= rows
    }
    return top to bottom + 1
}

/** `inkRows` が一度に読む行の数 (1920 幅で 240KB) */
private const val INK_BAND = 32
