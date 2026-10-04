package io.github.danything.denpatv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import io.github.danything.denpatv.data.DeepLink
import io.github.danything.denpatv.ui.DenpaTv

class MainActivity : ComponentActivity() {
    /** 外から来たリンク (`denpa://…`)。画面が開いたら null に戻す */
    private val link = mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DenpaApp
        // 作り直し (プロセスが落ちて戻ったなど) では、同じリンクをもう一度開かない
        if (savedInstanceState == null) link.value = DeepLink.parse(intent?.dataString)
        setContent { DenpaTv(app, link) }
    }

    /** 開いている間に来たリンク (singleTask なので新しい Activity は作らない) */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.parse(intent.dataString)?.let { link.value = it }
    }
}
