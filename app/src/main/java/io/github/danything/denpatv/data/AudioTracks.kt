package io.github.danything.denpatv.data

import java.util.Locale

/** 音声の1本。`named` は放送の名前 (denpa が書く「主音声ステレオ」「解説ステレオ」など) が付いているか */
data class AudioTrack(val label: String, val named: Boolean)

/**
 * 音声の名前。名前 (`label`) があればそれ、無ければ言語 (日本語など) と番号 (生の TS は名前が無い)
 */
fun audioTrack(index: Int, label: String?, language: String?): AudioTrack {
    if (!label.isNullOrBlank()) return AudioTrack(label, true)
    val lang = language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.JAPANESE).takeIf { name -> name.isNotBlank() } }
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
