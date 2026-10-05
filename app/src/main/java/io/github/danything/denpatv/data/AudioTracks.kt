package io.github.danything.denpatv.data

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.Locale

/** 音声の1本。`named` は放送の名前 (denpa が書く「主音声ステレオ」「解説ステレオ」など) が付いているか */
data class AudioTrack(val label: String, val named: Boolean)

/**
 * 音声の名前。名前 (`label`) があればそれ、無ければ言語 (日本語など) と番号 (生の TS は名前が無い)
 */
fun audioTrack(index: Int, label: String?, language: String?): AudioTrack {
    if (!label.isNullOrBlank()) return AudioTrack(label, true)
    val lang = language?.takeIf { it.isNotBlank() && it != "und" }?.let(::languageName)
    return AudioTrack(listOfNotNull("音声 ${index + 1}", lang?.let { "($it)" }).joinToString(" "), false)
}

/**
 * 覚えている音声に合わせるなら、その番号。**名前が付いているものだけ**合わせる — 「音声 2」のような番号は
 * 局や番組で意味が違う (ライブで局を替えるたびに副音声になってしまう)。合わせなくてよければ null
 */
fun rememberedAudio(tracks: List<AudioTrack>, remembered: String?): Int? {
    if (remembered == null) return null
    return tracks.indexOfFirst { it.named && it.label == remembered }.takeIf { it >= 0 }
}

/** 言語の名前 (日本語など)。TS は3文字 (jpn) で来るので2文字に直してから引く。引けなければ null */
private fun languageName(tag: String): String? {
    val code = if (tag.length == 3) Locale.getISOLanguages().firstOrNull { Locale.Builder().setLanguage(it).build().isO3Language == tag } ?: tag else tag
    return Locale.Builder().setLanguage(code).build().getDisplayLanguage(Locale.JAPANESE).takeIf { it.isNotBlank() && it != code && it != tag }
}

/**
 * デュアルモノ (二か国語・解説) の**どちら側を出すか**。ブラウザの denpa の `AudioSide` と同じ。
 *
 * デュアルモノは音声1本の左に主音声・右に副音声が入っている。`Main` は左を、`Sub` は右を両耳へ配り直し、
 * `Both` はそのまま (左右から別の音が同時に鳴る。テレビの「主+副」)。`wire` は denpa の JSON での書き方
 */
enum class AudioSide(val wire: String, val fallbackLabel: String) {
    Main("main", "主音声"),
    Sub("sub", "副音声"),
    Both("both", "主+副"),
    ;

