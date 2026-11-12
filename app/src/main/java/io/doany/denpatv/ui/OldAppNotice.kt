package io.doany.denpatv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.doany.denpatv.data.OldApp

/**
 * 古いアプリ (io.github.danything.denpatv) が残っているときの1行。押すと削除の確認画面を開く (data/OldApp.kt)。
 * 設定は初めて開いたときに受け取ってあるので、古いアプリは要らない。削除すれば出なくなる
 */
@Composable
fun OldAppNotice(oldApp: OldApp) {
    val installed by oldApp.installed.collectAsState()
    if (!installed) return
    DenpaButton(
        onClick = { oldApp.uninstall() },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(OLD_APP_LABEL, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

const val OLD_APP_LABEL = "古いアプリを削除"
