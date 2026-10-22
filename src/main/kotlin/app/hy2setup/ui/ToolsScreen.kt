package app.hy2setup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hy2setup.CombineStep
import app.hy2setup.MainViewModel
import app.hy2setup.StepStatus

/** Инструменты: классический комбайн и служебные действия. */
@Composable
fun ToolsScreen(vm: MainViewModel, startPage: String = "") {
    var page by rememberSaveable { mutableStateOf(startPage) }
    if (page == "combine") {
        BackHandler { page = "" }
        CombinePage(vm) { page = "" }
        return
    }
    var ask by remember { mutableStateOf<String?>(null) }
    val version = app.hy2setup.APP_VERSION

    ScreenFrame(title = "Инструменты", running = vm.running) {
        ToolGroup {
            ToolItem(AppIcons.Bolt, "Комбайн (классический)", "Новый сервер с выбранными протоколами — появится на Главной") { page = "combine" }
        }
        SectionCard(
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
            ToolItem(AppIcons.Key, "Забыть пароли и токены", "Удалить с телефона сохранённые пароли, ключ SSH и токены", !vm.running) { ask = "secrets" }
            ToolItem(AppIcons.Shield, "Сбросить доверенные серверы", "Снова спрашивать отпечаток при подключении", !vm.running) { ask = "hosts" }
        }
        ToolGroup {
            ToolItem(AppIcons.Info, "О приложении", "vpngramm v$version · @aeromike", chevron = false) {}
        }
    }

    when (ask) {
        "secrets" -> ConfirmDialog(
            "Забыть пароли и токены?",
            "С телефона будут удалены сохранённые пароли, приватный ключ SSH, ключи OneProvider и токен name.com. " +
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

/** Классический комбайн: новый VPS → A-запись → выбранные протоколы → (безопасность) → ссылки. */
@Composable
private fun CombinePage(vm: MainViewModel, onBack: () -> Unit) {
    ScreenFrame(
        title = "Комбайн",
        running = vm.running,
        navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, "Назад") } },
        floating = {
            if (vm.combineActive) {
                ExtendedFloatingActionButton(
                    onClick = vm::combineStop, icon = { Icon(AppIcons.Stop, null) }, text = { Text("Остановить") },
                    containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = { if (!vm.running) vm.combineRequest() },
                    icon = { Icon(AppIcons.Rocket, null) }, text = { Text("Запустить") },
                )
            }
        },
    ) {
        MoreHint(
            "Создаёт VPS и ставит на него выбранные протоколы одной кнопкой.",
            "OneProvider → дождаться загрузки → A-запись в name.com → проверка DNS → протоколы, которые включены ниже " +
                "(Hysteria2, VLESS, AmneziaWG, Telegram) → (с безопасностью: вход по ключу, обновления, автообновления, " +
                "отключение пароля) → ссылки и QR. Готовый сервер появляется на Главной: его ссылки постоянные, и сервер можно " +
                "переносить на другой VPS кнопкой «Заменить сервер» — ссылки не изменятся.",
        )
        if (vm.combineSteps.isNotEmpty()) {
            SectionCard("Ход выполнения", AppIcons.Bolt) {
                Stepper(CombineStep.entries.map { it.title to (vm.combineSteps[it] ?: StepStatus.PENDING) })
                if (vm.combineActive) Hint("Остановка срабатывает между шагами. Уже созданный VPS останется — удалите его в «Облаке», если он не нужен.")
            }
        }
        ErrorText(vm.combineError)
        ResultPanel(vm.combineLog, vm.combineActive, vm.combineLinks, title = "Журнал комбайна")

        AccountsCard(vm, startExpanded = vm.opApiKey.isBlank() || vm.ncToken.isBlank())
        SectionCard("VPS", AppIcons.Cloud) { VpsOrderForm(vm) }
        SectionCard("Имя сервера", AppIcons.Globe, subtitle = vm.plannedFqdn.ifEmpty { null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppField("Поддомен", vm.subdomain, { vm.subdomain = it }, Modifier.weight(2f), KeyboardType.Uri, enabled = !vm.running)
                AppField("TTL, сек", vm.ttl, { vm.ttl = it }, Modifier.weight(1f), KeyboardType.Number, enabled = !vm.running)
            }
            SwitchRow("Перезаписать существующую A-запись", vm.overwrite, { vm.overwrite = it }, enabled = !vm.running)
            if (vm.plannedFqdn.isEmpty()) Hint("Укажите домен в «Аккаунтах».")
        }
        Text("Протоколы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        Hint("Как на вкладке «Сервер»: включите нужные. Каждый даёт свою ссылку.")
        SectionCard("Hysteria2 + Salamander", AppIcons.Tune, subtitle = if (vm.combineHy2) "Порт ${vm.hyPort}/UDP" else "Не ставится") {
            SwitchRow(
                "Добавить Hysteria2", vm.combineHy2, { vm.combineHy2 = it }, enabled = !vm.running,
                subtitle = "UDP, очень быстрый; даёт сертификат и сайт, под которые маскируется VLESS",
            )
            if (vm.combineHy2) {
                AppField("Порт (UDP)", vm.hyPort, { vm.hyPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
                AppField("E-mail для Let's Encrypt (необязательно)", vm.email, { vm.email = it }, keyboard = KeyboardType.Email, enabled = !vm.running)
                AppField("Сайт-маскировка (https://…)", vm.masquerade, { vm.masquerade = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
            }
        }
        val selfMask = vm.combineHy2
        SectionCard(
            "VLESS + Reality", AppIcons.Shield,
            subtitle = when {
                !vm.combineVless -> "Не ставится"
                selfMask -> "Маскировка под ${vm.plannedFqdn.ifEmpty { "свой домен" }}"
                else -> "Маскировка под ${vm.realitySni.ifBlank { "чужой сайт" }}"
            },
        ) {
            SwitchRow(
                "Добавить VLESS", vm.combineVless, { vm.combineVless = it }, enabled = !vm.running,
                subtitle = "TCP — надёжнее всего на мобильном интернете в России",
            )
            if (vm.combineVless) {
                AppField("Порт VLESS (TCP)", vm.vlessPort, { vm.vlessPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
                if (selfMask) {
                    Hint("Маскировка под свой домен: сертификат и сайт даёт Hysteria2. Нужны открытые 80/TCP, 443/TCP и 443/UDP.")
                } else {
                    AppField("Сайт для маскировки Reality", vm.realitySni, { vm.realitySni = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
                    Hint(
                        "Без Hysteria2 VLESS маскируется под чужой сайт. В России такую маскировку режут чаще: имя сайта не совпадает " +
                            "с адресом сервера. Надёжнее включить Hysteria2.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        SectionCard("AmneziaWG 3.1", AppIcons.Bolt, subtitle = if (vm.combineAwg) "Порт ${vm.awgPort}/UDP" else "Не ставится") {
            SwitchRow(
                "Добавить AmneziaWG", vm.combineAwg, { vm.combineAwg = it }, enabled = !vm.running,
                subtitle = "Для приложения AmneziaVPN",
            )
            if (vm.combineAwg) {
                AppField("Порт AmneziaWG (UDP)", vm.awgPort, { vm.awgPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
                SwitchRow(
                    "RandomTrailers", vm.awgTrailers, { vm.awgTrailers = it }, enabled = !vm.running,
                    subtitle = if (vm.awgTrailers) "Сильнее маскировка, но скорость может упасть в десятки раз" else "Выключен: нормальная скорость (рекомендуется)",
                )
                Hint("Модуль ядра собирается 5–15 минут; на Debian 12 сначала ставится ядро 6.12 и сервер перезагружается.")
            }
        }
        SectionCard("Telegram-прокси", AppIcons.Send, subtitle = if (vm.combineTg) "Порт ${vm.tgPort}/TCP" else "Не ставится") {
            SwitchRow(
                "Добавить Telegram-прокси", vm.combineTg, { on ->
                    vm.combineTg = on
                    // 443/TCP обычно занят VLESS — сразу предлагаем свободный порт
                    if (on && vm.combineVless && vm.tgPort.trim() == vm.vlessPort.trim()) vm.tgPort = "993"
                }, enabled = !vm.running,
                subtitle = "MTProto (mtg) с маскировкой FakeTLS — только для Telegram",
            )
            if (vm.combineTg) {
                AppField("Порт (TCP)", vm.tgPort, { vm.tgPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
                AppField("Домен маскировки", vm.tgDomain, { vm.tgDomain = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
                if (vm.combineVless && vm.tgPort.trim() == vm.vlessPort.trim()) {
                    Hint("Порт ${vm.tgPort}/TCP уже занят VLESS — укажите другой, например 993.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        SectionCard("Безопасность", AppIcons.Lock) {
            SwitchRow(
                "Защитить сервер", vm.combineSecure, { vm.combineSecure = it }, enabled = !vm.running,
                subtitle = if (vm.combineSecure) "Вход по ключу из кабинета, без пароля" else "Вход по паролю root",
            )
            if (vm.combineSecure) Hint("Отметьте ключ в блоке VPS; его приватная часть — в «Доступе по SSH» раздела «Сервер».")
        }
    }

    if (vm.combineConfirm) {
        val sz = vm.opSelectedSize
        ConfirmDialog(
            "Запустить комбайн?",
            "Будет создан VPS ${vm.plannedFqdn}:\n${vm.opSelectedLocation?.title}\n${sz?.summary}\n${vm.opSelectedTemplate?.name}\n\n" +
                "Стоимость: ${sz?.monthlyPrice}/мес (${sz?.hourlyPrice}/час) — спишется с баланса OneProvider.\n\n" +
                "Затем: A-запись ${vm.plannedFqdn} в name.com и установка: " + listOfNotNull(
                    "Hysteria2 + Salamander (порт ${vm.hyPort}/UDP)".takeIf { vm.combineHy2 },
                    ("VLESS + Reality (порт ${vm.vlessPort}/TCP, маскировка под " + (if (vm.combineHy2) vm.plannedFqdn else vm.realitySni) + ")").takeIf { vm.combineVless },
                    "AmneziaWG 3.1 (порт ${vm.awgPort}/UDP)".takeIf { vm.combineAwg },
                    "Telegram-прокси (порт ${vm.tgPort}/TCP)".takeIf { vm.combineTg },
                ).joinToString(", ") +
                (if (vm.combineSecure) ", а в самом конце — вход по SSH-ключу, обновления и автообновления, отключение входа по паролю."
                else " (без безопасности).") +
                " Обычно всё занимает 5–15 минут; не закрывайте приложение.",
            "Запустить", onConfirm = vm::combineRun, onDismiss = { vm.combineConfirm = false },
        )
    }
}
