package io.github.danything.denpatv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.tv.material3.MaterialTheme
import io.github.danything.denpatv.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * URL の絵。読み終えるまでは `placeholder` の色だけ。出す大きさちょうどに縮めて読む (`Images`)。
 * `opaque` は透けない絵 (ポスター)。RGB_565 で読んで、覚えておく量を半分にする
 */
@Composable
fun RemoteImage(
    url: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    token: String? = null,
    opaque: Boolean = false,
    placeholder: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    BoxWithConstraints(modifier.background(placeholder)) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val height = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val image by produceState(
            initialValue = url?.let { Images.cached(it, width, height, opaque)?.asImageBitmap() },
            url, width, height,
        ) {
            // URL・大きさが替わったら前の絵は捨てる (覚えている状態は替わっても残る)
            value = url?.let { Images.cached(it, width, height, opaque)?.asImageBitmap() }
            if (url != null && value == null) {
                value = withContext(Dispatchers.IO) { Images.load(url, width, height, token, opaque) }?.asImageBitmap()
            }
        }
        image?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize()) }
    }
}

/**
 * **ぼかした絵** (一覧の上の段・詳しくの後ろ)。ごく小さく読んでぼかしたもの (`Images.loadBlurred`) を引き伸ばして描く。
 * 引き伸ばしの間を滑らかに埋める (双線形) ので、それだけでぼけて見える。描くたびにぼかさないので軽く、Android の版で見た目が変わらない
 */
@Composable
fun BlurredImage(url: String, token: String?, modifier: Modifier = Modifier) {
    val image: ImageBitmap? by produceState(Images.cachedBlurred(url)?.asImageBitmap(), url) {
        value = Images.cachedBlurred(url)?.asImageBitmap()
            ?: withContext(Dispatchers.IO) { Images.loadBlurred(url, token) }?.asImageBitmap()
    }
    Box(modifier) {
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.matchParentSize())
        }
    }
}
