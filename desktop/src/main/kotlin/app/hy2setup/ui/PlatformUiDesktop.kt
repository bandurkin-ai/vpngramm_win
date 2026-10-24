package app.hy2setup.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import app.hy2setup.platform.Doc
import app.hy2setup.platform.FileDoc
import app.hy2setup.qrPixels
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File

/* То, чем интерфейс на Windows отличается от Android: буфер обмена, «Поделиться», выбор файлов, цвета, шрифт, версия. */

/** Окно приложения — владелец диалогов выбора файла. */
object DesktopWindow {
    var frame: Frame? = null
}

/** Кладёт текст в буфер обмена Windows. */
@Composable
fun rememberClipboard(): (label: String, text: String) -> Unit = remember {
    { _, text -> Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

/** «Поделиться» на ПК: текст копируется, дальше его можно вставить в мессенджер или письмо. */
@Composable
fun rememberShare(): (text: String, title: String) -> Unit {
    val clip = rememberClipboard()
    val snack = LocalSnack.current
    return remember(clip, snack) {
        { text, _ ->
            clip("", text)
            snack("Скопировано — вставьте в мессенджер или письмо (Ctrl+V)")
        }
    }
}

/** Выбор файла для чтения (диалог Windows). */
@Composable
fun rememberOpenDoc(onPicked: (Doc) -> Unit): () -> Unit = {
    pickFile("Выберите файл", FileDialog.LOAD, null)?.let { onPicked(FileDoc(it)) }
}

/** Выбор места для сохранения файла (с предложенным именем). */
@Composable
fun rememberSaveDoc(onPicked: (Doc) -> Unit): (name: String) -> Unit = { name ->
    pickFile("Сохранить копию", FileDialog.SAVE, name)?.let { onPicked(FileDoc(it)) }
}

private fun pickFile(title: String, mode: Int, name: String?): File? {
    val d = FileDialog(DesktopWindow.frame, title, mode)
    if (name != null) d.file = name
    d.isVisible = true
    val file = d.file ?: return null
    return File(d.directory, file)
}

fun qrImage(text: String, size: Int = 720): ImageBitmap =
    BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, size, size, qrPixels(text, size), 0, size) }
        .toComposeImageBitmap()

/** Цвета из обоев есть только на Android 12+. */
@Composable
fun dynamicColors(dark: Boolean): ColorScheme? = null

/** Рукописный шрифт логотипа vpngramm (Grand Hotel, OFL). */
val LogoFont = FontFamily(Font("font/grand_hotel.ttf"))

@Composable
fun rememberAppVersion(): String = remember {
    DesktopWindow::class.java.getResourceAsStream("/vpngramm-version.txt")?.use { String(it.readBytes()).trim() } ?: "?"
}
