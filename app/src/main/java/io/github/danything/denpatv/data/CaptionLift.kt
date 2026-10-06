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
 * 字幕の絵 (RGBA を1行ずつ並べたもの) の、何か描いてある行の上の端と下の端 (行の番号。下の端は含まない)。
 * 何も描いていなければ null。**生の TS の字幕は画面まるごとの絵で届く** (`CaptionCue`) ので、字の在りかはこれで見る。
 * `row(y, into)` は y 行目の画素を `into` に読む (Bitmap.getPixels)
 */
fun inkRows(width: Int, height: Int, row: (Int, IntArray) -> Unit): Pair<Int, Int>? {
    val line = IntArray(width)
    fun inked(y: Int): Boolean {
        row(y, line)
        return line.any { it ushr 24 != 0 }
    }
    val top = (0 until height).firstOrNull(::inked) ?: return null
    val bottom = (height - 1 downTo top).first(::inked)
    return top to bottom + 1
}
