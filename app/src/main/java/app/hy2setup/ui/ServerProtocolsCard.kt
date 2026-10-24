package app.hy2setup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.hy2setup.MainViewModel
import app.hy2setup.ServerMode
import app.hy2setup.StableProfile

/**
 * «Протоколы» на Главной: что стоит на сервере выбранного профиля. При разворачивании протоколы читаются с сервера
 * и отмечаются галочками; «Применить» ставит отмеченные (с постоянными ссылками, они попадают и в подписку)
 * и удаляет снятые.
 */
@Composable
internal fun ServerProtocolsCard(vm: MainViewModel, profile: StableProfile, startExpanded: Boolean = false) {
    val installed = vm.homeProtoInstalled
    val chosen = vm.homeProtoChosen
    val onProfile = listOfNotNull(
        "Hysteria2".takeIf { profile.hy2On }, "VLESS".takeIf { profile.vlessOn }, "XHTTP".takeIf { profile.xhttpActive },
        "AmneziaWG".takeIf { profile.awgOn }, "Telegram".takeIf { profile.tgOn },
    )
    SectionCard(
        "Протоколы", AppIcons.Bolt, startExpanded = startExpanded,
        subtitle = onProfile.joinToString(" · ").ifEmpty { "Нет протоколов" },
        onExpandChange = { open -> if (open && vm.homeProtoInstalled == null && !vm.running && profile.vps != null) vm.homeProtoRead() },
    ) {
        when {
            profile.vps == null -> Hint("У сервера ещё нет VPS — сначала создайте его.")
            installed == null -> {
                Hint(if (vm.running) "Читаю протоколы с сервера…" else "Протоколы читаются с сервера по SSH.")
                if (!vm.running) TextButton(onClick = vm::homeProtoRead) { Text("Прочитать с сервера") }
            }
            else -> {
                Hint("Галочка — протокол стоит на сервере. Отметьте или снимите и нажмите «Применить»: отмеченные установятся " +
                    "с постоянными ссылками (они попадут и в подписку Happ), снятые — удалятся с сервера.")
                Column {
                    ServerMode.entries.forEach { m -> ProtocolRow(vm, m, m in installed, m in chosen) }
                }
                if (vm.homeProtoAdd.isNotEmpty() || vm.homeProtoRemove.isNotEmpty()) {
                    Hint(
                        listOfNotNull(
                            ("Установить: " + names(vm.homeProtoAdd)).takeIf { vm.homeProtoAdd.isNotEmpty() },
                            ("Удалить: " + names(vm.homeProtoRemove)).takeIf { vm.homeProtoRemove.isNotEmpty() },
                        ).joinToString(". ") + ".",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ErrorText(vm.plusError)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::homeProtoRead, enabled = !vm.running) {
                        Icon(AppIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("С сервера")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = vm::homeProtoRequest,
                        enabled = !vm.running && (vm.homeProtoAdd.isNotEmpty() || vm.homeProtoRemove.isNotEmpty()),
                    ) { Text("Применить") }
                }
            }
        }
    }
    if (vm.homeProtoConfirm) {
        val add = vm.homeProtoAdd
        val remove = vm.homeProtoRemove
        ConfirmDialog(
            "Изменить протоколы ${profile.fqdn}?",
            listOfNotNull(
                ("Установить: ${names(add)}. Ключи создаются один раз и хранятся в профиле — ссылки постоянные, они появятся и в подписке Happ." +
                    (if (ServerMode.AWG in add) " AmneziaWG ставится 5–15 минут (на Debian 12 — с новым ядром и перезагрузкой)." else ""))
                    .takeIf { add.isNotEmpty() },
                ("Удалить: ${names(remove)}. Их ссылки перестанут работать у всех людей." +
                    (if (ServerMode.HY2 in remove) " Вместе с Hysteria2 перестанет открываться подписка Happ (её отдаёт Hysteria2)." else ""))
                    .takeIf { remove.isNotEmpty() },
                "Ссылки остальных протоколов не изменятся.",
            ).joinToString("\n\n"),
            "Применить", danger = remove.isNotEmpty(),
            onConfirm = vm::homeProtoApply, onDismiss = { vm.homeProtoConfirm = false },
        )
    }
}

@Composable
private fun ProtocolRow(vm: MainViewModel, m: ServerMode, now: Boolean, want: Boolean) {
    val state = when {
        now && want -> "установлен"
        want -> "будет установлен"
        now -> "будет удалён"
        else -> "не установлен"
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = !vm.running) { vm.homeProtoToggle(m) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = want, onCheckedChange = null, enabled = !vm.running, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(PROTOCOL_NAMES.getValue(m), style = MaterialTheme.typography.bodyLarge)
            Text(
                state, style = MaterialTheme.typography.bodySmall,
                color = when {
                    now != want && want -> MaterialTheme.colorScheme.primary
                    now != want -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun names(set: Set<ServerMode>) = ServerMode.entries.filter { it in set }.joinToString { PROTOCOL_NAMES.getValue(it) }
