package app.hy2setup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.hy2setup.ServerMode
import app.hy2setup.ServerProfile
import app.hy2setup.platform.Device

/**
 * «Инструменты → Произвольный VPS»: установка VPN на любой сервер по адресу и паролю (или SSH-ключу) — без покупки VPS, без домена и без
 * добавления сервера на Главную. Чужие серверы запоминаются в отдельном списке этой вкладки.
 */
@Composable
fun VpsCard(vm: MainViewModel, startExpanded: Boolean = false) {
    val modes = vm.serverModeList
    val names = modes.joinToString(" + ") { if (it == ServerMode.AWG) "AWG" else if (it == ServerMode.XHTTP) "XHTTP" else it.title }
    val vlessAny = ServerMode.VLESS in modes || ServerMode.XHTTP in modes
    val target = vm.vpsHost.trim()
    var ask by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    SectionCard(
        "Произвольный VPS", AppIcons.Terminal, startExpanded = startExpanded,
        subtitle = if (target.isEmpty()) "Установка VPN на любой сервер" else "${vm.vpsUser.trim()}@$target · $names",
    ) {
        Hint("Установка на любой сервер — по адресу и паролю или SSH-ключу, без покупки VPS; на Главную сервер не добавляется. Домен нужен только для маскировки VLESS под свой сервер.")

        // 1. сервер
        VpsServerCard(vm)

        // 2. действия с уже установленным
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionTile(AppIcons.Info, "Статус", !vm.running, Modifier.weight(1f)) { vm.vpsStatus() }
            ActionTile(AppIcons.Qr, "Ссылки", !vm.running, Modifier.weight(1f)) { vm.vpsFetchLinks() }
            if (modes.any { it != ServerMode.TG }) ActionTile(AppIcons.Shield, "Проверить", !vm.running, Modifier.weight(1f)) { vm.vpsDiagnose() }
        }

        // 3. результат
        ErrorText(vm.vpsError)
        if (vm.vpsLog.isNotEmpty() || vm.vpsBusy || vm.vpsResultLinks.isNotEmpty()) {
            ResultPanel(vm.vpsLog, vm.vpsBusy, vm.vpsResultLinks, compact = true)
        }

        // 4. что ставить
        SectionCard("Протоколы", AppIcons.Bolt, subtitle = names) {
            MultiPicker(
                "Что ставить",
                ServerMode.entries.map { it to PROTOCOL_NAMES.getValue(it) }, vm.serverModes,
                onToggle = { vm.toggleServerMode(it) }, enabled = !vm.running,
            )
            Hint(
                if (modes.size > 1) "Выбрано: $names — «Установить» поставит всё по очереди."
                else when (modes.first()) {
                    ServerMode.HY2 -> "Hysteria2 + Salamander: быстрый протокол поверх UDP (QUIC), самоподписанный сертификат."
                    ServerMode.VLESS -> "VLESS + Reality (Xray): выглядит как обычный HTTPS к чужому сайту, поверх TCP."
                    ServerMode.XHTTP -> "VLESS + Reality + XHTTP: тот же Xray и порт, что у VLESS, трафик идёт как HTTP-запросы. Ставится вместе с VLESS."
                    ServerMode.AWG -> "AmneziaWG 3.1: WireGuard с маскировкой, для приложения AmneziaVPN. Работает поверх UDP."
                    ServerMode.TG -> "MTProto-прокси для Telegram (mtg) в режиме FakeTLS."
                } + " В списке можно отметить несколько.",
            )
        }

        // 5. параметры
        if (ServerMode.HY2 in modes && !(vlessAny && vm.vpsSelf)) SectionCard(
            "Параметры Hysteria2", AppIcons.Tune, startExpanded = false,
            subtitle = "Порт ${vm.hyPort.trim()}/UDP · SNI ${vm.sni.trim()}",
        ) {
            AppField("Порт Hysteria2 (UDP)", vm.hyPort, { vm.hyPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
            AppField("SNI самоподписанного сертификата", vm.sni, { vm.sni = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
            AppField("Сайт-маскировка (https://…)", vm.masquerade, { vm.masquerade = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
            Hint("Без домена сертификат самоподписанный: в ссылке его отпечаток, клиенты проверяют именно его.")
        }
        if (vlessAny) SectionCard(
            "Параметры VLESS", AppIcons.Tune,
            subtitle = "Порт ${vm.vlessPort.trim()}/TCP · маскировка под " +
                if (vm.vpsSelf) vm.vpsDomain.trim().ifEmpty { "свой сервер (домен не указан)" } else vm.realitySni.trim(),
        ) {
            AppField("Порт VLESS (TCP)", vm.vlessPort, { vm.vlessPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
            SwitchRow(
                "Маскировка под свой сервер", vm.vpsSelf, { vm.vpsSelf = it }, enabled = !vm.running,
                subtitle = "Рекомендуется — как на Главной",
            )
            if (vm.vpsSelf) {
                AppField(
                    "Домен сервера", vm.vpsDomain, { vm.vpsDomain = it }, keyboard = KeyboardType.Uri, enabled = !vm.running,
                    supporting = "Должен уже указывать на IP сервера (A-запись у регистратора)",
                )
                AppField("Порт Hysteria2 (UDP)", vm.hyPort, { vm.hyPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
                AppField("E-mail для Let's Encrypt (необязательно)", vm.vpsEmail, { vm.vpsEmail = it }, keyboard = KeyboardType.Email, enabled = !vm.running)
                AppField("Сайт-маскировка (https://…)", vm.masquerade, { vm.masquerade = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
                MoreHint(
                    "Вместе с VLESS ставится Hysteria2 — получите и её ссылку.",
                    "Hysteria2 получает сертификат Let's Encrypt на ваш домен и отдаёт сайт, под который маскируется VLESS (и XHTTP). " +
                        "Имя и адрес совпадают — провайдеру не к чему придраться. Перед установкой приложение проверит, что домен указывает " +
                        "на этот сервер; DNS оно не меняет. Нужны открытые 80/TCP, 443/TCP и 443/UDP.",
                )
            } else {
                AppField("Сайт для маскировки Reality (SNI)", vm.realitySni, { vm.realitySni = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
                Hint("Крупный сайт с TLS 1.3, например www.microsoft.com. В России подключения с чужим сайтом часто режут — лучше маскировка под свой сервер.")
            }
        }
        if (ServerMode.AWG in modes) SectionCard(
            "Параметры AmneziaWG", AppIcons.Tune, startExpanded = false,
            subtitle = "Порт ${vm.awgPort}/UDP · RandomTrailers " + if (vm.awgTrailers) "вкл" else "выкл",
        ) {
            AppField("Порт AmneziaWG (UDP)", vm.awgPort, { vm.awgPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running,
                supporting = "Не 443 — его занимает Hysteria2")
            SwitchRow(
                "RandomTrailers", vm.awgTrailers, { vm.awgTrailers = it }, enabled = !vm.running,
                subtitle = if (vm.awgTrailers) "Сильнее маскировка, но скорость может упасть в десятки раз" else "Выключен: нормальная скорость (рекомендуется)",
            )
            Hint("Ставится модуль ядра AmneziaWG 3.1 — 5–15 минут, на Debian 12 с перезагрузкой сервера.")
        }
        if (ServerMode.TG in modes) SectionCard(
            "Параметры Telegram-прокси", AppIcons.Send, startExpanded = false,
            subtitle = "Порт ${vm.tgPort.trim()}/TCP · маскировка под ${vm.tgDomain.trim()}",
        ) {
            AppField("Порт (TCP)", vm.tgPort, { vm.tgPort = it }, keyboard = KeyboardType.Number, enabled = !vm.running)
            AppField("Домен маскировки (FakeTLS)", vm.tgDomain, { vm.tgDomain = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
            Hint("443 — лучший порт, если на сервере нет VLESS; иначе, например, 8443.")
        }

        // 6. безопасность: только с SSH-ключом (вход по паролю отключается)
        val keyReady = vm.vpsUseKey && vm.vpsKey.isNotBlank()
        val hardenOn = vm.vpsHarden && keyReady
        SectionCard(
            "Безопасность", AppIcons.Lock, startExpanded = false,
            subtitle = "Защитить после установки: " + when {
                hardenOn -> "вкл"
                vm.vpsHarden -> "выкл (нет SSH-ключа)"
                else -> "выкл"
            },
        ) {
            SwitchRow(
                "Защитить сервер после установки", hardenOn, { vm.vpsHarden = it }, enabled = !vm.running && keyReady,
                subtitle = when {
                    !keyReady -> "Нужен SSH-ключ — выберите «SSH-ключ» в блоке «Сервер»"
                    hardenOn -> "Вход по ключу, обновления, файрвол, fail2ban"
                    else -> "Без обновлений, вход по паролю остаётся"
                },
            )
            Hint("Файрвол: снаружи открыты только SSH и порты VPN — другие службы на сервере станут недоступны извне. fail2ban банит за перебор паролей SSH; SSH ужесточён (LoginGraceTime 30, MaxAuthTries 3, без X11). Если по ключу войти не удастся, защита не включается, а VPN остаётся рабочим.")
        }
        // 7. установка и действия с сервером
        Button(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ask = "install" },
            enabled = !vm.running, modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(AppIcons.Rocket, null); Spacer(Modifier.width(8.dp))
            Text(if (vm.vpsBusy) "Выполняется…" else if (modes.size > 1) "Установить (${modes.size})" else "Установить")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.vpsRestart() }, enabled = !vm.running, modifier = Modifier.weight(1f)) { Text("Перезапустить") }
            if (ServerMode.HY2 in modes || vlessAny) {
                OutlinedButton(onClick = { vm.vpsLiveCheck() }, enabled = !vm.running, modifier = Modifier.weight(1f)) { Text("Живая проверка") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { ask = "secure" }, enabled = !vm.running, modifier = Modifier.weight(1f)) { Text("Защитить") }
            OutlinedButton(
                onClick = { ask = "remove" }, enabled = !vm.running, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Удалить", maxLines = 1) }
        }
    }

    when (ask) {
        "install" -> ConfirmDialog(
            "Установить $names?",
            "Сервер: ${if (target.isEmpty()) "не указан" else "${vm.vpsUser.trim()}@$target:${vm.vpsPort.trim()}"}.\n" +
                "Будет поставлено: $names" +
                (if (vlessAny && vm.vpsSelf) " (VLESS с маскировкой под ${vm.vpsDomain.trim().ifEmpty { "свой домен" }}, вместе с Hysteria2)" else "") +
                ". Если эти протоколы уже стоят, они переустановятся с новыми ключами — старые ссылки перестанут работать." +
                if (vm.vpsHarden && vm.vpsUseKey && vm.vpsKey.isNotBlank()) "\nПосле установки: вход только по SSH-ключу и обновления." else "",
            "Установить", onConfirm = { ask = null; vm.vpsInstall() }, onDismiss = { ask = null },
        )
        "secure" -> ConfirmDialog(
            "Защитить сервер $target?",
            "Обновления системы, автообновления и вход только по SSH-ключу (ключ — в блоке «Сервер»). Протоколы и ссылки не меняются.\nФайрвол: снаружи открыты только SSH и порты VPN — другие службы на сервере станут недоступны извне. fail2ban банит за перебор паролей SSH; SSH ужесточён (LoginGraceTime 30, MaxAuthTries 3, без X11). " +
                "Займёт 5–15 минут.",
            "Защитить", onConfirm = { ask = null; vm.vpsSecure() }, onDismiss = { ask = null },
        )
        "remove" -> ConfirmDialog(
            "Удалить $names с сервера $target?",
            modes.joinToString("\n") {
                when (it) {
                    ServerMode.HY2 -> "• Hysteria2: служба hysteria-server будет остановлена, программа и /etc/hysteria удалены."
                    ServerMode.VLESS -> "• VLESS: служба xray будет остановлена, Xray и /usr/local/etc/xray удалены (вместе с XHTTP)."
                    ServerMode.XHTTP -> "• VLESS XHTTP: убирается из конфигурации Xray, обычный VLESS остаётся."
                    ServerMode.AWG -> "• AmneziaWG: служба awg-quick@awg0 будет остановлена, конфигурация удалена."
                    ServerMode.TG -> "• Telegram: служба mtg будет остановлена, программа и /etc/mtg.toml удалены."
                }
            } + "\nСтарые ссылки перестанут работать.",
            "Удалить", danger = true, onConfirm = { ask = null; vm.vpsRemove() }, onDismiss = { ask = null },
        )
    }
}

/** Сервер блока «Произвольный VPS»: список запомненных чужих серверов, адрес, порт, пользователь, пароль или ключ. */
@Composable
private fun VpsServerCard(vm: MainViewModel) {
    val keyPicker = rememberOpenKeyDoc { vm.vpsLoadKeyFrom(it) }
    val target = vm.vpsHost.trim()
    var pick by remember { mutableStateOf(false) }
    var toForget by remember { mutableStateOf<ServerProfile?>(null) }
    SectionCard(
        "Сервер", AppIcons.Terminal,
        subtitle = if (target.isEmpty()) "Не указан" else "${vm.vpsUser.trim()}@$target · " + if (vm.vpsUseKey) "ключ" else "пароль",
    ) {
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { pick = true }, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Запомненные серверы", style = MaterialTheme.typography.labelSmall)
                    Text(if (vm.vpsServers.isEmpty()) "Пока нет — сервер запомнится после первого входа" else "Выбрать (${vm.vpsServers.size})", maxLines = 1)
                }
                Icon(AppIcons.ExpandMore, null)
            }
            DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                DropdownMenuItem(
                    text = { Text("Новый сервер") }, leadingIcon = { Icon(AppIcons.Add, null) },
                    onClick = { pick = false; vm.vpsNew() },
                )
                vm.vpsServers.forEach { p ->
                    DropdownMenuItem(
                        text = { Column { Text(p.host); Text("${p.user} · порт ${p.port} · " + if (p.useKey) "ключ" else "пароль", style = MaterialTheme.typography.bodySmall) } },
                        onClick = { pick = false; vm.vpsSelect(p) },
                        trailingIcon = { IconButton(onClick = { pick = false; toForget = p }) { Icon(AppIcons.Delete, "Убрать ${p.host} из списка") } },
                    )
                }
            }
        }
        AppField("IP-адрес или домен сервера", vm.vpsHost, { vm.vpsHost = it }, keyboard = KeyboardType.Uri, enabled = !vm.running)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppField("Порт", vm.vpsPort, { vm.vpsPort = it }, Modifier.weight(1f), KeyboardType.Number, enabled = !vm.running)
            AppField("Пользователь", vm.vpsUser, { vm.vpsUser = it }, Modifier.weight(2f), enabled = !vm.running)
        }
        Segmented(listOf(false to "Пароль", true to "SSH-ключ"), vm.vpsUseKey, { vm.vpsUseKey = it }, enabled = !vm.running)
        if (!vm.vpsUseKey) {
            AppField("Пароль", vm.vpsPassword, { vm.vpsPassword = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
        } else {
            AppField("Приватный ключ (вставьте текст)", vm.vpsKey, { vm.vpsKey = it }, lines = 3, mono = true, enabled = !vm.running)
            OutlinedButton(onClick = { keyPicker() }, enabled = !vm.running) { Text("Выбрать файл ключа…") }
            AppField("Пароль от ключа (если есть)", vm.vpsPassphrase, { vm.vpsPassphrase = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
            if (vm.vpsUser.trim() != "root") {
                AppField("Пароль для sudo", vm.vpsPassword, { vm.vpsPassword = it }, secret = true, keyboard = KeyboardType.Password, enabled = !vm.running)
            }
        }
        MoreHint(
            "Сервер запоминается только здесь, после первого входа; пароль и ключ — на ${Device.PREP} зашифрованными.",
            "На Главную, в «Доступ по SSH» и в контроль серверов такие серверы не попадают. Убрать сервер из списка — значок корзины в списке; " +
                "сам сервер при этом не меняется. Поддерживаются ключи ed25519, RSA, ECDSA (OpenSSH, PEM, PuTTY .ppk).",
        )
    }
    toForget?.let { p ->
        ConfirmDialog(
            "Убрать сервер из списка?",
            "${p.user}@${p.host}:${p.port} и сохранённые для него пароль и ключ будут удалены с ${Device.GEN}. Сам сервер не затрагивается.",
            "Убрать", danger = true, onConfirm = { toForget = null; vm.vpsForget(p) }, onDismiss = { toForget = null },
        )
    }
}
