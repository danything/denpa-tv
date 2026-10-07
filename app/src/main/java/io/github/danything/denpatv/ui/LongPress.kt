package io.github.danything.denpatv.ui

import android.view.KeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.github.danything.denpatv.data.CenterPress
import io.github.danything.denpatv.data.LongPressGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.danything.denpatv.data.UpKey
import io.github.danything.denpatv.data.UpToClose

/** 長押しで開いたものの外側に付ける。押し直すまで、長押しの続き (繰り返しと離し) を中に通さない (`LongPressGuard`) */
fun Modifier.ignoreHeldCenter(): Modifier = composed {
    val guard = remember { LongPressGuard() }
    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        !guard.deliver(native.action, native.keyCode, native.repeatCount)
    }
}

/**
 * 開いているもののいちばん上の列に付ける。**この列で押しはじめた上キーを離したら `onClose`** (`UpToClose`)。
 * 中の札が受けなかった上キーだけが上がってくる (`onKeyEvent`)。null なら何もしない
 */
fun Modifier.closeOnUp(onClose: (() -> Unit)?): Modifier = if (onClose == null) this else composed {
    val up = remember { UpToClose() }
    onKeyEvent { event ->
        val native = event.nativeKeyEvent
        when (up.key(native.action, native.keyCode, native.repeatCount, native.downTime)) {
            UpKey.Pass -> false
            UpKey.Hold -> true
            UpKey.Close -> { onClose(); true }
        }
    }
}

/**
 * 決定の短押しと長押しを、**押した・離したの時間でも分ける** (`CenterPress`)。録画の一覧のカードと、再生の画面の映像に付ける。
 *
 * TV の Material の `Card` の長押しは、押し続けたときのキーの繰り返し (長押しの印) でしか決まらない。繰り返しを送らない
 * リモコン・端末 (キーの繰り返しを切ってある、離すまで何も送らない BT のリモコン) では、長く押しても離したときに短押し
 * (再生) になっていた。決定のキーはここで受けてしまい (カードには渡さない)、繰り返しの印か、押したまま
 * `CenterPress.LONG_PRESS_MS` たったら `onLongClick`、その前に離したら `onClick`。
 * 合いが外れた・`enabled` が替わった (映像の上に帯やメニューが開いた・閉じた) ら押しを忘れる。`enabled` でない間は受けない
 */
fun Modifier.centerPresses(onClick: () -> Unit, onLongClick: () -> Unit, enabled: Boolean = true): Modifier = composed {
    val center = remember { CenterPress() }
    val scope = rememberCoroutineScope()
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val timer = remember { arrayOfNulls<Job>(1) }
    fun run(action: CenterPress.Action) = when (action) {
        CenterPress.Action.Short -> click()
        CenterPress.Action.Long -> longClick()
    }
    fun forget() {
        timer[0]?.cancel()
        center.reset()
    }
    LaunchedEffect(enabled) { forget() }
    onFocusChanged {
        if (!it.hasFocus) forget()
    }.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (!enabled || native.keyCode !in LongPressGuard.CENTER_KEYS) return@onPreviewKeyEvent false
        when (native.action) {
            KeyEvent.ACTION_DOWN -> {
                val before = center.press
                center.down(native.repeatCount, native.isLongPress)?.let(::run)
                if (center.press != before) {
                    val press = center.press
                    timer[0]?.cancel()
                    timer[0] = scope.launch {
                        delay(CenterPress.LONG_PRESS_MS)
                        center.held(press)?.let(::run)
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                timer[0]?.cancel()
                center.up()?.let(::run)
            }
        }
        true
    }
}
