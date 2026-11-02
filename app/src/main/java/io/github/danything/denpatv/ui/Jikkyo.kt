package io.github.danything.denpatv.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import io.github.danything.denpatv.BuildConfig
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.AirClock
import io.github.danything.denpatv.data.JikkyoComment
import io.github.danything.denpatv.data.JikkyoEndpoints
import io.github.danything.denpatv.data.JikkyoFeed
import io.github.danything.denpatv.data.JikkyoLanes
import io.github.danything.denpatv.data.RecordingJikkyo
import io.github.danything.denpatv.data.Service
import io.github.danything.denpatv.data.Unauthorized
import io.github.danything.denpatv.data.jikkyoSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 実況の取得先。smoke の縮めた APK だけ、ビルドのときに偽物へ差し替えられる (app/build.gradle.kts) */
val jikkyoEndpoints = JikkyoEndpoints(BuildConfig.JIKKYO_NX, BuildConfig.JIKKYO_KAKOLOG)

/**
 * 過去ログから取るところ (録画・追っかけ)。`from` から `to()` まで。`every` を渡すと、その間隔で新しいところを取り足す (録画中の追っかけ)
 */
class JikkyoPast(val from: Long, val to: () -> Long, val every: Long? = null)

/**
 * 実況の、画面に出すぶん (`rememberJikkyo`)。流すのは `JikkyoLayer`、操作の列のボタンは `control`
 */
@Stable
class JikkyoState internal constructor(
    val feed: JikkyoFeed,
    /** ボタンを出すか (世帯の設定がオンで、denpa が新しい) */
    val supported: Boolean,
    /** この局 (録画) の実況のチャンネル。無ければ null */
    val channel: String?,
    /** 端末の設定 (流すか) */
    val on: Boolean,
    /** 読めない */
    val down: Boolean,
    /** いま映しているコマの放送時刻 */
    val air: AirClock,
    /** 追っかけ。新しいところ (過去ログにまだ無い 10 分以内) に来たら NX-Jikkyo で1回だけ遡る */
    val backfill: Boolean,
    private val toggle: () -> Unit,
    private val missing: () -> Unit,
) {
    /** 流している */
    val active: Boolean get() = supported && on && channel != null

    /**
     * 操作の列の「実況」(「字幕」の隣)。出さないなら null。実況の無い局は「実況 (この局にはありません)」、
     * 読めないときは「(いまは読めません)」を添える
     */
    fun control(): Control? {
        if (!supported) return null
        if (channel == null) return Control("実況 (この局にはありません)", icon = R.drawable.ic_jikkyo) { missing() }
        val label = (if (on) "実況 オン" else "実況 オフ") + if (on && down) " (いまは読めません)" else ""
        return Control(label, on = on, icon = R.drawable.ic_jikkyo) { toggle() }
    }
}

/** 世帯の設定で実況がオンか。オフなら局の一覧の `jikkyo` がどれも null (実況のある局が1つも無い) */
fun householdJikkyo(services: List<Service>): Boolean = services.any { it.jikkyo != null }

/**
 * **実況のコメントを流す支度** (局・録画ごと)。ボタンを押したら端末の設定を替える (`Settings.jikkyo`)。
 *
 * @param live NX-Jikkyo の WebSocket に繋ぐか (ライブ・追っかけ)
 * @param channel この局 (録画) の実況のチャンネル。無ければ null
 * @param household 世帯の設定で実況がオンか (`householdJikkyo`)。オフならボタンを出さない
 * @param past 過去ログから取るところ (録画・追っかけ)。ライブは null
 * @param air いま映しているコマの放送時刻
 */
@Composable
fun rememberJikkyo(
    repo: Repository,
    live: Boolean,
    channel: String?,
    household: Boolean,
    air: AirClock,
    onChange: (String) -> Unit,
    past: JikkyoPast? = null,
    backfill: Boolean = false,
    onUnauthorized: () -> Unit = {},
): JikkyoState {
    val on by repo.app.settings.jikkyo.collectAsState(initial = false)
    val supported = household && jikkyoSupported(repo.denpaVersion)
    val scope = rememberCoroutineScope()
    // 繋ぐ出来事と取り足しは1本のスレッドで回す (NxJikkyo)。画面を離れたら閉じてから止める
    val feed = remember {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val loop = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        JikkyoFeed(live, loop, jikkyoEndpoints) to loop
    }.let { (feed, loop) ->
        DisposableEffect(feed) { onDispose { feed.close { loop.cancel() } } }
        feed
    }
    val down by feed.down.collectAsState()
    val active = supported && on && channel != null
    val latestPast by rememberUpdatedState(past)
    LaunchedEffect(channel, active, past?.from) {
        feed.set(channel, active)
        if (!active) return@LaunchedEffect
        latestPast?.let { feed.kakolog(it.from, it.to, it.every) }
    }
    // NG ワードは流しはじめるたびに読む (denpa の設定で替えたものが、開き直さなくても次に効く)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val words = try {
            repo.api.jikkyoNg(repo.base)
        } catch (_: Unauthorized) {
            return@LaunchedEffect onUnauthorized()
        }
        feed.setNg(words)
    }
    return JikkyoState(
        feed = feed,
        supported = supported,
        channel = channel,
        on = on,
        down = down,
        air = air,
        backfill = backfill,
        toggle = {
            scope.launch { repo.app.settings.setJikkyo(!on) }
            onChange(if (on) "実況 オフ" else "実況 オン")
        },
        missing = { onChange("この局には実況がありません") },
    )
}

