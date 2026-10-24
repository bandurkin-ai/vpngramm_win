package app.hy2setup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.hy2setup.MainViewModel
import app.hy2setup.platform.Device

/** Инструменты: контроль серверов и служебные действия. Новый сервер создаётся на Главной. */
@Composable
fun ToolsScreen(vm: MainViewModel) {
    var ask by remember { mutableStateOf<String?>(null) }
    val version = rememberAppVersion()

    ScreenFrame(title = "Инструменты", running = vm.running) {
        if (!vm.monitorSupported) {
            SectionCard("Контроль серверов", AppIcons.Shield, subtitle = "Есть в версии для Android", startExpanded = false) {
                Hint("Фоновая проверка серверов с уведомлениями работает в приложении для Android. Здесь проверить сервер можно кнопкой «Проверить» на Главной.")
            }
        } else SectionCard(
            "Контроль серверов", AppIcons.Shield,
            subtitle = if (vm.monitorHours == 0) "Выключен" else "Проверка раз в ${vm.monitorHours} ч",
        ) {
            Segmented(
                listOf(0 to "Выкл", 1 to "1 ч", 3 to "3 ч", 6 to "6 ч"), vm.monitorHours, { vm.setMonitor(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            MoreHint(
                "Телефон сам проверяет серверы Главной и сообщит, если сервер перестал отвечать.",
                "Проверка — как у клиента VLESS: соединение и TLS с сервером. После двух неудач подряд придёт уведомление с кнопкой " +
                    "«Заменить сервер». Если на телефоне включён VPN, тревога не поднимается: проверка могла идти через этот же сервер.",
            )
        }
        ToolGroup {
            ToolItem(AppIcons.Key, "Забыть пароли и токены", "Удалить с ${Device.GEN} сохранённые пароли, ключ SSH и токены", !vm.running) { ask = "secrets" }
            ToolItem(AppIcons.Shield, "Сбросить доверенные серверы", "Снова спрашивать отпечаток при подключении", !vm.running) { ask = "hosts" }
        }
        ToolGroup {
            ToolItem(AppIcons.Info, "О приложении", "vpngramm v$version · @aeromike", chevron = false) {}
        }
    }

    when (ask) {
        "secrets" -> ConfirmDialog(
            "Забыть пароли и токены?",
            "С ${Device.GEN} будут удалены сохранённые пароли, приватный ключ SSH, ключи OneProvider и токен name.com. " +
                "Серверы и профиль Главной не затрагиваются.",
            "Забыть", danger = true, onConfirm = { ask = null; vm.forgetSecrets() }, onDismiss = { ask = null },
        )
        "hosts" -> ConfirmDialog(
            "Сбросить доверенные серверы?",
            "При следующем подключении к каждому серверу приложение снова покажет его отпечаток и спросит, доверять ли ему.",
            "Сбросить", onConfirm = { ask = null; vm.forgetHosts() }, onDismiss = { ask = null },
        )
    }
}

@Composable
private fun ToolGroup(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) { content() }
}

@Composable
private fun ToolItem(
    icon: ImageVector, title: String, subtitle: String, enabled: Boolean = true, chevron: Boolean = true, onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = if (chevron) { { Icon(AppIcons.Chevron, null) } } else null,
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}
