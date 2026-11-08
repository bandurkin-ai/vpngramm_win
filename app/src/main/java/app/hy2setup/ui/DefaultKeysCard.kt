package app.hy2setup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import app.hy2setup.MainViewModel
import app.hy2setup.PublicKeyLine

/**
 * «Инструменты → Ключи для всех серверов»: открытые ключи (например, YubiKey), которые приложение само ставит
 * на каждый создаваемый сервер; на существующие — кнопками.
 */
@Composable
internal fun DefaultKeysCard(vm: MainViewModel, startExpanded: Boolean = false) {
    val picker = rememberOpenDoc { vm.addDefaultKeysFrom(it) }
    var paste by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf<String?>(null) }
    var toRemove by remember { mutableStateOf<PublicKeyLine?>(null) }
    val keys = vm.defaultKeys
    SectionCard(
        "Ключи для всех серверов", AppIcons.Key, startExpanded = startExpanded,
        subtitle = when {
            keys.isEmpty() -> "Список пуст"
            !vm.defaultKeysOn -> "Ключей: ${keys.size} · на новые серверы не ставятся"
            else -> "Ключей: ${keys.size} · ставятся на новые серверы"
        },
    ) {
        Hint("Открытые ключи SSH (например, YubiKey), которые приложение добавляет в authorized_keys каждого сервера, который создаёт или переносит.")
        val copy = rememberCopier()
        keys.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k.title, style = MaterialTheme.typography.bodyMedium)
                    Text(k.kind, style = MaterialTheme.typography.bodySmall)
                    Text(k.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { copy("Ключ SSH", k.line) }) { Text("Копировать") }
                TextButton(onClick = { toRemove = k }, enabled = !vm.running) { Text("Удалить") }
            }
        }
        OutlinedButton(onClick = { picker() }, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Добавить из файла .pub…") }
        AppField("Или вставьте строку ключа", paste, { paste = it }, lines = 2, mono = true, enabled = !vm.running)
        if (paste.isNotBlank()) {
            TextButton(onClick = { if (vm.addDefaultKeys(paste)) paste = "" }, enabled = !vm.running) { Text("Добавить ключ") }
        }
        ErrorText(vm.defaultKeysError)
        SwitchRow(
            "Ставить на новые серверы", vm.defaultKeysOn, { vm.setDefaultKeysEnabled(it) }, enabled = !vm.running,
            subtitle = "При создании сервера и при «Заменить сервер» на Главной",
        )
        if (keys.isNotEmpty()) {
            FilledTonalButton(onClick = { ask = "one" }, enabled = !vm.running && vm.host.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Добавить на сервер ${vm.host.trim()}".trim())
            }
            if (vm.stableProfiles.any { !it.vps?.ip.isNullOrBlank() }) {
                OutlinedButton(onClick = { ask = "all" }, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Добавить на все серверы Главной") }
            }
        }
        MoreHint(
            "Ключи только добавляются: уже стоящие на сервере ключи и пароль не меняются.",
            "Подходят ключи ed25519, RSA, ECDSA и ключи аппаратных токенов (sk-ssh-ed25519, sk-ecdsa) — для последних на сервере нужен OpenSSH 8.2 или новее " +
                "(Debian 11+, Ubuntu 20.04+). Файл .pub может содержать несколько ключей — по одному на строку. " +
                "Список уходит в копию серверов («Сохранить копию» на Главной).",
        )
    }
    val names = keys.joinToString { it.title }
    when (ask) {
        "one" -> ConfirmDialog(
            "Добавить ключи на ${vm.host.trim()}?",
            "В authorized_keys сервера будут добавлены: $names.",
            "Добавить", onConfirm = { ask = null; vm.defaultKeysInstall() }, onDismiss = { ask = null },
        )
        "all" -> ConfirmDialog(
            "Добавить ключи на все серверы Главной?",
            "Серверы: ${vm.stableProfiles.filter { !it.vps?.ip.isNullOrBlank() }.joinToString { it.fqdn }}.\nКлючи: $names.",
            "Добавить", onConfirm = { ask = null; vm.defaultKeysInstallAll() }, onDismiss = { ask = null },
        )
    }
    toRemove?.let { k ->
        ConfirmDialog(
            "Убрать ключ из списка?",
            "${k.title} больше не будет ставиться на новые серверы. С серверов, где он уже стоит, он не удаляется.",
            "Убрать", danger = true, onConfirm = { toRemove = null; vm.removeDefaultKey(k.blob) }, onDismiss = { toRemove = null },
        )
    }
}
