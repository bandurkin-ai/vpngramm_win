package app.hy2setup.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.hy2setup.R
import app.hy2setup.platform.Doc
import app.hy2setup.platform.UriDoc
import app.hy2setup.qrPixels

/* То, чем интерфейс на Android отличается от Windows: буфер обмена, «Поделиться», выбор файлов, цвета, шрифт, версия. */

/** Кладёт текст в буфер обмена. */
@Composable
fun rememberClipboard(): (label: String, text: String) -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) {
        { label, text ->
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }
}

/** «Поделиться» — системное меню отправки. */
@Composable
fun rememberShare(): (text: String, title: String) -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) {
        { text, title ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            ctx.startActivity(Intent.createChooser(send, title))
        }
    }
}

/** Выбор файла для чтения. */
@Composable
fun rememberOpenDoc(onPicked: (Doc) -> Unit): () -> Unit {
    val app = LocalContext.current.applicationContext as Application
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(UriDoc(app, uri)) }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Выбор места для сохранения файла (с предложенным именем). */
@Composable
fun rememberSaveDoc(onPicked: (Doc) -> Unit): (name: String) -> Unit {
    val app = LocalContext.current.applicationContext as Application
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) onPicked(UriDoc(app, uri))
    }
    return { name -> launcher.launch(name) }
}

fun qrImage(text: String, size: Int = 720): ImageBitmap =
    Bitmap.createBitmap(qrPixels(text, size), size, size, Bitmap.Config.ARGB_8888).asImageBitmap()

/** Цвета из обоев (Material You, Android 12+) или null. */
@Composable
fun dynamicColors(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val ctx = LocalContext.current
    return if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
}

/** Рукописный шрифт логотипа vpngramm (Grand Hotel, OFL). */
val LogoFont = FontFamily(Font(R.font.grand_hotel))

@Composable
fun rememberAppVersion(): String {
    val ctx = LocalContext.current
    return remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
}

/** Выбор файла SSH-ключа: на Android — обычный выбор файла. */
@androidx.compose.runtime.Composable
fun rememberOpenKeyDoc(onPicked: (app.hy2setup.platform.Doc) -> Unit): () -> Unit = rememberOpenDoc(onPicked)
