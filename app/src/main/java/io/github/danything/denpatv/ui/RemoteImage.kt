package io.github.danything.denpatv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.MaterialTheme
import io.github.danything.denpatv.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * URL の絵。読み終えるまでは `placeholder` の色だけ。出す大きさに縮めて読む (`Images`)。
 * `decode` を渡すと、出す大きさではなくその大きさに縮めて読む (ぼかして大きく敷く背景。大きく読んでも見分けが付かない)
 */
@Composable
fun RemoteImage(
    url: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    token: String? = null,
    decode: IntSize? = null,
    placeholder: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    BoxWithConstraints(modifier.background(placeholder)) {
        val density = LocalDensity.current
        val width = decode?.width ?: with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val height = decode?.height ?: with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val image by produceState<ImageBitmap?>(
            initialValue = url?.let { Images.cached(it, width, height)?.asImageBitmap() },
            url, width, height,
        ) {
            // URL・大きさが替わったら前の絵は捨てる (覚えている状態は替わっても残る)
            value = url?.let { Images.cached(it, width, height)?.asImageBitmap() }
            if (url != null && value == null) {
                value = withContext(Dispatchers.IO) { Images.load(url, width, height, token) }?.asImageBitmap()
            }
        }
        image?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize()) }
    }
}
