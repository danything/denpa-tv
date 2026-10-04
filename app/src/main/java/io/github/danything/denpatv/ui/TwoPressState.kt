package io.github.danything.denpatv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.danything.denpatv.data.TwoPress
import kotlinx.coroutines.delay

/** 2回押しの状態を画面に持つ (`TwoPress`)。「もう一度押すと…」は時間が経つと元に戻る */
class TwoPressState internal constructor(private val logic: TwoPress) {
    var armed by mutableStateOf(false)
        private set

    /** 押した。実行するなら true */
    fun press(): Boolean {
        val run = logic.press(System.currentTimeMillis())
        armed = !run
        return run
    }

    fun reset() {
        logic.reset()
        armed = false
    }
}

@Composable
fun rememberTwoPress(windowMs: Long = 4_000): TwoPressState {
    val state = remember { TwoPressState(TwoPress(windowMs)) }
    LaunchedEffect(state.armed) {
        if (!state.armed) return@LaunchedEffect
        delay(windowMs)
        state.reset()
    }
    return state
}

/** 消すボタンの文字 */
fun deleteLabel(armed: Boolean) = if (armed) "もう一度押すと削除" else "削除"
