package app.hy2setup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hy2setup.MainViewModel
import app.hy2setup.OneProviderClient
import app.hy2setup.OpSshKey
import app.hy2setup.ServerProfile

/** Ключи OneProvider. */
@Composable
fun OneProviderFields(vm: MainViewModel) {
    AppField("Api-Key", vm.opApiKey, { vm.opApiKey = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
    AppField("Client-Key", vm.opClientKey, { vm.opClientKey = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
    MoreHint(
        "Ключи — в кабинете panel.op-net.com, раздел API.",
        "Нажмите «Click to Reveal». Ключи хранятся на телефоне в зашифрованном виде. Если в кабинете включён Whitelist, добавьте в него IP телефона.",
    )
}

/** Доступ к name.com: логин, токен и (по желанию) домен. */
@Composable
fun NameComFields(vm: MainViewModel, withZone: Boolean = true) {
    AppField("Логин name.com", vm.ncUser, { vm.ncUser = it }, keyboard = KeyboardType.Email, enabled = !vm.running)
    AppField("API-токен name.com", vm.ncToken, { vm.ncToken = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
    if (withZone) AppField("Домен, например example.com", vm.zone, { vm.zone = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
    MoreHint(
        "Токен: name.com → Account Settings → API Token Management.",
        "Логин, домен и токен запоминаются; токен хранится на телефоне в зашифрованном виде.",
    )
}

/** Карточка «Аккаунты»: всё, что нужно для работы с OneProvider и name.com. */
@Composable
fun AccountsCard(vm: MainViewModel, startExpanded: Boolean, withZone: Boolean = true) {
    val filled = vm.opApiKey.isNotBlank() && vm.opClientKey.isNotBlank() && vm.ncUser.isNotBlank() && vm.ncToken.isNotBlank()
    SectionCard(
        "Аккаунты", AppIcons.Key,
        subtitle = if (filled) "OneProvider и name.com подключены" else "Нужны ключи OneProvider и токен name.com",
        collapsible = true, startExpanded = startExpanded,
    ) {
        Text("OneProvider", style = MaterialTheme.typography.titleSmall)
        OneProviderFields(vm)
        Divider()
        Text("name.com", style = MaterialTheme.typography.titleSmall)
        NameComFields(vm, withZone)
    }
}

/** Адрес сервера с выпадающим списком сохранённых серверов. */
@Composable
fun ServerHostField(vm: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ServerProfile?>(null) }
    Box(Modifier.fillMaxWidth()) {
        AppField(
            "IP-адрес или домен сервера", vm.host, { vm.host = it }, keyboard = KeyboardType.Uri, enabled = !vm.running,
            supporting = if (vm.profiles.isEmpty()) "Сервер запомнится после первого подключения" else "Сохранённых серверов: ${vm.profiles.size}",
            trailing = if (vm.profiles.isEmpty()) null else {
                { IconButton(onClick = { expanded = true }, enabled = !vm.running) { Icon(AppIcons.ExpandMore, "Сохранённые серверы") } }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            vm.profiles.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.host)
                            Text("${p.user} · порт ${p.port} · " + if (p.useKey) "ключ" else "пароль", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { vm.selectProfile(p); expanded = false },
                    trailingIcon = {
                        IconButton(onClick = { toDelete = p; expanded = false }) { Icon(AppIcons.Delete, "Удалить ${p.host} из списка") }
                    },
                )
            }
        }
    }
    toDelete?.let { p ->
        ConfirmDialog(
            "Удалить сервер из списка?",
            "${p.user}@${p.host}:${p.port} и сохранённые для него пароль и ключ будут удалены с телефона. Сам сервер не затрагивается.",
            "Удалить", danger = true, onConfirm = { vm.deleteProfile(p); toDelete = null }, onDismiss = { toDelete = null },
        )
    }
}

/** Карточка доступа к серверу по SSH. */
@Composable
fun SshAccessCard(vm: MainViewModel) {
    val target = vm.host.trim()
    SectionCard(
        "Доступ по SSH", AppIcons.Terminal,
        subtitle = if (target.isEmpty()) "Сервер не указан" else "${vm.user.trim()}@$target · " + if (vm.useKey) "ключ" else "пароль",
        collapsible = true, startExpanded = target.isEmpty(),
    ) {
        ServerHostField(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppField("Порт", vm.sshPort, { vm.sshPort = it }, Modifier.weight(1f), KeyboardType.Number, enabled = !vm.running)
            AppField("Пользователь", vm.user, { vm.user = it }, Modifier.weight(2f), enabled = !vm.running)
        }
        Segmented(listOf(false to "Пароль", true to "SSH-ключ"), vm.useKey, { vm.useKey = it }, enabled = !vm.running)
        if (!vm.useKey) {
            AppField("Пароль", vm.password, { vm.password = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
        } else {
            AppField("Приватный ключ (вставьте текст)", vm.privateKey, { vm.privateKey = it }, lines = 3, mono = true, enabled = !vm.running)
            OutlinedButton(onClick = { app.hy2setup.FileDialogs.open("Файл приватного ключа")?.let { vm.loadKeyFrom(it) } }, enabled = !vm.running) { Text("Выбрать файл ключа…") }
            AppField("Пароль от ключа (если есть)", vm.passphrase, { vm.passphrase = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
            if (vm.user.trim() != "root") {
                AppField("Пароль для sudo", vm.password, { vm.password = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
            }
        }
        MoreHint(
            "Пароль и ключ хранятся на телефоне зашифрованными.",
            "Поддерживаются ключи ed25519, RSA, ECDSA (OpenSSH, PEM, PuTTY .ppk). Пользователю не root нужен sudo и его пароль.",
        )
    }
}

/** Выбор локации, тарифа (доступные к заказу, с IPv4, до $10/мес), ОС и SSH-ключей кабинета. */
@Composable
fun VpsOrderForm(vm: MainViewModel) {
    if (!vm.opCatalogLoaded) {
        OutlinedButton(onClick = vm::opLoadCatalog, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) {
            Text("Загрузить локации, тарифы и ключи")
        }
        Hint("Тарифы: только доступные к заказу, с IPv4 и не дороже $${OneProviderClient.MAX_MONTHLY_PRICE.toInt()}/мес.")
        return
    }
    if (vm.opLocationsOrderable.isEmpty()) ErrorText("Подходящих локаций нет.")
    // шаг 1: регион, шаг 2: город и страна внутри региона
    Picker(
        "Регион", vm.opRegion.ifEmpty { "Выберите…" },
        vm.opRegions.map { (r, n) -> r to "$r · " + if (n == 0) "все недоступны" else "локаций: $n" },
        !vm.running, vm::opSelectRegion,
    )
    val loc = vm.opSelectedLocation
    Picker(
        "Локация",
        loc?.let { it.place + (vm.opBadSince(it.id)?.let { t -> " · недоступна ($t)" } ?: "") } ?: "Выберите…",
        vm.opLocationsInRegion.map { l -> l.id to l.place + (vm.opBadSince(l.id)?.let { t -> " · недоступна (проверено $t)" } ?: "") },
        !vm.running, vm::opSelectLocation,
    )
    if (loc != null && vm.opIsBad(loc.id)) {
        ErrorText("В этой локации провайдер недавно ответил «нет мест». Лучше выбрать другую.")
    }
    Text("Тариф", style = MaterialTheme.typography.titleSmall)
    val sizes = vm.opSizesForLocation
    if (sizes.isEmpty()) Hint("В этой локации нет доступных тарифов.")
    sizes.forEach { sz ->
        ListItem(
            headlineContent = { Text(sz.summary) },
            supportingContent = {
                Text(
                    "$${sz.monthlyPrice}/мес · $${sz.hourlyPrice}/час" + (if (sz.speedMbps.isNotEmpty()) " · ${sz.speedMbps} Мбит/с" else ""),
                )
            },
            leadingContent = { RadioButton(vm.opSizeId == sz.id, { vm.opSizeId = sz.id }, enabled = !vm.running) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = !vm.running) { vm.opSizeId = sz.id },
        )
    }
    Picker("Операционная система", vm.opSelectedTemplate?.name ?: "Выберите…", vm.opTemplates.map { it.id to it.name }, !vm.running) {
        vm.opTemplateId = it
    }
    Text("SSH-ключи из кабинета", style = MaterialTheme.typography.titleSmall)
    if (vm.opKeys.isEmpty()) Hint("В кабинете нет SSH-ключей — вход будет по паролю root, который выдаст провайдер.")
    vm.opKeys.forEach { k -> KeyRow(k, k.uuid in vm.opKeySel, !vm.running) { vm.opToggleKey(k.uuid) } }
    OutlinedButton(onClick = vm::opLoadCatalog, enabled = !vm.running) { Text("Обновить список") }
}

@Composable
fun KeyRow(k: OpSshKey, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(k.name.ifEmpty { k.uuid }) },
        supportingContent = {
            Text(k.value.split(" ").take(2).joinToString(" "), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Checkbox(checked, { onToggle() }, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = enabled) { onToggle() },
    )
}
