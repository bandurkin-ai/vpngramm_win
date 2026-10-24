package app.hy2setup.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hy2setup.MainViewModel
import app.hy2setup.TAB_HOME
import app.hy2setup.vpn.VpnConfig
import app.hy2setup.vpn.VpnProtocol
import app.hy2setup.vpn.VpnState

/** Встроенный VPN: сервер с Главной, протокол (VLESS, Hysteria2 или AmneziaWG) и кнопка подключения. */
@Composable
fun VpnScreen(vm: MainViewModel) {
    ScreenFrame(title = "VPN", running = false) {
        val profile = vm.vpnProfile
        if (profile == null) {
            SectionCard("Сервера ещё нет", AppIcons.Shield, collapsible = false) {
                Hint("Встроенный VPN подключается к вашим серверам с Главной. Сначала создайте сервер или добавьте уже работающий.")
                FilledTonalButton(onClick = { vm.selectedTab = TAB_HOME }) {
                    Icon(AppIcons.Home, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("На Главную")
                }
            }
            return@ScreenFrame
        }
        val busy = VpnState.active
        val proto = vm.vpnProto
        val avail = VpnConfig.available(profile)

        VpnPanel(vm, profile, proto)

        SectionCard("Подключение", AppIcons.Tune, subtitle = "${proto.title} · ${profile.fqdn}" + if (vm.vpnBypassRu) " · РФ напрямую" else "") {
            if (vm.stableProfiles.size > 1) {
                Picker("Сервер", profile.fqdn, vm.stableProfiles.map { it.fqdn to it.fqdn }, enabled = !busy) { vm.selectVpnServer(it) }
            }
            Text("Протокол", style = MaterialTheme.typography.labelLarge)
            if (avail.isEmpty()) {
                Hint("На сервере ${profile.fqdn} нет ни VLESS, ни Hysteria2, ни AmneziaWG.")
            } else {
                Segmented(avail.map { it to SHORT.getValue(it) }, proto, { vm.selectVpnProto(it) }, enabled = !busy)
                Hint(
                    when (proto) {
                        VpnProtocol.VLESS -> "VLESS + Reality: похож на обычный HTTPS и работает почти везде. Рекомендуется."
                        VpnProtocol.XHTTP -> "VLESS + Reality + XHTTP: тот же сервер и порт, но трафик идёт как обычные HTTP-запросы. Попробуйте, если VLESS режут."
                        VpnProtocol.HY2 -> "Hysteria2: самый быстрый, идёт по UDP. Если оператор режет UDP и подключение не идёт — выберите VLESS."
                        VpnProtocol.AWG -> "AmneziaWG: WireGuard с маскировкой, по UDP. Режим RandomTrailers встроенный клиент не поддерживает."
                    },
                )
            }
            val missing = VpnProtocol.entries.filter { it !in avail }
            if (missing.isNotEmpty()) {
                MoreHint(
                    "На этом сервере нет: ${missing.joinToString { it.title }}.",
                    "Поставьте протокол на вкладке «Сервер» (или при замене сервера на Главной), затем нажмите «Проверить» на Главной — " +
                        "приложение считает его с сервера, и он появится здесь.",
                )
            }
            SwitchRow(
                "Российские сайты напрямую", vm.vpnBypassRu, { vm.updateVpnBypassRu(it) }, enabled = !busy,
                subtitle = if (vm.vpnBypassRu) "Сайты .ru/.рф и российские IP — мимо VPN" else "Весь трафик идёт через VPN",
            )
            if (busy) Hint("Чтобы сменить сервер, протокол или маршрутизацию, сначала отключитесь.")
        }
    }
}

/** Короткие подписи протоколов (четыре кнопки в ряд). */
private val SHORT = mapOf(VpnProtocol.VLESS to "VLESS", VpnProtocol.XHTTP to "XHTTP", VpnProtocol.HY2 to "Hy2", VpnProtocol.AWG to "AWG")
