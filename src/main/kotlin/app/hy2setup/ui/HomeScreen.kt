package app.hy2setup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hy2setup.LogSummary
import app.hy2setup.MainViewModel
import app.hy2setup.PlusStep
import app.hy2setup.StableProfile
import app.hy2setup.StepStatus
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import app.hy2setup.CombineServer
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Главная: постоянный профиль (ссылки не меняются при смене VPS) и всё для его обслуживания. */
@Composable
fun HomeScreen(vm: MainViewModel) {
    val profile = vm.plusProfile
    var passFor by remember { mutableStateOf<String?>(null) }
    var pass by remember { mutableStateOf("") }
    var pendingFile by remember { mutableStateOf<java.io.File?>(null) }
    var menu by remember { mutableStateOf(false) }
    var orderSheet by remember { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var linksSheet by remember { mutableStateOf(false) }
    var linksUser by remember { mutableStateOf<app.hy2setup.ProfileUser?>(null) }
    // из уведомления «сервер недоступен» — сразу шторка замены
    LaunchedEffect(vm.replaceRequest) { if (vm.replaceRequest) { vm.replaceRequest = false; vm.plusNew = false; orderSheet = true } }
    val haptic = LocalHapticFeedback.current

    ScreenFrame(
        title = "Главная",
        running = vm.running,
        actions = {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, "Ещё") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Сохранить копию") }, leadingIcon = { Icon(AppIcons.Save, null) },
                    onClick = {
                        menu = false
                        app.hy2setup.FileDialogs.save("vpngramm-backup.vpngramm", "Сохранить копию")?.let { pendingFile = it; pass = ""; passFor = "export" }
                    }, enabled = !vm.running,
                )
                DropdownMenuItem(
                    text = { Text("Восстановить из файла") }, leadingIcon = { Icon(AppIcons.Restore, null) },
                    onClick = { menu = false; app.hy2setup.FileDialogs.open("Восстановить из файла")?.let { vm.plusImport(it) } }, enabled = !vm.running,
                )
                if (profile != null && !profile.realitySelf) {
                    DropdownMenuItem(
                        text = { Text("Перевести VLESS на свой домен") }, leadingIcon = { Icon(AppIcons.Shield, null) },
                        onClick = { menu = false; vm.plusConfirmSelf = true }, enabled = !vm.running && profile.vps != null,
                    )
                }
                if (profile != null) {
                    DropdownMenuItem(
                        text = { Text("Удалить сервер", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; vm.serverToDelete = profile }, enabled = !vm.running,
                    )
                }
            }
        },
        floating = {
            if (vm.plusActive) {
                ExtendedFloatingActionButton(
                    onClick = vm::plusStop, icon = { Icon(AppIcons.Stop, null) }, text = { Text("Остановить") },
                    containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else if (profile != null) {
                // без серверов — мастер на экране, отдельная кнопка не нужна
                ExtendedFloatingActionButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.plusNew = false; orderSheet = true },
                    icon = { Icon(AppIcons.Rocket, null) },
                    text = { Text(if (profile == null) "Создать сервер" else "Заменить сервер") },
                    expanded = true,
                )
            }
        },
    ) {
        if (profile == null) {
            FirstRunWizard(vm, onAddExisting = { addSheet = true })
        } else {
            ServerSwitcher(vm, profile, onNew = { vm.plusNew = true; orderSheet = true }, onAdd = { addSheet = true })
            if (vm.backupStale) BackupBanner {
                app.hy2setup.FileDialogs.save("vpngramm-backup.vpngramm", "Сохранить копию")?.let { pendingFile = it; pass = ""; passFor = "export" }
            }
            ProfileHero(vm, profile, onLinks = { linksUser = null; linksSheet = true })
            PeopleCard(vm, profile, onLinks = { u -> linksUser = u; linksSheet = true })
        }

        if (vm.plusSteps.isNotEmpty()) {
            SectionCard("Ход выполнения", AppIcons.Bolt, collapsible = !vm.plusActive, startExpanded = vm.plusActive) {
                val target = vm.plusRunTarget
                Stepper(
                    PlusStep.entries.map {
                        val title = when {
                            target == null -> it.title
                            it == PlusStep.CREATE -> "Свободный сервер: ${target.title}"
                            it == PlusStep.BOOT -> "Доступ по SSH"
                            else -> it.title
                        }
                        title to (vm.plusSteps[it] ?: StepStatus.PENDING)
                    },
                )
                if (vm.plusActive) Hint(if (target == null) "Остановка срабатывает между шагами. Уже созданный VPS останется в OneProvider." else "Остановка срабатывает между шагами.")
            }
        }
        ErrorText(vm.plusError)
        ResultPanel(vm.plusLog, vm.plusActive, vm.plusLinks, title = "Журнал")
        if (vm.plusLinks.isNotEmpty()) {
            Hint("На мобильном интернете в России надёжнее VLESS (TCP): UDP, на котором работает Hysteria2, операторы часто режут.")
        }
        if (profile != null) AccountsCard(vm, startExpanded = false, withZone = false)
    }

    if (orderSheet) NewServerSheet(vm, if (vm.plusNew) null else profile) { orderSheet = false; vm.plusNew = false }
    if (addSheet) AddServerSheet(vm) { addSheet = false }
    if (linksSheet && profile != null) LinksSheet(vm, profile, linksUser) { linksSheet = false }
    LaunchedEffect(vm.plusActive) { if (vm.plusActive) orderSheet = false }

    HomeDialogs(vm, if (vm.plusNew) null else profile, passFor, pass, { pass = it }, onPassDone = { mode ->
        val file = pendingFile
        passFor = null
        if (file != null && mode == "export") vm.plusExport(file, pass)
    })
}