    companion object {
        /** denpa の書き方から。知らない・無いなら null */
        fun of(wire: String?): AudioSide? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * denpa が番組表から組み立てた選べる音声の1つ (ブラウザの `arib.ts` の `AudioTrack`)。`id` は `"0:main"` の形、
 * `stream` は何本目の音声か、`side` はどちら側か。局の `now.audios` と録画の `audios` で来る (docs/api.md)。
 * 古い denpa では空のまま — デュアルモノを見分けられないので、これまでどおり左右をそのまま出す
 */
@Serializable
data class DenpaAudio(
    val id: String = "",
    val stream: Int = 0,
    val side: String = "both",
    val label: String = "",
    val main: Boolean? = null,
)

/**
 * 何本目の音声がデュアルモノなら、主・副・主+副それぞれの名前 (denpa の名前。無ければ「主音声」など)。
 * デュアルモノでなければ null。**主か副を denpa が言っているときだけ**デュアルモノとみなす (`both` だけなら
 * ふつうの音声)
 */
fun dualMonoLabels(audios: List<DenpaAudio>, stream: Int): Map<AudioSide, String>? {
    val sides = audios.filter { it.stream == stream }.mapNotNull { audio -> AudioSide.of(audio.side)?.let { it to audio.label } }
    if (sides.none { it.first != AudioSide.Both }) return null
    return AudioSide.entries.associateWith { side -> sides.firstOrNull { it.first == side }?.second?.takeIf { it.isNotBlank() } ?: side.fallbackLabel }
}

/**
 * 札で選べる1つ。`group` は Media3 の音声の何本目か、`side` はデュアルモノのどちら側か (デュアルモノでなければ null)
 */
data class AudioChoice(val group: Int, val side: AudioSide?, val track: AudioTrack)

/**
 * 選べる音声を**平らに並べる** (ブラウザの `audioTracks` と同じ)。デュアルモノの1本からは主・副・主+副の3つが出る。
 * デュアルモノの名前は覚える名前に使わない (`named = false`。どちら側かは別に覚える。`AudioSide`)
 *
 * **denpa の `stream` (何本目の音声か) と Media3 の音声の並びは同じとみなす。** denpa 自身も `stream` を
 * ffmpeg の `-map 0:a:<stream>` (TS の PMT の並び) にそのまま渡して焼いているので、同じ前提に乗る。
 * Media3 の TS の読み手も PMT の音声を PID の順に並べる (放送では主音声が先の PID)
 */
fun audioChoices(groups: List<AudioTrack>, denpa: List<DenpaAudio>): List<AudioChoice> =
    groups.flatMapIndexed { group, track ->
        val dual = dualMonoLabels(denpa, group)
        if (dual == null) listOf(AudioChoice(group, null, track))
        else AudioSide.entries.map { side -> AudioChoice(group, side, AudioTrack(dual.getValue(side), false)) }
    }

/** いま選ばれているもの (並びの何番目か)。デュアルモノなら `side` の側。見つからなければ 0 */
fun selectedChoice(choices: List<AudioChoice>, group: Int, side: AudioSide): Int =
    choices.indexOfFirst { it.group == group && (it.side == null || it.side == side) }.coerceAtLeast(0)

/**
 * 2ch の配り直しの係数 (入力 × 出力の行優先。Media3 の `ChannelMixingMatrix` の並び)。
 * `Main` は左を両耳へ、`Sub` は右を両耳へ、`Both` はそのまま
 */
fun dualMonoCoefficients(side: AudioSide): FloatArray = when (side) {
    //                 L→L  L→R  R→L  R→R
    AudioSide.Main -> floatArrayOf(1f, 1f, 0f, 0f)
    AudioSide.Sub -> floatArrayOf(0f, 0f, 1f, 1f)
    AudioSide.Both -> floatArrayOf(1f, 0f, 0f, 1f)
}

/**
 * **焼いて流すライブ・追っかけで頼む音声** (`?audio=<id>`)。焼いたものには音声が1本しか入っていない (denpa が選んで焼く)
 * ので、選び直すには denpa に頼み直す。生の TS は全部の音声が入っているので使わない (`audioChoices` で選ぶ)。
 *
 * - 選べるものが1つ以下なら null (頼まない。denpa の既定のまま)
 * - この画面で選んだもの (`picked`) が並びにあれば、それ
 * - 無ければ denpa の既定 (放送の言う主音声、無ければ先頭)。それがデュアルモノなら、覚えている側 (`side`)
 *
 * 古い denpa は `audio` を読み捨てるので、頼んでも害は無い (主音声のまま)
 */
fun bakedAudio(audios: List<DenpaAudio>, picked: String?, side: AudioSide): DenpaAudio? {
    if (audios.size < 2) return null
    audios.firstOrNull { it.id == picked }?.let { return it }
    val default = audios.firstOrNull { it.main == true } ?: audios.first()
    if (dualMonoLabels(audios, default.stream) == null) return default
    return audios.firstOrNull { it.stream == default.stream && it.side == side.wire } ?: default
}

/** 焼いたものを頼む URL に足す `&audio=<id>` (頼まなければ空) */
fun audioQuery(audio: DenpaAudio?): String =
    audio?.id?.takeIf { it.isNotBlank() }?.let { "&audio=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
