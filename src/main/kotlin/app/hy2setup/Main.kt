package app.hy2setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import app.hy2setup.ui.AppRoot
import app.hy2setup.ui.Hy2Theme
import java.awt.SystemTray

fun main() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
        runCatching { CrashReport.save(thread.name, e) }
        previous?.uncaughtException(thread, e)
    }
    application {
        val vm = remember { MainViewModel().also { it.crashReport = CrashReport.take() } }
        val trayState = rememberTrayState()
        val trayOk = remember { SystemTray.isSupported() }
        var visible by remember { mutableStateOf(true) }
        val window = rememberWindowState(size = DpSize(520.dp, 860.dp))

        remember { Notifications.tray = trayState }
        LaunchedEffect(Unit) {
            vm.refreshMonitor()
            vm.scheduleMonitor()
        }
        // долгая работа — не даём Windows уснуть
        LaunchedEffect(Unit) { snapshotFlow { vm.running }.collect { KeepAwake.set(it) } }

        fun show() {
            visible = true
            window.isMinimized = false
            Notifications.takePending()?.let { vm.openFromNotification(it) }
            vm.refreshMonitor()
        }

        if (trayOk) {
            Tray(
                icon = AppIcon.painter,
                state = trayState,
                tooltip = "vpngramm",
                onAction = ::show,
                menu = {
                    Item("Открыть vpngramm", onClick = ::show)
                    Item("Выход", onClick = { vm.persist(); exitApplication() })
                },
            )
        }

        Window(
            onCloseRequest = {
                vm.persist()
                // при включённом контроле серверов остаёмся в трее, иначе закрываем приложение
                if (trayOk && vm.monitorHours > 0) visible = false else exitApplication()
            },
            visible = visible,
            state = window,
            title = "vpngramm $APP_VERSION",
            icon = AppIcon.painter,
        ) {
            Hy2Theme {
                // интерфейс рассчитан на узкий экран: на широком окне держим его по центру
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) { AppRoot(vm) }
                    }
                }
            }
        }
    }
}
