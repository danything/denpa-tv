package io.github.danything.denpatv.data

/**
 * ライブ・追っかけの画質の切り替えの様子。**選んだらすぐ「切り替え中」と出し、新しいストリームの最初のフレームが出てから「にしました」と言う。**
 * 新しいストリームが映るまでは前の映像が残っている (Media3 は替えるときに面を消さない) ので、黙っていると効いたのか分からない。
 *
 * - `shown` … いま映っている画質 (見出しに出すもの)
 * - `pending` … 選んで、まだ映っていない画質
 * - `awaiting` … `pending` のストリームをリクエストした (ここから先の最初のフレームが、新しい画質のフレーム)。リクエストする前のフレーム (前のストリームのシークのあとなど) では言わない
 */
data class CodecSwitch(
    val shown: LiveQuality,
    val pending: LiveQuality? = null,
    val awaiting: Boolean = false,
) {
    /** 見出しに出す画質。切り替え中ならそう言う */
    val label: String get() = pending?.let { "${it.label} に切り替え中" } ?: shown.label

    /** 選んだ。同じものを選び直しただけなら null (何もしない)。切り替え中に映っている画質を選び直したら、切り替えをやめる */
    fun choose(next: LiveQuality): CodecSwitch? = when (next) {
        pending ?: shown -> null
        shown -> CodecSwitch(shown)
        else -> copy(pending = next, awaiting = false)
    }

    /** ストリームをリクエストした。切り替え中の画質なら、次のフレームを待つ。切り替え中でなければ、それが映っている画質 */
    fun requested(quality: LiveQuality): CodecSwitch = when {
        pending == quality -> copy(awaiting = true)
        pending == null -> copy(shown = quality)
        else -> this
    }

    /** 最初のフレームが出た。切り替えが済んだら、済んだ画質を返す */
    fun pictured(): Pair<CodecSwitch, LiveQuality?> {
        val done = pending?.takeIf { awaiting } ?: return this to null
        return CodecSwitch(done) to done
    }

    /** 映せなかった。切り替え中なら、映せなかった画質を返す (画質は `shown` に戻す) */
    fun failed(): Pair<CodecSwitch, LiveQuality?> {
        val failed = pending ?: return this to null
        return CodecSwitch(shown) to failed
    }
}
