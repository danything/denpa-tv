package io.github.danything.denpatv.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.C
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import io.github.danything.denpatv.data.CaptionCue
import io.github.danything.denpatv.data.CaptionFeed
import io.github.danything.denpatv.data.CaptionFrame
import io.github.danything.denpatv.data.CueTimeline
import io.github.danything.denpatv.data.Pts
import io.github.danything.denpatv.data.Unauthorized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.URI
import kotlin.math.roundToInt

/**
 * **TS の読み手が 0 に寄せた幅を覚えておく** (生の TS の字幕を放送の PTS で突き合わせるため。`Pts.broadcast`)。
 *
 * Media3 の TsExtractor は、最初の PTS を 0 に寄せた時刻でサンプルを出す。寄せ幅は渡した `TimestampAdjuster` が持って
 * いるので、**読み手を作るところで自分の `TimestampAdjuster` を渡して控えておく**。中身は `DefaultExtractorsFactory` と
 * 同じ作り方の TsExtractor (ほかの形はそのまま)。映像を頼み直すたびに読み手も作り直されるので、最後に作ったものを見る
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class TsClock(private val inner: DefaultExtractorsFactory = DefaultExtractorsFactory()) : ExtractorsFactory {
    @Volatile
    private var adjuster: TimestampAdjuster? = null
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var transcoding = true

    /** いまの寄せ幅 (マイクロ秒)。まだ PTS を1つも読んでいなければ null */
    fun offsetUs(): Long? = adjuster?.timestampOffsetUs?.takeIf { it != C.TIME_UNSET }

    override fun createExtractors(): Array<Extractor> = swap(inner.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swap(inner.createExtractors(uri, responseHeaders))

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        inner.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    @Deprecated("Media3 で非推奨。呼ばれたら中へそのまま渡す")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        transcoding = textTrackTranscodingEnabled
        @Suppress("DEPRECATION")
        inner.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
        inner.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory {
        inner.setParseHagcMetadata(parseHagcMetadata)
        return this
    }

    private fun swap(extractors: Array<Extractor>): Array<Extractor> =
        extractors.map { if (it is TsExtractor) ts() else it }.toTypedArray()

    /** `DefaultExtractorsFactory` の TS と同じ作り。違うのは `TimestampAdjuster` を控えることだけ */
    private fun ts(): TsExtractor {
        val made = TimestampAdjuster(0)
        adjuster = made
        return TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            if (transcoding) 0 else TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            subtitleParserFactory,
            made,
            DefaultTsPayloadReaderFactory(0, emptyList()),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
    }
}

/** 字幕の口の canvas の大きさ (denpa の `CANVAS`)。映像の枠いっぱいに引き伸ばす */
private const val CANVAS_WIDTH = 1920f
private const val CANVAS_HEIGHT = 1080f

/** 解いた字幕の絵と置き場所 */
class CaptionPicture(val bitmap: ImageBitmap, val cue: CaptionCue)

/** 生の TS の字幕の、画面に出すぶん */
class RawCaptionState {
    /** 字幕の口が「選べる字幕がある」と言ったか。操作の列の「字幕」の札を出すかに使う */
    var available by mutableStateOf(false)
    /** いま重ねている絵 */
    var picture by mutableStateOf<CaptionPicture?>(null)
    internal val timeline = CueTimeline()
    internal var key: Any? = null
}