/**
 * 録画・追っかけの実況。チャンネルと元の TS の頭の放送時刻は `GET api/recordings/<id>` の `jikkyo` から読む。
 * `clocks` は読めた `jikkyo` から、放送時刻 W と過去ログから取るところを作る。
 * 世帯の設定は、録画の `jikkyo` があればオン、無ければ局の一覧で見る (一覧をまだ読んでいなければボタンを出さない)
 */
@Composable
fun rememberRecordingJikkyo(
    repo: Repository,
    id: Long,
    onChange: (String) -> Unit,
    onUnauthorized: () -> Unit,
    live: Boolean = false,
    clocks: (RecordingJikkyo) -> Pair<AirClock, JikkyoPast>,
): JikkyoState {
    var found by remember(id) { mutableStateOf<RecordingJikkyo?>(null) }
    LaunchedEffect(id) {
        found = try {
            repo.api.recordingState(repo.base, id)?.jikkyo
        } catch (_: Unauthorized) {
            return@LaunchedEffect onUnauthorized()
        } catch (_: Exception) {
            // 読めなければ流さない (観るのは止めない)
            null
        }
    }
    val made = found?.let(clocks)
    return rememberJikkyo(
        repo,
        live = live,
        channel = found?.channel,
        household = found != null || householdJikkyo(repo.services),
        air = made?.first ?: AirClock { null },
        onChange = onChange,
        past = made?.second,
        // 追っかけは、過去ログにまだ入っていない新しいところで NX-Jikkyo を1回だけ遡る
        backfill = live,
        onUnauthorized = onUnauthorized,
    )
}

/** 1件の流れているコメント。x は放送時刻 W から毎フレーム決める (止めれば止まり、速さに従う) */
private class Flowing(
    val id: Long,
    val text: String,
    /** 流し始めた (止めるものは置いた) 放送時刻 */
    val startW: Long,
    val duration: Long,
    val still: Boolean,
    /** 行の上の端 (px) */
    val top: Float,
    val width: Float,
    val height: Float,
    val fontSize: Float,
    val color: Int,
)

/**
 * **実況のコメントを映像の上に流す** (ライブ・追っかけ・録画で同じ)。決まりはブラウザの denpa の `JikkyoLayer.svelte` に揃える。
 *
 * - 重ねる順は 映像 < コメント < 字幕 < 操作バー (`PlayerFrame`)。操作バーの影の下を通す。字幕は操作バーの上へ持ち上げるが、コメントは持ち上げない
 * - 行・字の大きさは映像の枠 (この層の大きさ) で決める (`JikkyoLanes`)。字は端末の字 (`Typeface.DEFAULT`。絵文字は OS の字)
 * - **1件ごとに小さな部品を作り、字は作るときに Paint で縁 → 字を1回描く。** 動かすのは `graphicsLayer` の `translationX` だけで、
 *   x は放送時刻 W から毎フレーム決める (描き直さない。止めれば止まり、再生の速さに従う)
 * - 色・位置・大きさはコメントのコマンドどおり。`ue`・`shita` は上・下の行に 3 秒止める。透明度 0.5
 * - **アニメーションを切っている端末では流さず、上の行に 3 秒置く** (`shita` は下の行)
 * - 同時に流すのは 60 件まで (メモリの少ない端末は 15 件)。空いた行が無ければ捨てる
 */
