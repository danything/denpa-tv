package io.github.danything.denpatv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
    /** ライブから戻ったら、メニューの「ライブ」に合わせ直す (どこにも合っていないと最初の1押しが空振りする) */
    var returnToLive by rememberSaveable { mutableStateOf(false) }
    val items = remember { Destination.entries.associateWith { FocusRequester() } }
    val liveItem = items.getValue(Destination.Live)
    val drawer = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(Unit) {
        if (returnToLive) runCatching { liveItem.requestFocus() }
    }

    // 開いたら、いま出している行き先に合わせる (左キーで近いものに合うので、録画の2列目から開くとライブに合ってしまう)
    LaunchedEffect(drawer.currentValue) {
        if (drawer.currentValue == DrawerValue.Open && !returnToLive) runCatching { items.getValue(selected).requestFocus() }
    }

    NavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            Column(
                Modifier.fillMaxHeight().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterVertically),
            ) {
                Destination.entries.forEach { destination ->
                    NavigationDrawerItem(
                        selected = selected == destination,
                        onClick = {
                            if (destination == Destination.Live) {
                                returnToLive = true
                                onLive()
                            } else {
                                returnToLive = false
                                selected = destination
                            }
                        },
                        leadingContent = { Icon(painterResource(destination.icon), contentDescription = null) },
                        modifier = Modifier.focusRequester(items.getValue(destination)),
                    ) { Text(destination.label) }
                }
            }
        },
    ) {
        // 右の画面に入ったら、ライブに戻す印は外す (録画を観て戻ったときは、開いた録画に合わせる)
        Box(Modifier.onFocusChanged { if (it.hasFocus) returnToLive = false }) {
            when (selected) {
                Destination.Settings -> SettingsScreen(repo)
                else -> RecordingsScreen(repo, onWatch, onUnauthorized, takeFocus = !returnToLive)
            }
        }
    }
}