/** Выбор сервера с постоянными ссылками; всё ниже на Главной относится к выбранному. */
@Composable
internal fun ServerSwitcher(vm: MainViewModel, profile: StableProfile, onNew: () -> Unit, onAdd: () -> Unit, startOpen: Boolean = false) {
    var open by remember { mutableStateOf(startOpen) }
    val list = vm.stableProfiles
    Box {
        OutlinedCard(
            onClick = { open = true }, enabled = !vm.running, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Dns, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.fqdn, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (list.size > 1) "Сервер ${list.indexOf(profile) + 1} из ${list.size} · нажмите, чтобы выбрать другой"
                        else "Один сервер · нажмите, чтобы добавить ещё",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(AppIcons.ExpandMore, "Выбрать сервер")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            list.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(overallOk(p, vm.monitorState(p.fqdn)))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p.fqdn)
                                Text(
                                    listOfNotNull(
                                        p.vps?.ip ?: "VPS ещё не создан",
                                        p.movedAt.takeIf { it > 0 }?.let { "перенесён " + SimpleDateFormat("dd.MM", Locale("ru")).format(Date(it)) },
                                        maxOf(p.checkAt, vm.monitorState(p.fqdn)?.first ?: 0).takeIf { it > 0 }?.let { "проверен " + ago(it) },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingIcon = { if (p.fqdn == profile.fqdn) Icon(AppIcons.Check, null) else Spacer(Modifier.size(24.dp)) },
                    trailingIcon = {
                        IconButton(onClick = { open = false; vm.serverToDelete = p }) {
                            Icon(AppIcons.Delete, "Удалить ${p.fqdn}", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    onClick = { open = false; vm.selectStable(p.fqdn) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Создать новый сервер") }, leadingIcon = { Icon(AppIcons.Rocket, null) },
                onClick = { open = false; onNew() },
            )
            DropdownMenuItem(
                text = { Text("Добавить работающий сервер") }, leadingIcon = { Icon(AppIcons.Add, null) },
                onClick = { open = false; onAdd() },
            )
        }
    }
}

/** Шторка «Добавить работающий сервер»: его нынешние ссылки становятся постоянными. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddServerSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AddServerForm(vm, onDismiss)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun AddServerForm(vm: MainViewModel, onDone: () -> Unit) {
    Text("Добавить работающий сервер", style = MaterialTheme.typography.titleLarge)
    Hint(
        "Например, сервер, сделанный комбайном. Приложение по SSH прочитает его пароли и ключи (сервер не изменяется) — " +
            "и его можно будет переносить на другой VPS с теми же ссылками.",
    )
    AppField("Домен сервера (например, vpn2.example.org)", vm.importFqdn, { vm.importFqdn = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
    AppField("IP-адрес (если пусто — по домену)", vm.importHost, { vm.importHost = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
    Hint(
        "Вход: сохранённый пароль root этого сервера или приватный ключ из «Сервер → Доступ по SSH». " +
            "Если сервер ещё не открывали в приложении, впишите его адрес и пароль на вкладке «Сервер».",
    )
    ErrorText(vm.importError)
    Button(onClick = { vm.importServer(onDone) }, enabled = !vm.running, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Icon(AppIcons.Add, null); Spacer(Modifier.width(10.dp)); Text("Добавить")
    }
}

/** Карточка-герой: имя, текущий VPS, статус и быстрые действия. */
@Composable
private fun ProfileHero(vm: MainViewModel, profile: StableProfile, onLinks: () -> Unit) {
    val df = remember { SimpleDateFormat("dd.MM.yyyy", Locale("ru")) }
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Постоянные ссылки", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(profile.fqdn, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            ProtocolStatusRow(profile)
            Text(
                if (profile.checkAt > 0) "Проверено ${ago(profile.checkAt)}" else "Ещё не проверялся — нажмите «Проверить»",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            InfoLine(AppIcons.Dns, profile.vps?.let { "${it.ip} · ${it.hostname.ifEmpty { "VPS ${it.id}" }}" } ?: "VPS ещё не создан")
            InfoLine(AppIcons.Lock, profile.cert?.let { "Сертификат до ${df.format(Date(it.notAfter))}" } ?: "Сертификат ещё не сохранён")
            if (profile.vlessOn) InfoLine(AppIcons.Shield, if (profile.realitySelf) "VLESS маскируется под ваш домен" else "VLESS маскируется под ${profile.realitySni}")
            if (profile.hy2On) InfoLine(AppIcons.Tune, "Hysteria2 · порт ${profile.hy2Port}/UDP")
            profile.awg?.takeIf { profile.awgOn }?.let { InfoLine(AppIcons.Bolt, "AmneziaWG 3.1 · порт ${it.port}/UDP") }
            profile.tg?.takeIf { profile.tgOn }?.let { InfoLine(AppIcons.Send, "Telegram-прокси · порт ${it.port}/TCP") }
            TrafficBlock(vm, profile)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        ActionTile(AppIcons.Qr, "Ссылки", true, Modifier.weight(1f), onLinks)
        ActionTile(AppIcons.Check, "Проверить", !vm.running && profile.vps != null, Modifier.weight(1f), vm::plusDiagnose)
        ActionTile(AppIcons.Speed, "Живая", !vm.running && profile.vps != null, Modifier.weight(1f), vm::plusLiveCheck)
    }
    if (profile.vlessOn && !profile.realitySelf && profile.hy2On) {
        val s = LocalStatusColors.current
        Card(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = s.warningContainer, contentColor = s.onWarningContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Warning, null); Spacer(Modifier.width(10.dp))
                    Text("VLESS может блокироваться", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "Маскировка под чужой сайт (${profile.realitySni}) в России часто режется: имя не совпадает с адресом сервера.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(onClick = { vm.plusConfirmSelf = true }, enabled = !vm.running && profile.vps != null) {
                    Text("Перевести на свой домен")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Шторка «Новый сервер»: тариф, безопасность, запуск. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewServerSheet(vm: MainViewModel, profile: StableProfile?, onDismiss: () -> Unit) {
    // переключатель показывает текущий режим профиля — иначе ссылка AmneziaWG поменялась бы незаметно
    LaunchedEffect(Unit) { profile?.awg?.let { vm.plusTrailers = it.trailers } }
    var mode by remember { mutableStateOf(if (profile != null && vm.plusTarget != null) "existing" else "new") }
    // без профиля переезжать некуда — только новый VPS
    LaunchedEffect(profile == null) { if (profile == null) vm.plusTargetId = "" }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (profile == null) "Создать сервер" else "Заменить сервер ${profile.fqdn}", style = MaterialTheme.typography.titleLarge)
            if (profile != null) {
                Segmented(
                    listOf("new" to "Новый VPS", "existing" to "Свободный сервер"), mode,
                    { m ->
                        mode = m
                        if (m == "new") vm.plusTargetId = ""
                        else {
                            if (vm.plusTarget == null) vm.plusTargets.firstOrNull()?.let { vm.plusTargetId = it.vpsId }
                            // сверяем список с аккаунтом: удалённые VPS будут помечены
                            if (!vm.opLoaded && vm.opApiKey.isNotBlank() && vm.opClientKey.isNotBlank()) vm.opLoadVms()
                        }
                    },
                    enabled = !vm.running, modifier = Modifier.fillMaxWidth(),
                )
            }
            Hint(
                when {
                    profile == null -> "Будет создан профиль ${vm.plannedFqdn.ifEmpty { "(укажите имя на главной)" }} и куплен VPS."
                    mode == "existing" -> "Ссылки ${profile.fqdn} переедут на готовый сервер: A-запись переводится на его IP, " +
                        "протоколы этого сервера заменяются. Новый VPS не покупается."
                    else -> "Ссылки ${profile.fqdn} останутся прежними — клиентам ничего менять не нужно."
                },
            )
            if (profile == null && vm.stableProfiles.isNotEmpty()) {
                // новый сервер рядом с уже существующими: своё имя для ссылок
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppField("Поддомен", vm.subdomain, { vm.subdomain = it }, Modifier.weight(2f), KeyboardType.Uri, enabled = !vm.running)
                    AppField("TTL, с", vm.ttl, { vm.ttl = it }, Modifier.weight(1f), KeyboardType.Number, enabled = !vm.running)
                }
                Hint(if (vm.plannedFqdn.isEmpty()) "Укажите домен в карточке «Аккаунты»." else "В ссылках будет: ${vm.plannedFqdn}")
            }
            if (mode == "existing" && profile != null) CombineServerList(vm, profile) else VpsOrderForm(vm)
            SwitchRow(
                "AmneziaWG 3.1", vm.plusAwg, { vm.plusAwg = it }, enabled = !vm.running,
                subtitle = when {
                    !vm.plusAwg -> "Не ставить"
                    profile?.awg != null -> "Прежняя ссылка, порт ${profile.awg.port}/UDP"
                    else -> "Третья ссылка — для AmneziaVPN"
                },
            )
            if (vm.plusAwg) {
                val changes = profile?.awg != null && profile.awg.trailers != vm.plusTrailers
                SwitchRow(
                    "RandomTrailers", vm.plusTrailers, { vm.plusTrailers = it }, enabled = !vm.running,
                    subtitle = if (changes) "Режим изменится — ссылку AmneziaWG нужно будет добавить заново" else if (vm.plusTrailers) "Сильнее маскировка, но скорость может упасть в десятки раз" else "Выключен: нормальная скорость (рекомендуется)",
                )
            }
            SwitchRow(
                "Безопасность", vm.plusSecure, { vm.plusSecure = it }, enabled = !vm.running,
                subtitle = if (vm.plusSecure) "В конце: вход только по ключу, обновления" else "Вход по паролю root останется",
            )
            ErrorText(vm.plusError)
            val existing = mode == "existing" && profile != null
            Button(
                onClick = vm::plusRequest,
                enabled = !vm.running && (if (existing) vm.plusTarget != null else vm.opCatalogLoaded),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(AppIcons.Rocket, null); Spacer(Modifier.width(10.dp)); Text(if (existing) "Переехать" else "Запустить")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Готовые серверы комбайна: выбор, куда переезжают постоянные ссылки. */
@Composable
internal fun CombineServerList(vm: MainViewModel, profile: StableProfile) {
    val list = vm.plusTargets
    var forget by remember { mutableStateOf<CombineServer?>(null) }
    val df = remember { SimpleDateFormat("dd.MM.yyyy", Locale("ru")) }
    if (list.isEmpty()) {
        Card(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Свободных серверов пока нет", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Здесь появляются VPS, которые вы оставили после переезда (кнопка «Оставить»). Серверы, на которых работают " +
                        "другие серверы Главной, сюда не попадают — иначе их ссылки сломались бы. Ссылки ${profile.fqdn} можно перенести на новый VPS.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { srv ->
            val chosen = vm.plusTargetId == srv.vpsId
            val alive = vm.combineServerAlive(srv)
            Card(
                modifier = Modifier.fillMaxWidth().selectable(chosen, enabled = !vm.running, role = Role.RadioButton) { vm.plusTargetId = srv.vpsId },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(srv.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOf(srv.ip, srv.location, srv.createdAt.takeIf { it > 0 }?.let { df.format(Date(it)) }.orEmpty())
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val tags = listOfNotNull(
                            "прежний сервер профиля".takeIf { srv.origin == CombineServer.ORIGIN_PROFILE },
                            srv.protocols.joinToString().takeIf { it.isNotEmpty() },
                            "вход по ключу".takeIf { srv.secured },
                        )
                        if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (alive) {
                            false -> Text("Не найден в OneProvider — возможно, удалён", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            true -> Text("Есть в аккаунте OneProvider", style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.success)
                            null -> {}
                        }
                    }
                    IconButton(onClick = { forget = srv }, enabled = !vm.running) { Icon(AppIcons.Delete, "Убрать из списка") }
                }
            }
        }
    }
    if (vm.opApiKey.isNotBlank()) {
        androidx.compose.material3.TextButton(onClick = vm::opLoadVms, enabled = !vm.running) {
            Icon(AppIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Сверить с OneProvider")
        }
    }
    forget?.let { srv ->
        ConfirmDialog(
            "Убрать из списка?",
            "${srv.title} (${srv.ip}) пропадёт из этого списка. Сам VPS и его протоколы не изменятся — удалить VPS можно во вкладке «Облако».",
            "Убрать", onConfirm = { forget = null; vm.forgetCombineServer(srv) }, onDismiss = { forget = null },
        )
    }
}

/** «Удалить сервер»: только строку в списке или вместе с VPS и A-записью. */
@Composable
internal fun ServerDeleteDialog(vm: MainViewModel, p: StableProfile) {
    val fullProblem = vm.deleteFullProblem()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.serverToDelete = null },
        icon = { Icon(AppIcons.Delete, null) },
        title = { Text("Удалить ${p.fqdn}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("«Удалить из списка» — убрать только строку в приложении: сервер, VPS и DNS продолжат работать, но перенести сервер " +
                    "с прежними ссылками можно будет только из сохранённой копии.")
                Text("«Удалить VPS и DNS» — ещё и удалить VPS в OneProvider и A-запись в name.com. Ссылки перестанут работать.")
                if (fullProblem != null) Text(fullProblem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.removeFromList(p.fqdn) }, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) {
                    Text("Удалить из списка")
                }
                Button(
                    onClick = { vm.serverToDelete = null; vm.serverDeleteFull = p },
                    enabled = !vm.running && fullProblem == null, modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Удалить VPS и DNS") }
                androidx.compose.material3.TextButton(onClick = { vm.serverToDelete = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Отмена")
                }
            }
        },
    )
}

/** Сохранение копии: пароль по желанию; без пароля — честное предупреждение, что файл не зашифрован. */
@Composable
internal fun BackupSaveDialog(pass: String, onPass: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val short = pass.isNotEmpty() && pass.length < 8
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Save, null) },
        title = { Text("Сохранить копию") },
        text = { BackupSaveContent(pass, onPass) },
        confirmButton = {
            androidx.compose.material3.TextButton(onSave, enabled = !short) { Text(if (pass.isEmpty()) "Сохранить без пароля" else "Сохранить с паролем") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun BackupSaveContent(pass: String, onPass: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("В копии: все серверы Главной с ключами ссылок, вкладка «Сервер» (адрес, пароль root, SSH-ключ), имя и ключи OneProvider и name.com.")
        AppField("Пароль (необязательно)", pass, onPass, secret = true, keyboard = KeyboardType.Password)
        if (pass.isEmpty()) {
            Text(
                "Без пароля файл НЕ зашифрован: кто его найдёт, получит доступ к вашим серверам и сможет покупать VPS за ваш счёт. " +
                    "Храните его надёжно и никому не пересылайте.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Hint(if (pass.length < 8) "Пароль — не короче 8 символов." else "Запомните пароль: без него копию открыть нельзя.")
        }
    }
}

@Composable
private fun HomeDialogs(
    vm: MainViewModel,
    profile: StableProfile?,
    passFor: String?,
    pass: String,
    onPass: (String) -> Unit,
    onPassDone: (String?) -> Unit,
) {
    val target = vm.plusTarget
    if (vm.plusConfirm && target != null && profile != null) {
        ConfirmDialog(
            title = "Переехать на свободный сервер?",
            text = "Сервер ${target.title} (${target.ip}) станет основным для ${profile.fqdn}. VPS не покупается.\n\n" +
                "A-запись ${profile.fqdn} будет переведена на ${target.ip}" + (if (profile.vps != null) " (сейчас ${profile.vps.ip})" else "") +
                ", затем на него ставятся Hysteria2, VLESS" + (if (vm.plusAwg) ", AmneziaWG 3.1" else "") + " с ключами профиля" +
                (if (vm.plusSecure && !target.secured) " и включается безопасность." else ".") +
                "\n\nПротоколы, которые комбайн ставил на этот сервер, будут заменены: прежние ссылки комбайна на него перестанут работать. " +
                "Ссылки ${profile.fqdn} останутся прежними. Обычно 5–15 минут; не закрывайте приложение.",
            confirm = "Переехать", onConfirm = vm::plusRun, onDismiss = { vm.plusConfirm = false },
        )
    } else if (vm.plusConfirm) {
        val sz = vm.opSelectedSize
        val name = profile?.fqdn ?: vm.plannedFqdn
        ConfirmDialog(
            title = if (profile == null) "Создать сервер?" else "Переехать на новый VPS?",
            text = "VPS для $name:\n${vm.opSelectedLocation?.title}\n${sz?.summary}\n${vm.opSelectedTemplate?.name}\n\n" +
                "Стоимость: ${sz?.monthlyPrice}/мес (${sz?.hourlyPrice}/час) — спишется с баланса OneProvider.\n\n" +
                "A-запись $name будет переведена на новый IP" + (if (profile?.vps != null) " (сейчас ${profile.vps.ip})" else "") +
                ", затем установка Hysteria2, VLESS" + (if (vm.plusAwg) ", AmneziaWG 3.1 (на Debian 12 — с новым ядром и перезагрузкой)" else "") +
                (if (vm.plusSecure) " и включение безопасности." else " без безопасности.") +
                (if (profile != null) " Ссылки останутся прежними." else "") + " Обычно 10–20 минут; не закрывайте приложение.",
            confirm = "Запустить", onConfirm = vm::plusRun, onDismiss = { vm.plusConfirm = false },
        )
    }
    if (vm.plusConfirmSelf && profile != null) {
        ConfirmDialog(
            "Перевести VLESS на свой домен?",
            "На текущем сервере ${profile.vps?.ip} будут переустановлены Hysteria2 и VLESS. VLESS станет маскироваться под ${profile.fqdn} " +
                "вместо ${profile.realitySni}.\n\nСсылка VLESS изменится один раз (параметр sni) — её нужно заново импортировать " +
                "в Happ у всех клиентов. Ссылка Hysteria2 не меняется.",
            "Перевести", onConfirm = vm::plusSwitchToSelf, onDismiss = { vm.plusConfirmSelf = false },
        )
    }
    vm.serverToDelete?.let { ServerDeleteDialog(vm, it) }
    vm.serverDeleteFull?.let { p ->
        val v = p.vps
        ConfirmDialog(
            "Удалить VPS и DNS?",
            "Безвозвратно будут удалены:\n" +
                (if (v == null) "• VPS — у сервера его нет\n"
                else if (vm.vpsSharedWithOthers(p)) "• VPS ${v.ip} — НЕ удаляется: на нём работает другой сервер списка\n"
                else "• VPS ${v.hostname.ifEmpty { v.id.ifEmpty { "" } }} (${v.ip}) в OneProvider — со всеми данными\n") +
                "• A-запись ${p.fqdn} в name.com\n" +
                "• строка ${p.fqdn} в списке и ключи ссылок на телефоне\n\n" +
                "Все ссылки этого сервера перестанут работать у всех клиентов.",
            "Удалить", danger = true, onConfirm = { vm.deleteServerFully(p) }, onDismiss = { vm.serverDeleteFull = null },
        )
    }
    vm.serverDeleteOverage?.let { (p, r) ->
        ConfirmDialog(
            "Доплата за трафик",
            "У VPS сервера ${p.fqdn} перерасход трафика ${r.overageGb} ГБ. При удалении будет списано ${r.overageCost}. Удалить?",
            "Удалить и оплатить", danger = true, dismiss = "Оставить",
            onConfirm = { vm.deleteServerFully(p, confirmOverage = true) }, onDismiss = { vm.serverDeleteOverage = null },
        )
    }
    vm.plusPendingImport?.let { p ->
        ConfirmDialog(
            "Заменить профиль?",
            "Текущий профиль ${profile?.fqdn} будет заменён профилем ${p.fqdn} из файла. Ссылки станут такими, как в копии.",
            "Заменить", onConfirm = { vm.plusApplyImport() }, onDismiss = vm::plusSkipImport, dismiss = "Оставить текущий",
        )
    }
    vm.plusOldVps?.let { old ->
        val overage = vm.plusOldOverage
        ConfirmDialog(
            if (overage == null) "Удалить старый VPS?" else "Доплата за трафик",
            if (overage == null) "Новый сервер работает. Старый VPS ${old.hostname.ifEmpty { old.id }} (${old.ip}) больше не нужен. " +
                "Удалить его в OneProvider? Все данные на нём будут уничтожены."
            else "У старого VPS перерасход трафика ${overage.overageGb} ГБ. При удалении будет списано ${overage.overageCost}. Удалить?",
            if (overage == null) "Удалить" else "Удалить и оплатить", danger = true,
            onConfirm = { vm.plusDeleteOld(confirmOverage = overage != null) }, onDismiss = vm::plusKeepOld, dismiss = "Оставить",
        )
    }
    if (passFor == "export") BackupSaveDialog(pass, onPass, onSave = { onPassDone("export") }, onDismiss = { onPassDone(null) })
    if (vm.importNeedsPassword) {
        var importPass by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::cancelImportPassword,
            icon = { Icon(AppIcons.Lock, null) },
            title = { Text("Копия зашифрована") },
            text = { AppField("Пароль от копии", importPass, { importPass = it }, secret = true, keyboard = KeyboardType.Password) },
            confirmButton = { androidx.compose.material3.TextButton({ vm.plusImportWithPassword(importPass) }) { Text("Открыть") } },
            dismissButton = { androidx.compose.material3.TextButton(vm::cancelImportPassword) { Text("Отмена") } },
        )
    }
    vm.pendingSettings?.let { st ->
        ConfirmDialog(
            "Заменить настройки доступа?",
            "В копии есть настройки вкладки «Сервер»" + (if (st.host.isNotEmpty()) " (${st.user}@${st.host})" else "") +
                ", SSH-ключ и ключи OneProvider и name.com. На телефоне они уже заполнены и отличаются. Заменить их данными из копии?",
            "Заменить", onConfirm = { vm.confirmSettings(true) }, onDismiss = { vm.confirmSettings(false) }, dismiss = "Оставить текущие",
        )
    }
}
