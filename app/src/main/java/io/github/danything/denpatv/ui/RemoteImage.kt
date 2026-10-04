package io.github.danything.denpatv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.tv.material3.MaterialTheme
import io.github.danything.denpatv.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** URL の絵。読み終えるまでは地の色だけ。出す大きさに縮めて読む (`Images`) */
@Composable
fun RemoteImage(url: String?, contentScale: ContentScale, modifier: Modifier = Modifier, token: String? = null) {
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val height = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val image by produceState<ImageBitmap?>(
            initialValue = url?.let { Images.cached(it, width, height)?.asImageBitmap() },
            url, width, height,
        ) {
            if (url != null && value == null) {
                value = withContext(Dispatchers.IO) { Images.load(url, width, height, token) }?.asImageBitmap()
            }
        }
        image?.let { Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize()) }
        if (image == null) Box(Modifier.matchParentSize())
    }
}
