package io.github.danything.denpatv.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.github.danything.denpatv.data.LongPressGuard

/** 長押しで開いたものの外側に付ける。押し直すまで、長押しの続き (繰り返しと離し) を中に通さない (`LongPressGuard`) */
fun Modifier.ignoreHeldCenter(): Modifier = composed {
    val guard = remember { LongPressGuard() }
    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        !guard.deliver(native.action, native.keyCode, native.repeatCount)
    }
}
