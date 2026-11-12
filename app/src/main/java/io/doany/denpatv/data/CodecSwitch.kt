package io.doany.denpatv.data

/**
 * ライブ・追っかけの画質の切り替えの様子。**選んだらすぐ「切り替え中」と出し、新しいストリームの最初のフレームが出てから「にしました」と言う。**
 * 新しいストリームが映るまでは前の映像が残っている (Media3 は替えるときに面を消さない) ので、黙っていると効いたのか分からない。
 * 画質は選んだもの (`LiveQuality`、null は「自動」) で持つ。自動で denpa が選んだ形式は、呼ぶ側が応答ヘッダから渡す (`label` の `playing`)
 *
 * - `shown` … いま映っている選択 (見出しに出すもの)
 * - `pending` … 選んで、まだ映っていない選択
 */
data class CodecSwitch(
    val shown: LiveQuality?,
    val pending: Pending? = null,
) {
    /**
     * 切り替え中の選択。`awaiting` は、`choice` のストリームをリクエストした (ここから先の最初のフレームが、新しい選択のフレーム)。
     * リクエストする前のフレーム (前のストリームのシークのあとなど) では言わない
     */
    data class Pending(val choice: LiveQuality?, val awaiting: Boolean = false) {
        val label: String get() = choiceLabel(choice, null)
    }

    /** 流そうとしている選択。切り替え中ならその選択 */
    val chosen: LiveQuality? get() = if (pending != null) pending.choice else shown

    /** 見出しに出す画質。切り替え中ならそう言う。自動なら、いま流れている形式 (`playing`) を添える */
    fun label(playing: LiveQuality?): String = pending?.let { labelThen(it.label, "に切り替え中") } ?: choiceLabel(shown, playing)

    /** 選んだ。同じものを選び直しただけなら null (何もしない)。切り替え中に映っている選択を選び直したら、切り替えをやめる */
    fun choose(next: LiveQuality?): CodecSwitch? = when {
        next == chosen -> null
        next == shown -> CodecSwitch(shown)
        else -> copy(pending = Pending(next))
    }

    /** ストリームをリクエストした。切り替え中の選択なら、次のフレームを待つ。切り替え中でなければ、それが映っている選択 */
    fun requested(choice: LiveQuality?): CodecSwitch = when {
        pending == null -> copy(shown = choice)
        pending.choice == choice -> copy(pending = pending.copy(awaiting = true))
        else -> this
    }

    /** 最初のフレームが出た。切り替えが済んだら、済んだ選択を返す */
    fun pictured(): Pair<CodecSwitch, Pending?> {
        val done = pending?.takeIf { it.awaiting } ?: return this to null
        return CodecSwitch(done.choice) to done
    }

    /** 映せなかった。切り替え中なら、映せなかった選択を返す (選択は `shown` に戻す) */
    fun failed(): Pair<CodecSwitch, Pending?> {
        val failed = pending ?: return this to null
        return CodecSwitch(shown) to failed
    }
}
