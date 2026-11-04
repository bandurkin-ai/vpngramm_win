package app.hy2setup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hy2setup.MainViewModel
import app.hy2setup.hwkey.HwKey

/**
 * «Инструменты → Доступ к серверам»: аппаратные ключи (Рутокен MFA, YubiKey) для входа по SSH.
 * Вход — только касанием, без PIN; ключи резидентные (в токене) и нерезидентные.
 */
@Composable
internal fun HwKeysCard(vm: MainViewModel, startExpanded: Boolean = false) {
    val key = vm.hwKey
    var ask by remember { mutableStateOf<String?>(null) }
    var toRemove by remember { mutableStateOf<HwKey?>(null) }
    SectionCard(
        "Аппаратные ключи", AppIcons.Key, startExpanded = startExpanded,
        subtitle = when {
            key == null -> "Ключей нет"
            vm.hwLogin -> "Вход по ключу включён · ${key.name.ifBlank { "токен" }}"
            vm.hwVerified -> "Ключ проверен на сервере"
            else -> "Ключей: ${vm.hwKeys.size} — добавьте на сервер и проверьте"
        },
    ) {
        Hint("Рутокен MFA (USB-C) или YubiKey 5C: закрытый ключ хранится только в токене, при входе нужно лишь коснуться его — без PIN.")
        if (vm.hwKeys.isNotEmpty()) {
            val copy = rememberCopier()
            Text("Ключ для входа", style = MaterialTheme.typography.titleSmall)
            vm.hwKeys.forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = k.fingerprint == key?.fingerprint, onClick = { vm.hwSelect(k.fingerprint) }, enabled = !vm.running)
                    Column(Modifier.weight(1f)) {
                        Text(k.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            k.authorizedKeysLine(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { copy("Ключ SSH", k.authorizedKeysLine()) }) { Text("Копировать") }
                    TextButton(onClick = { toRemove = k }, enabled = !vm.running) { Text("Удалить") }
                }
            }
        }
        Button(onClick = { ask = "create" }, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Добавить ключ с токена…") }
        OutlinedButton(onClick = vm::hwLoadResident, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) {
            Text("Загрузить резидентный ключ с токена")
        }
        if (key != null) {
            FilledTonalButton(onClick = vm::hwInstall, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) {
                Text("Добавить ключ на сервер ${vm.host.trim()}".trim())
            }
            if (vm.stableProfiles.any { it.vps != null }) {
                OutlinedButton(onClick = vm::hwInstallAll, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Добавить на все серверы Главной") }
            }
            OutlinedButton(onClick = vm::hwTest, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Проверить вход") }
            SwitchRow(
                "Входить по аппаратному ключу", vm.hwLogin, { vm.setHwLoginEnabled(it) }, enabled = vm.hwVerified && !vm.running,
                subtitle = if (vm.hwVerified) "«Доступ к серверам» и серверы Главной — касанием токена" else "Сначала проверьте вход по выбранному ключу",
            )
        }
        MoreHint(
            "Нерезидентный ключ хранится в приложении, токен лишь подписывает. Резидентный — в самом токене, его можно загрузить на новый телефон.",
            "Для резидентного ключа токен может один раз попросить PIN — при создании или загрузке; PIN не сохраняется, вход всё равно по касанию. " +
                "YubiKey 5.7 даёт ключ ed25519, Рутокен MFA — ecdsa (P-256). На сервере нужен OpenSSH 8.2 или новее (Debian 11+, Ubuntu 20.04+). " +
                "Каждое подключение — одно касание, поэтому «Проверить» на Главной попросит коснуться 2–3 раза. " +
                "Пароль и обычный SSH-ключ оставьте запасным входом, чтобы не потерять доступ, если токен потеряется.",
        )
    }
    when (ask) {
        "create" -> AlertDialog(
            onDismissRequest = { ask = null },
            icon = { Icon(AppIcons.Key, null) },
            title = { Text("Какой ключ создать?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Нерезидентный — проще: PIN не нужен, ключ хранится в приложении (и в копии серверов).")
                    Text("Резидентный — хранится в токене: его можно загрузить на другой телефон. Токен может один раз попросить PIN.")
                }
            },
            confirmButton = { TextButton(onClick = { ask = null; vm.hwCreate(true) }) { Text("Резидентный") } },
            dismissButton = { TextButton(onClick = { ask = null; vm.hwCreate(false) }) { Text("Нерезидентный") } },
        )
    }
    toRemove?.let { k ->
        ConfirmDialog(
            "Удалить ключ из приложения?",
            "${k.title}. Ключ перестанет использоваться для входа. На серверах строка ключа и в токене резидентный ключ остаются.",
            "Удалить", danger = true, onConfirm = { toRemove = null; vm.hwRemove(k.fingerprint) }, onDismiss = { toRemove = null },
        )
    }
}

/** Окно «Коснитесь токена», пока приложение ждёт токен. */
@Composable
internal fun HwKeyDialog(vm: MainViewModel) {
    if (vm.pinPrompt != null) return PinDialog(vm)
    val text = vm.hwPrompt ?: return
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(AppIcons.Key, null) },
        title = { Text("Аппаратный ключ") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(4.dp))
                Text(text)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = vm::hwCancel) { Text("Отмена") } },
    )
}

/** Ввод PIN токена — только для создания или загрузки резидентного ключа; PIN нигде не сохраняется. */
@Composable
private fun PinDialog(vm: MainViewModel) {
    val text = vm.pinPrompt ?: return
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(AppIcons.Key, null) },
        title = { Text("PIN токена") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                OutlinedTextField(
                    pin, { pin = it }, label = { Text("PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.answerPin(pin) }, enabled = pin.length >= 4) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = { vm.hwCancel() }) { Text("Отмена") } },
    )
}