/**
 * **生の TS の字幕を受け取って、出す番の絵を選ぶ。** 描くのは `RawCaptionLayer`。
 *
 * - 字幕の入れ切りは焼いた映像の字幕と同じ設定 (端末ごと)。切ってある間も、選べる字幕があるかだけは訊く
 *   (札を出すため。分かったら切る)
 * - **映像が流れはじめてから頼む** — TS の読み手が最初の PTS を読むまで時計が無く、ライブは映像の口が
 *   開いていないと字幕の口が 404 を返す (denpa は字幕のためにチューナーを掴まない)
 * - `generation` が変わったら (局を替えた・頼み直した・シークした) 頼み直す。録画は頼むときの位置 (`fromMs`) から読ませる
 * - ライブは切れたら繋ぎ直す。録画は読み切ったら終わり
 *
 * @param path 字幕の口 (denpa の根からの相対、`?from=` は付けない)。null なら生ではないので何もしない
 * @param fromMs 録画で読ませる位置 (ミリ秒、頭から)。ライブは null
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberRawCaptions(
    repo: Repository,
    player: ExoPlayer,
    clock: TsClock,
    path: String?,
    generation: Any?,
    fromMs: (() -> Long)? = null,
    onUnauthorized: () -> Unit = {},
): RawCaptionState {
    val state = remember { RawCaptionState() }
    val enabled by repo.app.settings.subtitles.collectAsState(initial = true)

    LaunchedEffect(path, generation, enabled) {
        // 選べる字幕は局 (録画) ごと。頼み直し・シークでは消さない (札がちらつく)
        if (state.key != path) {
            state.key = path
            state.available = false
        }
        state.timeline.clear()
        state.picture = null
        if (path == null) return@LaunchedEffect
        // 切ってあって、選べる字幕があるかはもう分かっている
        if (!enabled && state.available) return@LaunchedEffect
        /** 続けて断られた回数。古い denpa (口が無い) や、映像が開かないままのときに頼み続けない */
        var refused = 0
        while (true) {
            // 映像が流れはじめる (頼み直した映像を TS の読み手が読みはじめる) まで待つ。
            // 局を替えた直後は前の読み手の時計が残っているので、流れているかも見る
            delay(WAIT_MS)
            while (clock.offsetUs() == null || !player.isPlaying) delay(WAIT_MS)
            val from = fromMs?.invoke()?.let { "?from=${it.coerceAtLeast(0) / 1000}" } ?: ""
            val url = repo.url(path + from)?.let { URI(it) } ?: return@LaunchedEffect
            state.timeline.clear()
            val finished = try {
                follow(url, repo.token, state, keepGoing = { enabled }).also { refused = 0 }
            } catch (_: Unauthorized) {
                return@LaunchedEffect onUnauthorized()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: CaptionFeed.Refused) {
                // 404 (映像の口がまだ開いていない・繋ぎ直しの間・古い denpa)。少し待って頼み直すが、続くならやめる
                if (++refused >= MAX_REFUSED) return@LaunchedEffect
                false
            } catch (_: Exception) {
                // 読みの途切れ。少し待って頼み直す
                refused = 0
                false
            }
            // 切ってある間に選べる字幕が分かった・録画を読み切った
            if (!enabled && state.available) return@LaunchedEffect
            if (finished && fromMs != null) return@LaunchedEffect
            delay(RETRY_MS)
        }
    }

    // 出す番の絵を選ぶ。**時計は再生位置を放送の PTS に戻したもの**
    LaunchedEffect(state, enabled) {
        if (!enabled) {
            state.picture = null
            return@LaunchedEffect
        }
        var shown: CaptionCue? = null
        while (true) {
            delay(TICK_MS)
            val offset = clock.offsetUs() ?: continue
            val cue = state.timeline.at(Pts.broadcast(player.currentPosition, offset))
            if (cue === shown) continue
            shown = cue
            state.picture = cue?.let { decode(it) }
        }
    }
    return state
}

/**
 * 字幕の口を読み続ける。きれいに閉じたら true。`keepGoing` が false を返す間 (字幕を切ってある) は、
 * 選べる字幕が分かったところでやめる
 */
private suspend fun follow(url: URI, token: String?, state: RawCaptionState, keepGoing: () -> Boolean): Boolean = coroutineScope {
    val connection = withContext(Dispatchers.IO) { CaptionFeed.connect(url, token) }
    // 読むのは止まったままになる (ブロックする) ので、取り消されたら繋がりごと切って抜けさせる
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            connection.disconnect()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            DataInputStream(connection.inputStream.buffered()).use { input ->
                var finished: Boolean? = null
                while (finished == null) {
                    ensureActive()
                    when (val frame = CaptionFeed.read(input)) {
                        null -> finished = true
                        is CaptionFrame.Cue -> state.timeline.add(frame.cue)
                        is CaptionFrame.Tracks -> {
                            state.available = frame.count > 0
                            if (!keepGoing()) finished = false
                        }
                        CaptionFrame.Other -> Unit
                    }
                }
                finished
            }
        }
    } finally {
        watcher.cancel()
        connection.disconnect()
    }
}

/** 絵を解く。PNG は RGBA で、1920x1080 まるごと */
private suspend fun decode(cue: CaptionCue): CaptionPicture? = withContext(Dispatchers.Default) {
    BitmapFactory.decodeByteArray(cue.png, 0, cue.png.size)?.let { CaptionPicture(it.asImageBitmap(), cue) }
}

/** 字幕の絵を映像の枠に重ねる。映像は枠いっぱいに伸ばして出している (`PlayerFrame`) ので、絵も枠いっぱいに伸ばす */
@Composable
fun RawCaptionLayer(state: RawCaptionState) {
    val picture = state.picture ?: return
    Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / CANVAS_WIDTH
        val sy = size.height / CANVAS_HEIGHT
        val cue = picture.cue
        drawImage(
            picture.bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(picture.bitmap.width, picture.bitmap.height),
            dstOffset = IntOffset((cue.x * sx).roundToInt(), (cue.y * sy).roundToInt()),
            dstSize = IntSize((cue.width * sx).roundToInt(), (cue.height * sy).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** 出す番を見直す間 (ミリ秒)。字幕の時刻の細かさ (1 コマ 33ms) くらい */
private const val TICK_MS = 33L

/** 映像が流れはじめるのを待つ刻み (ミリ秒) */
private const val WAIT_MS = 200L

/** 続けて断られたらやめる回数 (頼み直しの間と掛けて 15 秒ほど) */
private const val MAX_REFUSED = 5

/** 頼み直すまでの間 (ミリ秒) */
private const val RETRY_MS = 3_000L
