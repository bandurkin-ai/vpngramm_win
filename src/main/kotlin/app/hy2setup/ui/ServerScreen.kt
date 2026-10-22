package app.hy2setup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hy2setup.MainViewModel
import app.hy2setup.Protocol
import app.hy2setup.ServerMode

/** Сервер: ручная установка Hysteria2, VLESS или Telegram-прокси на свой VPS. */
@Composable
fun ServerScreen(vm: MainViewModel) {
    val mode = vm.serverMode
    val tg = mode == ServerMode.TG
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmSecure by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    ScreenFrame(
        title = "Сервер",
        running = vm.running,
        actions = {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, "Ещё") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Перезапустить службу") }, leadingIcon = { Icon(AppIcons.Refresh, null) }, enabled = !vm.running,
                    onClick = { menu = false; if (tg) vm.tgRestart() else vm.restartService() },
                )
                if (!tg) {
                    DropdownMenuItem(
                        text = { Text("Живая проверка") }, leadingIcon = { Icon(AppIcons.Speed, null) }, enabled = !vm.running,
                        onClick = { menu = false; vm.liveCheck() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Защитить сервер") }, leadingIcon = { Icon(AppIcons.Lock, null) }, enabled = !vm.running,
                    onClick = { menu = false; confirmSecure = true },
                )
                DropdownMenuItem(
                    text = { Text("Удалить ${mode.title}", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) }, enabled = !vm.running,
                    onClick = { menu = false; confirmRemove = true },
                )
            }
        },
        floating = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (vm.running) return@ExtendedFloatingActionButton
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (tg) vm.tgInstall() else vm.install()
                },
                icon = { Icon(AppIcons.Rocket, null) },
                text = { Text(if (vm.running) "Выполняется…" else "Установить") },
                containerColor = if (vm.running) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
            )
        },
    ) {
        Segmented(
            listOf(false to "Быстро", true to "Эксперт"), vm.serverExpert, { vm.updateServerExpert(it) },
            enabled = !vm.running, modifier = Modifier.fillMaxWidth(),
        )
        Segmented(
            ServerMode.entries.map { it to if (it == ServerMode.AWG) "AWG" else it.title }, mode,
            onSelect = {
                vm.serverMode = it
                when (it) {
                    ServerMode.HY2 -> vm.protocol = Protocol.HY2
                    ServerMode.VLESS -> vm.protocol = Protocol.VLESS
                    ServerMode.AWG -> vm.protocol = Protocol.AWG
                    ServerMode.TG -> Unit
                }
            },
            enabled = !vm.running,
        )
        Hint(
            when (mode) {
                ServerMode.HY2 -> "Hysteria2 + Salamander: быстрый протокол поверх UDP (QUIC)."
                ServerMode.VLESS -> "VLESS + Reality (Xray): выглядит как обычный HTTPS поверх TCP."
                ServerMode.AWG -> "AmneziaWG 3.1: WireGuard с маскировкой, для приложения AmneziaVPN. Работает поверх UDP."
                ServerMode.TG -> "MTProto-прокси для Telegram (mtg) в режиме FakeTLS."
            },
        )

        // быстрые действия с уже установленным сервером
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionTile(AppIcons.Info, "Статус", !vm.running, Modifier.weight(1f)) { if (tg) vm.tgStatus() else vm.status() }
            ActionTile(AppIcons.Qr, "Ссылки", !vm.running, Modifier.weight(1f)) { if (tg) vm.tgFetchLink() else vm.fetchLinks() }
            if (!tg) ActionTile(AppIcons.Shield, "Проверить", !vm.running, Modifier.weight(1f), vm::diagnose)
        }

        ErrorText(if (tg) vm.tgError else vm.errorMessage)
        if (tg) ResultPanel(vm.tgLog, vm.running, vm.tgLinks) else ResultPanel(vm.log, vm.running, vm.resultLinks)

        SshAccessCard(vm)
        if (!vm.serverExpert) {
            Hint("Быстрый режим: нужны только адрес и пароль (или ключ) выше — порты, маскировка и прочее берутся по умолчанию. " +
                "Свой домен, порты и защиту сервера — в режиме «Эксперт».")
        } else if (tg) TelegramParams(vm) else {
            DnsNameCard(vm)
            when (mode) {
                ServerMode.VLESS -> VlessParams(vm)
                ServerMode.AWG -> AwgParamsCard(vm)
                else -> Hy2Params(vm)
            }
        }
        if (vm.serverExpert) SectionCard(
            "Безопасность", AppIcons.Lock, collapsible = true, startExpanded = false,
            subtitle = "Защитить после установки: " + if (vm.hardenBeforeInstall) "вкл" else "выкл",
        ) {
            SwitchRow(
                "Защитить сервер после установки", vm.hardenBeforeInstall, { vm.hardenBeforeInstall = it }, enabled = !vm.running,
                subtitle = if (vm.hardenBeforeInstall) "Вход по ключу, обновления, без пароля" else "Без обновлений, вход по паролю остаётся",
            )
            MoreHint(
                "Включается в самом конце, после установки протокола.",
                "Вход по SSH-ключу (приватный ключ — в «Доступе по SSH», например ключ из кабинета OneProvider), обновления " +
                    "системы, автообновления и отключение входа по паролю. Если по ключу войти не удастся, защита не включается, " +
                    "а протокол остаётся рабочим.",
            )
        }
    }

    if (confirmSecure) {
        ConfirmDialog(
            "Защитить сервер ${vm.host.trim()}?",
            "Обновления системы, автообновления и вход только по SSH-ключу (ключ — в «Доступе по SSH»). Протоколы и ссылки " +
                "не меняются. Если прошлое обновление прервалось, оно будет доделано. Займёт 5–15 минут; если связь оборвётся, " +
                "обновление продолжится на сервере, а приложение переподключится.",
            "Защитить", onConfirm = { confirmSecure = false; vm.secureNow() }, onDismiss = { confirmSecure = false },
        )
    }
    if (confirmRemove) {
        ConfirmDialog(
            "Удалить ${mode.title} с сервера?",
            when (mode) {
                ServerMode.HY2 -> "Служба hysteria-server будет остановлена, программа и /etc/hysteria удалены."
                ServerMode.VLESS -> "Служба xray будет остановлена, Xray и /usr/local/etc/xray удалены."
                ServerMode.AWG -> "Служба awg-quick@awg0 будет остановлена, конфигурация AmneziaWG удалена. Старая ссылка перестанет работать."
                ServerMode.TG -> "Служба mtg будет остановлена, программа и /etc/mtg.toml удалены. Старая ссылка перестанет работать."
            },
            "Удалить", danger = true,
            onConfirm = { confirmRemove = false; if (tg) vm.tgRemove() else vm.remove() },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun DnsNameCard(vm: MainViewModel) {
    val fqdn = vm.plannedFqdn
    SectionCard(
        "Имя и DNS", AppIcons.Globe,
        subtitle = when {
            vm.useDns && fqdn.isNotEmpty() -> "$fqdn → IP сервера"
            vm.domain.isNotBlank() -> vm.domain.trim()
            else -> "Без своего имени"
        },
        collapsible = true, startExpanded = true,
    ) {
        SwitchRow(
            "Создать A-запись в name.com", vm.useDns, { vm.useDns = it }, enabled = !vm.running,
            subtitle = "Установка пойдёт на это имя",
        )
        if (vm.useDns) {
            NameComFields(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppField("Поддомен", vm.subdomain, { vm.subdomain = it }, Modifier.weight(2f), KeyboardType.Uri, enabled = !vm.running)
                AppField("TTL, сек", vm.ttl, { vm.ttl = it }, Modifier.weight(1f), KeyboardType.Number, enabled = !vm.running)
            }
            SwitchRow("Перезаписать A-запись с этим именем", vm.overwrite, { vm.overwrite = it }, enabled = !vm.running)
            Hint(
                if (fqdn.isEmpty()) "Укажите домен — получится, например, vpn.example.com."
                else "Имя сервера: $fqdn. В «Доступе по SSH» должен быть IPv4 сервера.",
            )
        } else if (vm.protocol == Protocol.HY2 || vm.protocol == Protocol.AWG || vm.vlessSelf) {
            AppField(
                "Свой домен" + if (vm.protocol != Protocol.VLESS) " (необязательно)" else "",
                vm.domain, { vm.domain = it }, keyboard = KeyboardType.Uri, enabled = !vm.running,
                supporting = when (vm.protocol) {
                    Protocol.HY2 -> "С доменом — сертификат Let's Encrypt"
                    Protocol.AWG -> "С доменом ссылка не изменится при смене IP"
                    else -> "Должен указывать на этот сервер"
                },
            )
        }
    }
}

@Composable
private fun VlessParams(vm: MainViewModel) {
    SectionCard("Параметры VLESS", AppIcons.Tune) {
        AppField("Порт VLESS (TCP)", vm.vlessPort, { vm.vlessPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
        SwitchRow(
            "Маскировка под свой домен", vm.vlessSelf, { vm.vlessSelf = it }, enabled = !vm.running,
            subtitle = "Рекомендуется в России",
        )
        if (vm.vlessSelf) {
            MoreHint(
                "Вместе с VLESS ставится Hysteria2 — получите две ссылки.",
                "Hysteria2 получает сертификат Let's Encrypt на ваш домен и отдаёт сайт, под который маскируется VLESS. Имя и " +
                    "адрес совпадают — провайдеру не к чему придраться. Нужны открытые 80/TCP, 443/TCP и 443/UDP.",
            )
            AppField("Порт Hysteria2 (UDP)", vm.hyPort, { vm.hyPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
            AppField("E-mail для Let's Encrypt (необязательно)", vm.email, { vm.email = it }, keyboard = KeyboardType.Email, enabled = !vm.running)
            AppField("Сайт-маскировка (https://…)", vm.masquerade, { vm.masquerade = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
        } else {
            AppField("Сайт для маскировки Reality (SNI)", vm.realitySni, { vm.realitySni = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
            MoreHint(
                "Крупный сайт с TLS 1.3, например www.microsoft.com.",
                "В России подключения с чужим сайтом часто режут (имя не совпадает с адресом сервера) — тогда включите маскировку под свой домен.",
            )
        }
    }
}

@Composable
private fun Hy2Params(vm: MainViewModel) {
    SectionCard("Параметры Hysteria2", AppIcons.Tune) {
        AppField("Порт Hysteria2 (UDP)", vm.hyPort, { vm.hyPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
        if (vm.useDns || vm.domain.isNotBlank()) {
            AppField("E-mail для Let's Encrypt (необязательно)", vm.email, { vm.email = it }, keyboard = KeyboardType.Email, enabled = !vm.running)
        } else {
            AppField("SNI самоподписанного сертификата", vm.sni, { vm.sni = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
        }
        AppField("Сайт-маскировка (https://…)", vm.masquerade, { vm.masquerade = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
    }
}

@Composable
private fun TelegramParams(vm: MainViewModel) {
    SectionCard("Параметры прокси", AppIcons.Send) {
        AppField("Порт (TCP)", vm.tgPort, { vm.tgPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
        AppField("Домен маскировки (FakeTLS)", vm.tgDomain, { vm.tgDomain = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
        MoreHint(
            "443 — лучший порт, если на сервере нет VLESS; иначе, например, 8443.",
            "Hysteria2 работает по UDP и не мешает. Домен — крупный сайт с HTTPS (www.microsoft.com, www.cloudflare.com и т.п.). " +
                "Ссылку откройте на телефоне или отсканируйте QR — Telegram предложит подключить прокси.",
        )
    }
}

@Composable
private fun AwgParamsCard(vm: MainViewModel) {
    SectionCard(
        "Параметры AmneziaWG", AppIcons.Tune, collapsible = true, startExpanded = false,
        subtitle = "Порт ${vm.awgPort}/UDP · RandomTrailers " + if (vm.awgTrailers) "вкл" else "выкл",
    ) {
        AppField(
            "Порт AmneziaWG (UDP)", vm.awgPort, { vm.awgPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running,
            supporting = "Случайный и постоянный; не 443 — его занимает Hysteria2",
        )
        SwitchRow(
            "RandomTrailers", vm.awgTrailers, { vm.awgTrailers = it }, enabled = !vm.running,
            subtitle = if (vm.awgTrailers) "Сильнее маскировка, но скорость может упасть в десятки раз" else "Выключен: нормальная скорость (рекомендуется)",
        )
        MoreHint(
            "Ставится модуль ядра AmneziaWG 3.1 — 5–15 минут.",
            "На Debian 12 сначала ставится ядро 6.12 из bookworm-backports и сервер перезагружается. Ключи и параметры маскировки " +
                "создаются заново при каждой установке; постоянная ссылка — на Главной. Подключение — в приложении AmneziaVPN 5.0.2 или новее.",
        )
    }
}
