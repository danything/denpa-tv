package io.github.danything.denpatv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.danything.denpatv.ui.DenpaTv

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DenpaApp
        setContent { DenpaTv(app) }
    }
}
