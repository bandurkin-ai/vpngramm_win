package app.hy2setup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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

/** Инструменты: доступ к серверам (SSH и аппаратные ключи), произвольный VPS, служебные действия. Новый сервер создаётся на Главной. */
@Composable
fun ToolsScreen(vm: MainViewModel) {
    var ask by remember { mutableStateOf<String?>(null) }
    val version = rememberAppVersion()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.accessRefresh() }

    ScreenFrame(title = "Инструменты", running = vm.running) {
        Text("Доступ к серверам", style = MaterialTheme.typography.titleMedium)
        SshAccessCard(vm)
        DefaultKeysCard(vm)
        if (vm.hwKeySupported) HwKeysCard(vm)
        SectionCard("Защитить сервер", AppIcons.Shield, subtitle = "Обновления и вход только по SSH-ключу") {
            Hint("Для сервера из «Доступа по SSH»: обновления, автообновления, вход только по SSH-ключу, файрвол и fail2ban. Протоколы и ссылки не меняются.")
            MoreHint("Можно повторять: уже защищённый сервер получит файрвол и fail2ban.", "Файрвол: снаружи открыты только SSH и порты VPN — другие службы на сервере станут недоступны извне. fail2ban банит за перебор паролей SSH; SSH ужесточён (LoginGraceTime 30, MaxAuthTries 3, без X11). Если после включения файрвола войти не удастся, через 3 минуты он снимется сам.")
            FilledTonalButton(onClick = { ask = "secure" }, enabled = !vm.running && vm.host.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Защитить сервер ${vm.host.trim()}".trim())
            }
        }
        ErrorText(vm.errorMessage)
        if (vm.toolsLog.isNotEmpty() || (vm.running && !vm.vpsBusy && vm.selectedTab == app.hy2setup.TAB_TOOLS)) ResultPanel(vm.toolsLog, vm.running && !vm.vpsBusy, emptyList(), title = "Журнал")
        Text("Сервис", style = MaterialTheme.typography.titleMedium)
        VpsCard(vm)
        ToolGroup {
            ToolItem(AppIcons.Key, "Забыть пароли и токены", "Удалить с ${Device.GEN} сохранённые пароли, ключ SSH и токены", !vm.running) { ask = "secrets" }
            ToolItem(AppIcons.Shield, "Сбросить доверенные серверы", "Снова спрашивать отпечаток при подключении", !vm.running) { ask = "hosts" }
        }
        ToolGroup {
            ToolItem(AppIcons.Info, "О приложении", "vpngramm v$version · @aeromike", chevron = false) {}
        }
    }

    when (ask) {
        "secure" -> ConfirmDialog(
            "Защитить сервер ${vm.host.trim()}?",
            "На сервере обновятся пакеты и включатся автообновления, вход по паролю будет отключён — только после того, как вход по SSH-ключу проверен. " +
                "Нужен приватный SSH-ключ, уже добавленный на сервер.\nФайрвол: снаружи открыты только SSH и порты VPN — другие службы на сервере станут недоступны извне. fail2ban банит за перебор паролей SSH; SSH ужесточён (LoginGraceTime 30, MaxAuthTries 3, без X11).",
            "Защитить", onConfirm = { ask = null; vm.secureNow() }, onDismiss = { ask = null },
        )
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
