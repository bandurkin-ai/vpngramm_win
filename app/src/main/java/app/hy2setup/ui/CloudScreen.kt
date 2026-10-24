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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hy2setup.DnsRecord
import app.hy2setup.MainViewModel
import app.hy2setup.OpCreated
import app.hy2setup.OpVm

/** Облако: VPS в OneProvider и DNS-записи name.com. */
@Composable
fun CloudScreen(vm: MainViewModel) {
    ScreenFrame(
        title = "Облако",
        running = vm.running,
        actions = {
            IconButton(onClick = { if (vm.cloudDns) vm.loadDns() else vm.opLoadVms() }, enabled = !vm.running) {
                Icon(AppIcons.Refresh, "Обновить")
            }
        },
    ) {
        Segmented(listOf(false to "VPS · OneProvider", true to "DNS · name.com"), vm.cloudDns, { vm.cloudDns = it })
        if (vm.cloudDns) DnsPart(vm) else VpsPart(vm)
    }
    if (vm.cloudDns) DnsDialogs(vm) else VpsDialogs(vm)
}

// ---------------------------------------------------------------- VPS

@Composable
private fun VpsPart(vm: MainViewModel) {
    val keysSet = vm.opApiKey.isNotBlank() && vm.opClientKey.isNotBlank()
    SectionCard(
        "Ключи API", AppIcons.Key,
        subtitle = if (keysSet) "Указаны" else "Нужны для работы с OneProvider",
        collapsible = true, startExpanded = !keysSet,
    ) { OneProviderFields(vm) }

    ErrorText(vm.opError)
    vm.opCreated?.let { CreatedCard(vm, it) }

    SectionCard("Мои VPS", AppIcons.Dns, subtitle = if (vm.opLoaded) "Серверов: ${vm.opVms.size}" else null) {
        if (!vm.opLoaded) {
            FilledTonalButton(onClick = vm::opLoadVms, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Загрузить список VPS") }
        } else if (vm.opVms.isEmpty()) Hint("В аккаунте нет VPS.")
        vm.opVms.forEach { v -> VmRow(vm, v) }
        if (vm.opVms.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { vm.opSelected.clear(); vm.opSelected.addAll(vm.opVms.map { it.id }) },
                    enabled = !vm.running, modifier = Modifier.weight(1f),
                ) { Text("Выбрать все", maxLines = 1) }
                TextButton(onClick = { vm.opSelected.clear() }, enabled = !vm.running, modifier = Modifier.weight(1f)) {
                    Text("Снять выбор", maxLines = 1)
                }
            }
            if (vm.opSelected.isNotEmpty()) {
                DangerButton("Удалить выбранные (${vm.opSelected.size})", !vm.running) { vm.opConfirmDelete = true }
            }
        }
    }

    SectionCard("Новый VPS", AppIcons.Add, subtitle = "Купить VPS в OneProvider вручную", startExpanded = false) {
        VpsOrderForm(vm)
        if (vm.opCatalogLoaded) {
            AppField("Имя хоста (например, vpn1)", vm.opHostname, { vm.opHostname = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
            Button(onClick = vm::opRequestCreate, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Rocket, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Создать VPS")
            }
        }
    }

    SectionCard(
        "SSH-ключи кабинета", AppIcons.Key,
        subtitle = if (vm.opKeysLoaded) "Ключей: ${vm.opKeys.size}" else null,
        collapsible = true, startExpanded = false,
    ) {
        OutlinedButton(onClick = vm::opLoadKeys, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.opKeysLoaded) "Обновить список ключей" else "Загрузить SSH-ключи")
        }
        if (vm.opKeysLoaded && vm.opKeys.isEmpty()) Hint("В кабинете нет SSH-ключей.")
        vm.opKeys.forEach { k ->
            ListItem(
                headlineContent = { Text(k.name.ifEmpty { k.uuid }) },
                supportingContent = {
                    Text(k.value.split(" ").take(2).joinToString(" "), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    IconButton(onClick = { vm.opKeyToDelete = k }, enabled = !vm.running) {
                        Icon(AppIcons.Delete, "Удалить ключ", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Divider()
        Text("Добавить ключ", style = MaterialTheme.typography.titleSmall)
        AppField("Название", vm.opNewKeyName, { vm.opNewKeyName = it }, enabled = !vm.running)
        AppField("Публичный ключ (ssh-ed25519 AAAA…)", vm.opNewKeyValue, { vm.opNewKeyValue = it }, enabled = !vm.running, lines = 3, mono = true)
        TextButton(onClick = vm::opKeyFromServerTab, enabled = !vm.running) { Text("Взять из приватного ключа раздела «Сервер»") }
        FilledTonalButton(onClick = vm::opAddKey, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Добавить ключ в кабинет") }
        MoreHint(
            "Сюда вставляется только публичная часть (.pub).",
            "Новый ключ сразу отмечается для создаваемых VPS. Чтобы приложение входило по нему, его приватная часть должна быть " +
                "в «Доступе по SSH» раздела «Сервер».",
        )
    }

    ResultPanel(vm.opLog, vm.running, emptyList(), title = "Журнал OneProvider")
}

@Composable
private fun VmRow(vm: MainViewModel, v: OpVm) {
    val ram = v.memoryMb.toIntOrNull()?.let { if (it >= 1024 && it % 1024 == 0) "${it / 1024} ГБ" else "$it МБ" } ?: v.memoryMb
    ListItem(
        headlineContent = { Text(v.hostname.ifEmpty { "VPS ${v.id}" }) },
        overlineContent = { Text("${v.ip.ifEmpty { "IP ещё нет" }} · ${v.location}") },
        supportingContent = {
            Text(
                "${v.cores} vCPU · $ram · ${v.storageGb} ГБ · ${v.bandwidthGb} ГБ трафика" +
                    (if (v.cycleCost.isNotEmpty()) " · ${v.cycleCost}/мес" else "") +
                    (if (v.status.isNotEmpty()) " · ${v.status}" else ""),
            )
        },
        leadingContent = { Checkbox(v.id in vm.opSelected, { vm.opToggleVm(v.id) }, enabled = !vm.running) },
        trailingContent = {
            IconButton(onClick = { vm.useOnServerTab(v.ip) }, enabled = !vm.running && v.ip.isNotEmpty()) {
                Icon(AppIcons.Chevron, "Открыть в разделе «Сервер»")
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = !vm.running) { vm.opToggleVm(v.id) },
    )
}

@Composable
private fun CreatedCard(vm: MainViewModel, c: OpCreated) {
    var show by remember(c.id) { mutableStateOf(false) }
    val copy = rememberCopier()
    val s = LocalStatusColors.current
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = s.successContainer, contentColor = s.onSuccessContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Check, null); Spacer(Modifier.width(10.dp))
                    Text("VPS создан", style = MaterialTheme.typography.titleMedium)
                }
                Text("${c.hostname} · ID ${c.id}\nIP: ${c.ip.ifEmpty { "появится через минуту — обновите список" }}")
                if (c.password.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("root: " + if (show) c.password else "••••••••••", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        IconButton(onClick = { show = !show }) { Icon(if (show) AppIcons.EyeOff else AppIcons.Eye, if (show) "Скрыть" else "Показать") }
                        IconButton(onClick = { copy("Пароль root", c.password) }) { Icon(AppIcons.Copy, "Копировать пароль") }
                    }
                }
                Button(
                    onClick = { vm.useOnServerTab(c.ip, c.password, vm.opCreatedKeyed) },
                    enabled = c.ip.isNotEmpty() && !vm.running, modifier = Modifier.fillMaxWidth(),
                ) { Text("Установить протокол на этот VPS") }
                Hint("Откроется раздел «Сервер» с IP и паролем root. Подождите 1–3 минуты, пока VPS загрузится.", s.onSuccessContainer)
        }
    }
}

@Composable
private fun VpsDialogs(vm: MainViewModel) {
    if (vm.opConfirmCreate) {
        val sz = vm.opSelectedSize
        ConfirmDialog(
            "Создать VPS?",
            "Имя: ${vm.opHostname.trim()}\nЛокация: ${vm.opSelectedLocation?.title}\nТариф: ${sz?.summary}\n" +
                "ОС: ${vm.opSelectedTemplate?.name}\nSSH-ключей: ${vm.opKeySel.size}\n\n" +
                "Стоимость: ${sz?.monthlyPrice}/мес (${sz?.hourlyPrice}/час) спишется с баланса аккаунта OneProvider.",
            "Создать", onConfirm = vm::opCreate, onDismiss = { vm.opConfirmCreate = false },
        )
    }
    vm.opKeyToDelete?.let { k ->
        ConfirmDialog(
            "Удалить SSH-ключ?",
            "Ключ «${k.name.ifEmpty { k.uuid }}» будет удалён из кабинета OneProvider. На уже созданных VPS он останется, " +
                "но для новых VPS выбрать его будет нельзя.",
            "Удалить", danger = true, onConfirm = vm::opDeleteKey, onDismiss = { vm.opKeyToDelete = null },
        )
    }
    if (vm.opConfirmDelete) {
        val items = vm.opVms.filter { it.id in vm.opSelected }
        ConfirmDialog(
            "Удалить ${items.size} VPS?",
            "Серверы и все данные на них будут уничтожены безвозвратно:\n" + items.joinToString("\n") { "• ${it.hostname}  ${it.ip}" },
            "Удалить", danger = true, onConfirm = vm::opDeleteSelected, onDismiss = { vm.opConfirmDelete = false },
        )
    }
    if (vm.opOverage.isNotEmpty()) {
        ConfirmDialog(
            "Доплата за трафик",
            "Провайдер сообщает о перерасходе трафика. Если удалить сейчас, будет списана доплата:\n" +
                vm.opOverage.joinToString("\n") { (v, r) ->
                    "• ${v.hostname}: ${r.overageGb} ГБ сверх лимита, доплата ${r.overageCost}" +
                        if (r.hoursToWait.isNotEmpty()) ". Без доплаты — подождать ${r.hoursToWait} ч, не используя VPS." else ""
                },
            "Удалить с доплатой", danger = true, onConfirm = vm::opConfirmOverage, onDismiss = vm::opCancelOverage, dismiss = "Не удалять",
        )
    }
}

// ---------------------------------------------------------------- DNS

@Composable
private fun DnsPart(vm: MainViewModel) {
    SectionCard(
        "Аккаунт и домен", AppIcons.Key,
        subtitle = vm.zone.trim().ifEmpty { "Домен не указан" },
        collapsible = true, startExpanded = !vm.dnsLoaded,
    ) {
        NameComFields(vm)
        FilledTonalButton(onClick = vm::loadDns, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.dnsLoaded) "Обновить список" else "Загрузить записи")
        }
    }
    ErrorText(vm.dnsError)

    if (vm.dnsLoaded) {
        SectionCard("Записи ${vm.loadedZone}", AppIcons.Globe, subtitle = "Показано: ${vm.visibleRecords.size}") {
            SwitchRow("Только A/AAAA (адреса серверов)", vm.onlyAddress, { vm.onlyAddress = it }, enabled = !vm.running)
            val rows = vm.visibleRecords
            if (rows.isEmpty()) Hint("Записей нет.")
            rows.forEach { r -> RecordRow(r, r.id in vm.selected, vm.isProtected(r), !vm.running) { vm.toggle(r.id) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = vm::selectAllVisible, enabled = !vm.running, modifier = Modifier.weight(1f)) { Text("Выбрать все", maxLines = 1) }
                TextButton(onClick = vm::clearSelection, enabled = !vm.running, modifier = Modifier.weight(1f)) { Text("Снять выбор", maxLines = 1) }
            }
            if (vm.selected.isNotEmpty()) DangerButton("Удалить выбранные (${vm.selected.size})", !vm.running) { vm.confirmDelete = true }
            Hint("Корневые NS-записи защищены: без них домен перестанет работать.")
        }
    }

    AddRecordCard(vm)
    ResultPanel(vm.dnsLog, vm.running, emptyList(), title = "Журнал DNS")
}

@Composable
private fun RecordRow(r: DnsRecord, checked: Boolean, locked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(r.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("${r.answer} · TTL ${r.ttl}", maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        overlineContent = { Text(r.type + if (locked) " · системная" else "") },
        leadingContent = {
            if (locked) Icon(AppIcons.Lock, "Защищена", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Checkbox(checked, { onToggle() }, enabled = enabled)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = enabled && !locked) { onToggle() },
    )
}

@Composable
private fun AddRecordCard(vm: MainViewModel) {
    SectionCard("Добавить запись", AppIcons.Add, subtitle = "A, AAAA, CNAME, MX, TXT", startExpanded = false) {
        Segmented(listOf("A", "AAAA", "CNAME", "MX", "TXT").map { it to it }, vm.newType, { vm.newType = it }, enabled = !vm.running)
        AppField(
            "Имя (поддомен)", vm.newHost, { vm.newHost = it }, keyboard = KeyboardType.Uri, enabled = !vm.running,
            supporting = "Пусто или @ — сам домен",
        )
        val answerLabel = when (vm.newType) {
            "A" -> "IPv4-адрес"
            "AAAA" -> "IPv6-адрес"
            "CNAME" -> "Целевое имя (куда указывает)"
            "MX" -> "Почтовый сервер (имя хоста)"
            else -> "Текст записи"
        }
        AppField(
            answerLabel, vm.newAnswer, { vm.newAnswer = it },
            keyboard = if (vm.newType == "TXT") KeyboardType.Text else KeyboardType.Uri, enabled = !vm.running,
            trailing = if (vm.newType == "A") {
                { IconButton(onClick = vm::fillServerIp, enabled = !vm.running) { Icon(AppIcons.Dns, "Подставить IP сервера") } }
            } else null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppField("TTL, сек", vm.newTtl, { vm.newTtl = it }, Modifier.weight(1f), KeyboardType.Number, enabled = !vm.running)
            if (vm.newType == "MX") {
                AppField("Приоритет", vm.newPriority, { vm.newPriority = it }, Modifier.weight(1f), KeyboardType.Number, enabled = !vm.running)
            }
        }
        val fqdn = vm.newRecordFqdn
        if (fqdn.isNotEmpty() && vm.newAnswer.isNotBlank()) Hint("Будет создано: $fqdn  ${vm.newType}  ${vm.newAnswer.trim()}")
        Button(onClick = vm::addRecord, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) { Text("Добавить запись") }
    }
}

@Composable
private fun DnsDialogs(vm: MainViewModel) {
    if (vm.confirmDelete) {
        val items = vm.selectedRecords
        ConfirmDialog(
            "Удалить ${items.size} зап.?",
            "Будут безвозвратно удалены:\n" + items.joinToString("\n") { "• ${it.displayName}  ${it.type}  ${it.answer}" },
            "Удалить", danger = true,
            onConfirm = { vm.confirmDelete = false; vm.deleteSelected() }, onDismiss = { vm.confirmDelete = false },
        )
    }
}

// ---------------------------------------------------------------- общее

@Composable
fun DangerButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    ) {
        Icon(AppIcons.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(text)
    }
}
