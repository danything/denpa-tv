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
        if (savedInstanceState == null) {
            link.value = DeepLink.parse(intent?.dataString)
        }
        // 新しい版があるか (12 時間に1回まで。届かなくても黙っている)。プロセスごとに1回なので、プロセスが落ちて
        // 作り直したときも確かめる (許可の画面にいる間に閉じられて戻ったときに、続けて入れるため)
        app.updater.checkOnStart()
        setContent { DenpaTv(app, link) }
    }

    /** 許可の画面 (「不明なアプリのインストール」) から戻ったら、許可されていれば続けて入れる */
    override fun onResume() {
        super.onResume()
        (application as DenpaApp).updater.resume()
    }

    /** 開いている間に来たリンク (singleTask なので新しい Activity は作らない) */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.parse(intent.dataString)?.let { link.value = it }
    }
}
