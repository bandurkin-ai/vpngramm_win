package app.hy2setup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hy2setup.Awg
import app.hy2setup.Links
import app.hy2setup.LogSummary
import app.hy2setup.MainViewModel
import app.hy2setup.ProfileUser
import app.hy2setup.Protocol
import app.hy2setup.ShareLink
import app.hy2setup.StableProfile
import app.hy2setup.Traffic
import app.hy2setup.TgParams

/** Протоколы профиля в порядке показа. */
internal fun protocolsOf(p: StableProfile): List<String> = listOfNotNull(
    Protocol.VLESS.title.takeIf { p.vlessOn },
    Links.XHTTP_TITLE.takeIf { p.xhttpActive },
    Protocol.HY2.title.takeIf { p.hy2On },
    Awg.TITLE.takeIf { p.awgOn },
    TgParams.TITLE.takeIf { p.tgOn },
)

private fun shortName(title: String) = when (title) {
    Protocol.VLESS.title -> "VLESS"
    Protocol.HY2.title -> "Hysteria2"
    Awg.TITLE -> "AmneziaWG"
    TgParams.TITLE -> "Telegram"
    else -> title
}

/** «2 ч назад», «5 мин назад», «вчера». */
internal fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t) / 60_000
    return when {
        m < 1 -> "только что"
        m < 60 -> "$m мин назад"
        m < 24 * 60 -> "${m / 60} ч назад"
        m < 48 * 60 -> "вчера"
        else -> "${m / (24 * 60)} дн назад"
    }
}

/** Чипы «работает / нет / не проверялся» по каждому протоколу (по последней «Проверить»). */
@Composable
internal fun ProtocolStatusRow(p: StableProfile) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        protocolsOf(p).forEach { title ->
            // XHTTP работает внутри той же службы xray, что и VLESS
            val ok = p.status[if (title == Links.XHTTP_TITLE) Protocol.VLESS.title else title]
            StatusChip(
                when (ok) { true -> LogSummary.State.OK; false -> LogSummary.State.ERROR; null -> LogSummary.State.INFO },
                shortName(title),
            )
        }
    }
}

/** Общий итог сервера для цветной точки: зелёный — всё работает, красный — что-то нет, серый — не проверялся. */
internal fun overallOk(p: StableProfile): Boolean? =
    if (p.checkAt > 0 && p.status.isNotEmpty()) p.status.values.all { it } else null

@Composable
internal fun StatusDot(ok: Boolean?) {
    val s = LocalStatusColors.current
    val c = when (ok) { true -> s.success; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.outline }
    Spacer(Modifier.size(10.dp).clip(CircleShape).background(c))
}

/** Трафик за месяц и баланс OneProvider (если известны). */
@Composable
internal fun TrafficBlock(vm: MainViewModel, p: StableProfile) {
    val used = p.trafficGb
    val limit = vm.trafficLimitGb(p)
    val bal = vm.opBalance
    if (used == null && bal == null) return
    val onHero = MaterialTheme.colorScheme.onPrimaryContainer
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (used != null) {
            Text(Traffic.usage(used, limit, p.checkAt), style = MaterialTheme.typography.bodyMedium, color = onHero)
            if (limit != null) {
                val frac = (used / limit).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { frac }, modifier = Modifier.fillMaxWidth(),
                    color = if (frac > 0.8f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            val (level, state) = Traffic.limitState(used, limit)
            Text(
                state, style = MaterialTheme.typography.bodySmall,
                color = when (level) {
                    Traffic.Level.OVER, Traffic.Level.WARN -> MaterialTheme.colorScheme.error
                    Traffic.Level.OK -> LocalStatusColors.current.success
                    Traffic.Level.UNKNOWN -> onHero.copy(alpha = 0.75f)
                },
            )
        }
        // баланс OneProvider — в долларах: «$» ставится, если API не прислал его сам
        if (bal != null) Text("Баланс OneProvider: " + (if ('$' in bal) bal else "$$bal"), style = MaterialTheme.typography.bodyMedium, color = onHero)
    }
}

/** Баннер «копия устарела». */
@Composable
internal fun BackupBanner(onSave: () -> Unit) {
    val s = LocalStatusColors.current
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = s.warningContainer, contentColor = s.onWarningContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Save, null)
            Spacer(Modifier.width(12.dp))
            Text("Копия сервера устарела: без неё ссылки не восстановить",
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onSave) { Text("Сохранить") }
        }
    }
}

