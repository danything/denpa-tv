package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.Recording
import kotlinx.coroutines.delay

/** 横のメニューの行き先 */
private enum class Destination(val label: String, val icon: Int) {
    // いちばん使う録画を上に (開いたときもここ)
    Recordings("録画", R.drawable.ic_recordings),
    Live("ライブ", R.drawable.ic_live),
    Settings("設定", R.drawable.ic_settings),
}

/**
 * いちばん上の画面。**左に Compose for TV のナビゲーション ドロワー** (畳むとアイコンだけの帯、
 * 左キーで開く) を置き、録画・ライブ・設定へ行く。
 *
 * Android TV のデザインの指針は、行き先を 5〜6 までのドロワーにまとめるよう勧めている。
 * 局も録画も数が多いので、1つの画面に列で並べるより、それぞれの画面に分ける。
 * ライブは押すとすぐ全画面で映す (最後に観ていた局)。録画と設定はドロワーの右に出す
 */
@Composable
fun MainScreen(
    repo: Repository,
    onLive: () -> Unit,
    onWatch: (Recording, Boolean) -> Unit,
    onUnauthorized: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(Destination.Recordings) }
    val items = remember { Destination.entries.associateWith { FocusRequester() } }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val focusManager = LocalFocusManager.current
    /** 「ライブ」に合っているか (ライブから戻ったときに合わせ直す) */
    var liveFocused by remember { mutableStateOf(false) }

    /** 最後に左キーを押したとき (左キーで開いたのか、戻ってきて合いが仮にメニューへ落ちたのかを分ける) */
    var leftAt by remember { mutableLongStateOf(0L) }
    // 左キーで開いたら、いま出している行き先に合わせる (近いものに合うので、録画の2列目から開くとライブに合ってしまう)。
    // 観て戻ってきたときは、右の画面 (録画の一覧なら開いた録画) が合いを取るので、ここでは取らない。
    // 取るとメニューが開いたままになる
    LaunchedEffect(drawer.currentValue) {
        val byKey = System.nanoTime() - leftAt < 1_000_000_000L
        if (drawer.currentValue == DrawerValue.Open && byKey) runCatching { items.getValue(selected).requestFocus() }
    }
    /*
     * **ライブから戻ったら、左のメニューの「ライブ」に合わせる** (戻るでいちばん上のメニューへ)。右の画面 (録画の一覧) も
     * 戻ってきたときに合いを取りにいくので、そちらには取らせない (`takeFocus`)。合うまで何こまか試す —
     * 合わせ損ねると、どこにも合わずにリモコンが効かなくなる
     */
    var toMenu by remember { mutableStateOf(repo.menuOnReturn.also { repo.menuOnReturn = false }) }
    LaunchedEffect(Unit) {
        if (!toMenu) return@LaunchedEffect
        val live = items.getValue(Destination.Live)
        for (attempt in 0 until MENU_FOCUS_TRIES) {
            withFrameNanos { }
            if (runCatching { live.requestFocus() }.getOrDefault(false) && liveFocused) break
            delay(MENU_FOCUS_WAIT_MS)
        }
        // 合わせ終えたら (合わせ損ねても) 元どおり。このあと録画の一覧を開き直したときは、一覧がカードに合わせる
        toMenu = false
    }
    // メニューが開いているときの戻るは、右の画面へ戻す (右キーと同じ)。何もしないと戻るが効かないように見える
    BackHandler(enabled = drawer.currentValue == DrawerValue.Open) { focusManager.moveFocus(FocusDirection.Right) }

    NavigationDrawer(
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) leftAt = System.nanoTime()
            false
        },
        drawerState = drawer,
        drawerContent = { value ->
            val open = value == DrawerValue.Open
            Column(
                Modifier
                    .fillMaxHeight()
                    // 開いたら右の画面の上に重なるので、左から地の色で覆って字を読めるように
                    .then(if (open) Modifier.background(DRAWER_SCRIM) else Modifier)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Brand(open)
                Spacer(Modifier.weight(1f))
                Destination.entries.forEach { destination ->
                    NavigationDrawerItem(
                        selected = selected == destination,
                        onClick = {
                            if (destination == Destination.Live) onLive() else selected = destination
                        },
                        leadingContent = { Icon(painterResource(destination.icon), contentDescription = null) },
                        colors = drawerItemColors(),
                        border = NavigationDrawerItemDefaults.border(
                            focusedBorder = Focus.border(DRAWER_ITEM_SHAPE, 2f),
                            focusedSelectedBorder = Focus.border(DRAWER_ITEM_SHAPE, 2f),
                        ),
                        glow = NavigationDrawerItemDefaults.glow(focusedGlow = Focus.glow, focusedSelectedGlow = Focus.glow),
                        shape = NavigationDrawerItemDefaults.shape(DRAWER_ITEM_SHAPE),
                        modifier = Modifier
                            .focusRequester(items.getValue(destination))
                            .onFocusChanged { if (destination == Destination.Live) liveFocused = it.isFocused },
                    ) { Text(destination.label) }
                }
                Spacer(Modifier.weight(1f))
            }
        },
    ) {
        // 観て戻ったら右の画面が合いを取る (録画の一覧なら開いた録画、設定なら頭のボタン)
        when (selected) {
            Destination.Settings -> SettingsScreen(repo)
            else -> RecordingsScreen(repo, onWatch, onUnauthorized, takeFocus = !toMenu)
        }
    }
}

/** ライブから戻ったとき「ライブ」に合わせるのを試す回数と間 (ミリ秒)。画面の入れ替えが終わるまで */
private const val MENU_FOCUS_TRIES = 20
private const val MENU_FOCUS_WAIT_MS = 50L

/**
 * メニューの頭の印。**denpa の電波塔を azure で**、開いているときは名前も (アプリのアイコン・バナーと同じ)。
 * 合わせるものではない (左のメニューの行き先だけを上下で辿る)
 */
@Composable
private fun Brand(open: Boolean) {
    Row(
        Modifier.padding(start = 12.dp, top = 12.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_brand), contentDescription = "denpa", tint = Palette.AccentBright, modifier = Modifier.size(32.dp))
        if (open) Text("denpa", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
    }
}

/** 行き先の色。選んでいるものは azure で塗り、合わせたものは白 (札と同じ) */
@Composable
private fun drawerItemColors() = NavigationDrawerItemDefaults.colors(
    contentColor = Palette.TextMuted,
    inactiveContentColor = Palette.TextMuted,
    selectedContainerColor = Palette.Accent,
    selectedContentColor = Palette.OnAccent,
    focusedContainerColor = Color.White,
    focusedContentColor = Palette.Background,
    focusedSelectedContainerColor = Color.White,
    focusedSelectedContentColor = Palette.Accent,
)

private val DRAWER_ITEM_SHAPE = RoundedCornerShape(50)

/** 開いたメニューの下地。左は地の色、右へ行くほど透かす */
private val DRAWER_SCRIM = Brush.horizontalGradient(0f to Palette.Background, 0.75f to Palette.Background.copy(alpha = 0.92f), 1f to Palette.Background.copy(alpha = 0f))
