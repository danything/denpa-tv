package io.github.danything.denpatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
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
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import io.github.danything.denpatv.R
import io.github.danything.denpatv.data.Recording

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
    onWatch: (Recording) -> Unit,
    onUnauthorized: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(Destination.Recordings) }
    val items = remember { Destination.entries.associateWith { FocusRequester() } }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val focusManager = LocalFocusManager.current

    /** 最後に左キーを押したとき (左キーで開いたのか、戻ってきて合いが仮にメニューへ落ちたのかを分ける) */
    var leftAt by remember { mutableLongStateOf(0L) }
    // 左キーで開いたら、いま出している行き先に合わせる (近いものに合うので、録画の2列目から開くとライブに合ってしまう)。
    // 観て戻ってきたときは、右の画面 (録画の一覧なら開いた録画) が合いを取るので、ここでは取らない。
    // 取るとメニューが開いたままになる
    LaunchedEffect(drawer.currentValue) {
        val byKey = System.nanoTime() - leftAt < 1_000_000_000L
        if (drawer.currentValue == DrawerValue.Open && byKey) runCatching { items.getValue(selected).requestFocus() }
    }
    // メニューが開いているときの戻るは、右の画面へ戻す (右キーと同じ)。何もしないと戻るが効かないように見える
    BackHandler(enabled = drawer.currentValue == DrawerValue.Open) { focusManager.moveFocus(FocusDirection.Right) }

    NavigationDrawer(
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown) leftAt = System.nanoTime()
            false
        },
        drawerState = drawer,
        drawerContent = {
            Column(
                Modifier.fillMaxHeight().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Destination.entries.forEach { destination ->
                    NavigationDrawerItem(
                        selected = selected == destination,
                        onClick = {
                            if (destination == Destination.Live) onLive() else selected = destination
                        },
                        leadingContent = { Icon(painterResource(destination.icon), contentDescription = null) },
                        modifier = Modifier.focusRequester(items.getValue(destination)),
                    ) { Text(destination.label) }
                }
            }
        },
    ) {
        // 観て戻ったら右の画面が合いを取る (録画の一覧なら開いた録画、設定なら頭のボタン)
        when (selected) {
            Destination.Settings -> SettingsScreen(repo)
            else -> RecordingsScreen(repo, onWatch, onUnauthorized)
        }
    }
}
