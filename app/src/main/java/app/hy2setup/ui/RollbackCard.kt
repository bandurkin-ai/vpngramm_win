package app.hy2setup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hy2setup.MainViewModel
import app.hy2setup.MoveRollback

/**
 * Главная: «Переезд не завершён» — после ошибки или остановки «Заменить сервер». Откат возвращает A-запись и профиль
 * на прежний сервер (он при переезде не менялся); что делать с новым VPS — выбирает человек в окне подтверждения.
 */
@Composable
internal fun RollbackCard(vm: MainViewModel) {
    val rb = vm.plusRollback ?: return
    var ask by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = MaterialTheme.colorScheme.onErrorContainer
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Warning, null, tint = on)
                Spacer(Modifier.width(10.dp))
                Text("Переезд ${rb.fqdn} не завершён", style = MaterialTheme.typography.titleMedium, color = on)
            }
            rb.summary().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = on) }
            Text(
                "Прежний сервер ${rb.oldIp} не менялся — на него можно вернуться. Чтобы вместо этого доделать установку на новом сервере, " +
                    "откройте «Ссылки» → «Обновить сервер».",
                style = MaterialTheme.typography.bodySmall, color = on,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { ask = true }, enabled = !vm.running) {
                    Icon(AppIcons.Restore, null); Spacer(Modifier.width(8.dp)); Text("Откатить переезд")
                }
                TextButton(onClick = vm::plusRollbackDismiss, enabled = !vm.running) { Text("Скрыть", color = on) }
            }
        }
    }
    if (ask) RollbackDialog(rb, onDismiss = { ask = false }) { deleteNew -> ask = false; vm.plusRollbackRun(deleteNew) }
}

/** Подтверждение отката: что будет сделано и что делать с новым VPS. */
@Composable
internal fun RollbackDialog(rb: MoveRollback, onDismiss: () -> Unit, onConfirm: (deleteNew: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Restore, null) },
        title = { Text("Откатить переезд ${rb.fqdn}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rb.dnsChanged) Text("• A-запись ${rb.fqdn} в name.com вернётся на ${rb.oldIp}.")
                else Text("• A-запись не менялась — DNS не трогается.")
                Text("• Сервер на Главной снова будет работать с прежним VPS ${rb.oldIp}; ссылки те же.")
                when {
                    rb.bought -> Text("• Новый VPS${rb.newIp.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()} куплен и оплачивается: его можно удалить " +
                        "или оставить в «Свободных серверах», чтобы переехать на него позже без покупки.")
                    rb.newIp.isNotEmpty() -> Text("• Свободный сервер ${rb.newIp} останется в списке свободных.")
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rb.bought) {
                    FilledTonalButton(
                        onClick = { onConfirm(true) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text("Откатить и удалить новый VPS") }
                    FilledTonalButton(onClick = { onConfirm(false) }) { Text("Откатить, VPS оставить") }
                } else {
                    FilledTonalButton(onClick = { onConfirm(false) }) { Text("Откатить") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
