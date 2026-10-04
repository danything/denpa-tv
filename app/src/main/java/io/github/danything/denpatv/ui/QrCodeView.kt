package io.github.danything.denpatv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.nayuki.qrcodegen.QrCode

/**
 * QR コードを描く。符号化は Project Nayuki の QR Code generator (MIT、`io.nayuki.qrcodegen` に同梱。
 * docs/libraries.md)。白地に黒、周りに 4 マスの余白 (読み取りに要る静かな帯)
 */
@Composable
fun QrCodeView(text: String, size: Dp, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(modifier.size(size)) {
        val quiet = 4
        val cells = qr.size + quiet * 2
        val cell = this.size.minDimension / cells
        drawRect(Color.White)
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.getModule(x, y)) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
