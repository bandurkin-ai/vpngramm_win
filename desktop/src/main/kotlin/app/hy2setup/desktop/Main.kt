package app.hy2setup.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.hy2setup.MainViewModel
import app.hy2setup.platform.DesktopPlatform
import app.hy2setup.ui.AppRoot
import app.hy2setup.ui.DesktopWindow
import app.hy2setup.ui.Hy2Theme
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/** vpngramm для Windows: одно окно размером с телефон, внутри — тот же интерфейс, что и на Android. */
fun main() {
    val platform = DesktopPlatform()
    CrashLog.install(platform.dataDir)
    val crash = CrashLog.take(platform.dataDir)
    // модель создаётся в потоке интерфейса: её состояние читает и меняет Compose
    var vm: MainViewModel? = null
    SwingUtilities.invokeAndWait { vm = MainViewModel(platform).also { it.crashReport = crash } }
    val model = vm!!
    // выход из Windows или завершение процесса: введённое всё равно сохраняется
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { model.persist() } })

    application {
        val screen = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height }.getOrDefault(900)
        val state = rememberWindowState(size = DpSize(540.dp, minOf(900, screen - 40).coerceAtLeast(600).dp), position = WindowPosition.PlatformDefault)
        var askClose by remember { mutableStateOf(false) }
        val close = { model.persist(); exitApplication() }
        Window(
            onCloseRequest = { if (model.running) askClose = true else close() },
            state = state, title = "vpngramm", icon = painterResource("icon.png"),
        ) {
            remember(window) {
                DesktopWindow.frame = window
                window.minimumSize = java.awt.Dimension(380, 560)
                // как onStop на Android: введённое сохраняется, когда окно теряет фокус
                window.addWindowFocusListener(object : WindowAdapter() {
                    override fun windowLostFocus(e: WindowEvent?) = model.persist()
                })
            }
            Hy2Theme { AppRoot(model) }
            if (askClose) {
                AlertDialog(
                    onDismissRequest = { askClose = false },
                    title = { Text("Закрыть vpngramm?") },
                    text = { Text("Сейчас идёт работа с сервером. Если закрыть окно, она прервётся на середине — потом её можно будет запустить заново.") },
                    confirmButton = { TextButton(onClick = close) { Text("Закрыть") } },
                    dismissButton = { TextButton(onClick = { askClose = false }) { Text("Подождать") } },
                )
            }
        }
    }
}