/** Люди на сервере: у каждого свои ключи и своя подписка. */
@Composable
internal fun PeopleCard(vm: MainViewModel, p: StableProfile, onLinks: (ProfileUser?) -> Unit) {
    var add by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    SectionCard("Люди (${1 + p.users.size})", AppIcons.Key, collapsible = true,
        subtitle = "У каждого свои ключи — можно отключить одного") {
        PersonRow("Я (владелец)", "ключи сервера", null, vm, onLinks = { onLinks(null) })
        p.users.forEach { u ->
            PersonRow(u.name, if (u.disabled) "отключён" else "свои ключи и подписка", u, vm, onLinks = { onLinks(u) })
        }
        FilledTonalButton(onClick = { name = ""; add = true }, enabled = !vm.running && p.vps != null) {
            Icon(AppIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Добавить человека")
        }
        if (p.vps == null) Hint("Сначала создайте сервер.")
        if (p.tgOn) Hint("Telegram-прокси общий для всех — отключить его одному человеку нельзя.")
    }
    if (add) {
        AlertDialog(
            onDismissRequest = { add = false },
            icon = { Icon(AppIcons.Add, null) },
            title = { Text("Новый человек") },
            text = { AppField("Имя (например, Мама)", name, { name = it }) },
            confirmButton = { TextButton({ add = false; vm.addPerson(name) }) { Text("Далее") } },
            dismissButton = { TextButton({ add = false }) { Text("Отмена") } },
        )
    }
    vm.peopleConfirm?.let {
        ConfirmDialog("Изменить людей?", vm.peopleConfirmText, "Применить", onConfirm = vm::confirmPeople, onDismiss = { vm.peopleConfirm = null })
    }
}

@Composable
private fun PersonRow(name: String, sub: String, u: ProfileUser?, vm: MainViewModel, onLinks: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StatusDot(if (u?.disabled == true) false else true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onLinks, enabled = u?.disabled != true) { Icon(AppIcons.Qr, "Ссылки $name") }
        if (u != null) {
            IconButton(onClick = { vm.togglePerson(u.id) }, enabled = !vm.running) {
                Icon(if (u.disabled) AppIcons.Check else AppIcons.Stop, if (u.disabled) "Включить" else "Отключить")
            }
            IconButton(onClick = { vm.removePerson(u.id) }, enabled = !vm.running) {
                Icon(AppIcons.Delete, "Удалить", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Ссылки человека, разложенные по приложениям: Happ (подписка, VLESS, Hysteria2), AmneziaVPN, Telegram. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinksSheet(vm: MainViewModel, p: StableProfile, startUser: ProfileUser?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { LinksContent(vm, p, startUser) }
    }
}

@Composable
internal fun LinksContent(vm: MainViewModel, p: StableProfile, startUser: ProfileUser?) {
    var userId by remember { mutableStateOf(startUser?.id) }
    var open by remember { mutableStateOf<ShareLink?>(null) }
    val share = rememberShare()
    val copy = rememberCopier()
    val user = p.users.firstOrNull { it.id == userId }
    val links = p.linksFor(user)
    Text("Ссылки ${p.fqdn}", style = MaterialTheme.typography.titleLarge)
    if (p.users.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = user == null, onClick = { userId = null }, label = { Text("Я") })
            p.users.filter { !it.disabled }.forEach { u -> FilterChip(selected = u.id == userId, onClick = { userId = u.id }, label = { Text(u.name) }) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { share(links.joinToString("\n\n") { "${it.title}:\n${it.uri}" }, "Поделиться ссылками") }) {
            Icon(AppIcons.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Поделиться всеми")
        }
        OutlinedButton(onClick = { copy("Ссылки", links.joinToString("\n") { it.uri }) }) { Text("Скопировать всё") }
    }

    val happ = links.filter { it.title == Protocol.VLESS.title || it.title == Links.XHTTP_TITLE || it.title == Protocol.HY2.title }
    if (happ.isNotEmpty()) {
        AppGroup("Happ", "Лучше всего — подписка: Happ сам подтянет новые ключи и протоколы. Добавьте её через «+» → «Из буфера» или QR.") {
            if (p.subAvailable) {
                val sub = ShareLink("Подписка Happ", p.subUrl(user))
                LinkRow(sub, onOpen = { open = sub })
                if (p.status.isNotEmpty() && p.checkAt > 0) Hint("Если Happ не открывает подписку — нажмите «Обновить сервер» (ключи не меняются).")
                TextButton(onClick = { vm.plusApply() }, enabled = !vm.running && p.vps != null) { Text("Обновить сервер") }
            }
            happ.forEach { l -> LinkRow(l, onOpen = { open = l }) }
        }
    }
    links.firstOrNull { it.title == Awg.TITLE }?.let { l ->
        AppGroup("AmneziaVPN", "Откройте ссылку vpn:// в AmneziaVPN (5.0.2 или новее) или отсканируйте QR.") { LinkRow(l, onOpen = { open = l }) }
    }
    links.firstOrNull { it.title == TgParams.TITLE }?.let { l ->
        AppGroup("Telegram", "Откройте ссылку на телефоне — Telegram предложит подключить прокси. Он общий для всех людей.") { LinkRow(l, onOpen = { open = l }) }
    }
    Spacer(Modifier.height(12.dp))
    open?.let { LinkSheet(it) { open = null } }
}

@Composable
private fun AppGroup(title: String, hint: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
