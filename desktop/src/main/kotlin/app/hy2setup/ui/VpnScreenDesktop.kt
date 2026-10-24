package app.hy2setup.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hy2setup.MainViewModel
import app.hy2setup.TAB_HOME

/** VPN на ПК: встроенного клиента пока нет — подключение через Happ или v2rayN по ссылкам сервера. */
@Composable
fun VpnScreen(vm: MainViewModel) {
    ScreenFrame(title = "VPN", running = false) {
        SectionCard("Встроенный VPN — в версии для Android", AppIcons.Shield, collapsible = false) {
            Hint(
                "На компьютере подключайтесь через Happ для Windows или v2rayN: это бесплатные клиенты, они понимают " +
                    "все ссылки vpngramm — VLESS, VLESS XHTTP и Hysteria2 — и подписку.",
            )
            MoreHint(
                "Как подключить компьютер — по шагам:",
                "\n1. Установите Happ для Windows (happ.su) или v2rayN (github.com/2dust/v2rayN).\n" +
                    "2. Ниже нажмите на «Подписку Happ» и «Копировать».\n" +
                    "3. В Happ: «+» → «Вставить из буфера». В v2rayN: «Группы подписок» → «Настройки» → «Добавить», " +
                    "вставьте адрес и нажмите «Обновить подписку».\n" +
                    "4. Включите подключение. Если VLESS не работает — выберите VLESS XHTTP, на UDP-сетях — Hysteria2.\n\n" +
                    "AmneziaWG подключается в AmneziaVPN для Windows, прокси Telegram — в Telegram Desktop.",
            )
        }
        val profile = vm.vpnProfile
        if (profile == null) {
            SectionCard("Сервера ещё нет", AppIcons.Home, collapsible = false) {
                Hint("Ссылки появятся, когда на Главной будет сервер. Создайте его или добавьте уже работающий.")
                FilledTonalButton(onClick = { vm.selectedTab = TAB_HOME }) {
                    Icon(AppIcons.Home, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("На Главную")
                }
            }
            return@ScreenFrame
        }
        if (vm.stableProfiles.size > 1) {
            Picker("Сервер", profile.fqdn, vm.stableProfiles.map { it.fqdn to it.fqdn }, enabled = true) { vm.selectVpnServer(it) }
        }
        LinksContent(vm, profile, null)
    }
}
