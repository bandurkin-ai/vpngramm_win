package app.hy2setup

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

fun makeQr(text: String, size: Int = 720): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 2,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until size) {
        for (x in 0 until size) {
            img.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
        }
    }
    return img.toComposeImageBitmap()
}
