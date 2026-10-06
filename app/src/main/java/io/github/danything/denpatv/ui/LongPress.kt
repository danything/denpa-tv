package io.github.danything.denpatv.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.github.danything.denpatv.data.LongPressGuard
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