@Composable
fun JikkyoLayer(state: JikkyoState) {
    if (!state.active) return
    val context = LocalContext.current
    val reduced = remember { animationsOff(context) }
    val most = remember { if (lowRam(context)) LOW_RAM_MOST else MOST }
    val items = remember { mutableStateListOf<Flowing>() }
    /** いまのフレームの W。読むのは部品の `graphicsLayer` だけ (毎フレーム組み直さない) */
    var w by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val lanes = remember { JikkyoLanes(1) }
    val waiting = remember { mutableListOf<JikkyoComment>() }
    val measure = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT } }
    var nextId by remember { mutableLongStateOf(0L) }
    val current by rememberUpdatedState(state)

    fun tick(now: Long) {
        val frame = size
        if (frame.width <= 0 || frame.height <= 0) return
        val height = frame.height.toFloat()
        val width = frame.width.toFloat()
        lanes.resize(JikkyoLanes.laneCount(height))
        val take = current.feed.take(now)
        if (current.backfill) current.feed.backfill(now)
        if (take.jumped) {
            items.clear()
            lanes.clear()
            waiting.clear()
        }
        // 流れ終わった・置き終えたものを外す
        items.removeAll { now >= it.startW + it.duration || now < it.startW - JikkyoLanes.SPAN_MS }
        // 止めるものは時刻が来てから出す
        val due = waiting.filter { it.at <= now }.toMutableList()
        waiting.removeAll { it.at <= now }
        for (comment in take.comments) {
            val still = reduced || comment.style?.place != null
            if (still && comment.at > now) waiting += comment else due += comment
        }
        val line = JikkyoLanes.lineHeight(height)
        val base = JikkyoLanes.fontSize(height)
        for (comment in due) {
            if (items.size >= most) break
            val style = comment.style
            val still = reduced || style?.place != null
            val text = comment.text.replace('\n', ' ')
            var px = base * JikkyoLanes.sizeScale(style?.size)
            measure.textSize = px
            var textWidth = measure.measureText(text) + outline(px)
            // 止めるものは枠に収める (流れるものは長くても流れきる)
            if (still && textWidth > width) {
                px *= width / textWidth
                measure.textSize = px
                textWidth = measure.measureText(text) + outline(px)
            }
            val flow = if (still) lanes.hold(now, fromBottom = style?.place == "shita") else lanes.flow(now, textWidth, width, px)
            flow ?: continue
            items += Flowing(
                id = nextId++,
                text = text,
                startW = now,
                duration = flow.duration,
                still = still,
                top = (flow.lane + 0.5f) * line,
                width = textWidth,
                height = line,
                fontSize = px,
                color = style?.color?.let { android.graphics.Color.parseColor(it) } ?: android.graphics.Color.WHITE,
            )
        }
    }

    LaunchedEffect(Unit) {
        var lastTick = 0L
        try {
            while (true) {
                withFrameNanos { frame ->
                    val now = current.air.now() ?: return@withFrameNanos
                    w = now
                    // 新しいものは 0.1 秒おきに取る (コメントは秒より細かく合わせる必要が無い)
                    if (frame - lastTick >= TICK_NS) {
                        lastTick = frame
                        tick(now)
                    }
                }
            }
        } finally {
            current.feed.pause()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it },
    ) {
        val frameWidth = size.width.toFloat()
        for (item in items) key(item.id) { Comment(item, frameWidth) { w } }
    }
}

/** 1件。字は作るときに1回だけ描き (縁 → 字)、動かすのは `translationX` だけ */
@Composable
private fun Comment(item: Flowing, frameWidth: Float, w: () -> Long) {
    val density = LocalDensity.current
    val stroke = remember(item.id) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = item.fontSize
            style = Paint.Style.STROKE
            strokeWidth = outline(item.fontSize)
            strokeJoin = Paint.Join.ROUND
            color = android.graphics.Color.BLACK
        }
    }
    val fill = remember(item.id) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = item.fontSize
            color = item.color
        }
    }
    val sizeModifier = with(density) { Modifier.requiredSize(item.width.toDp(), item.height.toDp()) }
    Box(
        Modifier
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .graphicsLayer {
                translationY = item.top
                translationX = if (item.still) {
                    (frameWidth - item.width) / 2
                } else {
                    frameWidth - (w() - item.startW) * frameWidth / JikkyoLanes.SPAN_MS
                }
                alpha = COMMENT_ALPHA
            }
            .then(sizeModifier)
            .drawBehind {
                val metrics = fill.fontMetrics
                val baseline = size.height / 2 - (metrics.ascent + metrics.descent) / 2
                val x = stroke.strokeWidth / 2
                drawIntoCanvas {
                    it.nativeCanvas.drawText(item.text, x, baseline, stroke)
                    it.nativeCanvas.drawText(item.text, x, baseline, fill)
                }
            },
    )
}

/** 縁の太さ (px)。明るい場面でも読めるように */
private fun outline(fontSize: Float): Float = maxOf(2f, fontSize / 12)

/** アニメーションを切っているか (ユーザー補助・開発者向けオプションの「アニメーションの再生時間」が 0) */
private fun animationsOff(context: Context): Boolean =
    AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** メモリの少ない端末か */
internal fun lowRam(context: Context): Boolean =
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

/** 同時に流す件数の上限 */
private const val MOST = 60

/** 同時に流す件数の上限 (メモリの少ない端末) */
private const val LOW_RAM_MOST = 15

/** コメントの透明度 (ブラウザの denpa と同じ) */
private const val COMMENT_ALPHA = 0.5f

/** 新しいものを取る間 (ナノ秒) */
private const val TICK_NS = 100_000_000L
