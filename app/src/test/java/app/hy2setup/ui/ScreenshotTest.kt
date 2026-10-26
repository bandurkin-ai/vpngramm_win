package app.hy2setup.ui

import androidx.test.core.app.ApplicationProvider
import app.hy2setup.CombineServer
import app.hy2setup.MainViewModel
import app.hy2setup.PlusStep
import app.hy2setup.ProfileVps
import app.hy2setup.ShareLink
import app.hy2setup.StableProfile
import app.hy2setup.StepStatus
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Test
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Скриншоты разделов в светлой и тёмной теме (без эмулятора). PNG — в app/build/screens/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private fun vm(tab: Int, withProfile: Boolean = true) = MainViewModel(ApplicationProvider.getApplicationContext<android.app.Application>()).apply {
        selectedTab = tab
        if (withProfile) {
            plusProfile = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/")
                .copy(vps = ProfileVps("123", "203.0.113.10", "vpn"))
            plusLinks += ShareLink("VLESS", "vless://11111111-2222-3333-4444-555555555555@vpn.example.org:443?security=reality#vpn")
            plusLinks += ShareLink("Hysteria2", "hysteria2://secret@vpn.example.org:443?obfs=salamander#vpn")
            plusLog += listOf("== Проверка сервера vpn.example.org ==", "OK: Hysteria2 работает", "OK: Xray работает", "Готово.")
            PlusStep.entries.forEach { plusSteps[it] = StepStatus.DONE }
        }
        host = "203.0.113.10"
        log += listOf("Подключаюсь к 203.0.113.10…", "OK: служба hysteria-server активна", "Готово.")
    }

    private fun shot(name: String, tab: Int, dark: Boolean, withProfile: Boolean = true) {
        val model = vm(tab, withProfile)
        captureRoboImage("build/screens/$name-${if (dark) "dark" else "light"}.png") {
            Hy2Theme(dark = dark, dynamic = false) { AppRoot(model) }
        }
    }

    @Test fun homeLight() = shot("1-home", 0, false)
    @Test fun homeDark() = shot("1-home", 0, true)
    @Test fun homeEmpty() = shot("1-home-empty", 0, false, withProfile = false)
    @Test fun vpnLight() = shot("1b-vpn", 1, false)
    @Test fun vpnDark() = shot("1b-vpn", 1, true)
    @Test fun vpnEmpty() = shot("1b-vpn-empty", 1, false, withProfile = false)
    @Test fun serverLight() = shot("2-server", 2, false)
    @Test fun serverDark() = shot("2-server", 2, true)
    @Test fun cloudLight() = shot("3-cloud", 3, false)
    @Test fun cloudDark() = shot("3-cloud", 3, true)
    @Test fun toolsLight() = shot("4-tools", 4, false)
    @Test fun toolsDark() = shot("4-tools", 4, true)

    /** Вкладка «Сервер»: выбрано несколько протоколов сразу. */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun serverMulti() {
        val model = vm(2).apply {
            serverMode = app.hy2setup.ServerMode.VLESS
            toggleServerMode(app.hy2setup.ServerMode.XHTTP); toggleServerMode(app.hy2setup.ServerMode.TG)
        }
        org.junit.Assert.assertEquals(
            listOf(app.hy2setup.ServerMode.VLESS, app.hy2setup.ServerMode.XHTTP, app.hy2setup.ServerMode.TG), model.serverModeList,
        )
        captureRoboImage("build/screens/2-server-multi-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    /** Вкладка VPN: на сервере три протокола, выбран Hysteria2. */
    @Test
    fun vpnHysteria() {
        val model = vm(1).apply {
            plusProfile = plusProfile!!.copy(awg = app.hy2setup.Awg.generate("vpn.example.org", 34127), awgOn = true, xhttpOn = true)
            selectVpnProto(app.hy2setup.vpn.VpnProtocol.HY2)
        }
        org.junit.Assert.assertEquals(app.hy2setup.vpn.VpnProtocol.HY2, model.vpnProto)
        captureRoboImage("build/screens/1b-vpn-hy2-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun serverAwg() {
        val model = vm(2).apply { serverMode = app.hy2setup.ServerMode.AWG; protocol = app.hy2setup.Protocol.AWG }
        captureRoboImage("build/screens/2-server-awg-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    @Test fun regionThenLocation() {
        val model = vm(3, withProfile = false)
        val sz = app.hy2setup.OpSize("44", "", "General", "1", "1024", "20", "1000", "1000", "4.00", "0.006", true)
        model.opSizes += sz
        model.opLocations += listOf(
            app.hy2setup.OpLocation("1", "Europe", "Italy", "Florence", setOf("44")),
            app.hy2setup.OpLocation("2", "Europe", "Netherlands", "Amsterdam", setOf("44")),
            app.hy2setup.OpLocation("3", "North America", "USA", "Miami", setOf("44")),
            app.hy2setup.OpLocation("4", "Europe", "Norway", "Oslo", emptySet()),
        )
        model.opTemplates += app.hy2setup.OpTemplate("t", "Debian 12")
        model.opTemplateId = "t"
        model.opCatalogLoaded = true
        model.opBad["1"] = System.currentTimeMillis()
        model.opSelectRegion("Europe")
        // Осло без тарифов скрыт; помеченная Флоренция — в конце, выбрана Амстердам
        org.junit.Assert.assertEquals(listOf("2", "1"), model.opLocationsInRegion.map { it.id })
        org.junit.Assert.assertEquals("2", model.opSelectedLocation?.id)
        org.junit.Assert.assertEquals(listOf("Europe" to 1, "North America" to 1), model.opRegions)
        captureRoboImage("build/screens/3-cloud-order-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { VpsOrderForm(model) }
                }
            }
        }
    }

    /** Главная: трафик за месяц с полосой — лимит сохранён в профиле, список VPS OneProvider не загружен. */
    @Test
    fun homeTrafficBar() {
        val model = vm(0).apply {
            plusProfile = plusProfile!!.copy(vps = ProfileVps("123", "203.0.113.10", "vpn", trafficLimitGb = 1000.0), trafficGb = 312.4)
        }
        org.junit.Assert.assertEquals(1000.0, model.trafficLimitGb(model.plusProfile!!))
        captureRoboImage("build/screens/1-home-traffic-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    /** Заказ VPS с фильтром: тарифы дороже $10 видны, фильтр «до $20, от 2 ТБ» оставляет подходящие. */
    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun orderFilter() {
        val model = vm(3, withProfile = false)
        fun sz(id: String, ram: String, traffic: String, price: String) =
            app.hy2setup.OpSize(id, "", "General", "1", ram, "20", traffic, "1000", price, "0.01", true)
        model.opSizes += listOf(sz("44", "1024", "1000", "4.00"), sz("45", "2048", "2000", "12.00"), sz("46", "4096", "5000", "24.00"))
        model.opLocations += listOf(
            app.hy2setup.OpLocation("2", "Europe", "Netherlands", "Amsterdam", setOf("44", "45", "46")),
            app.hy2setup.OpLocation("3", "North America", "USA", "Miami", setOf("44")),
        )
        model.opTemplates += app.hy2setup.OpTemplate("t", "Debian 12")
        model.opTemplateId = "t"
        model.opCatalogLoaded = true
        model.opSelectLocation("2")
        // без фильтра — все три тарифа, в том числе дороже $10
        org.junit.Assert.assertEquals(listOf("44", "45", "46"), model.opSizesForLocation.map { it.id })
        model.opSizeId = "44"
        model.opSetFilter(app.hy2setup.OpFilter(maxPrice = 20, minTrafficGb = 2000))
        org.junit.Assert.assertEquals(listOf("45"), model.opSizesForLocation.map { it.id })
        org.junit.Assert.assertEquals("45", model.opSelectedSize?.id)                  // выбор перешёл на подходящий
        org.junit.Assert.assertEquals(listOf("Europe" to 1), model.opRegions)           // в Майами нет тарифа под фильтр
        org.junit.Assert.assertEquals(2, model.opSizesHiddenByFilter)
        captureRoboImage("build/screens/3-cloud-order-filter-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { VpsOrderForm(model) }
                }
            }
        }
        // фильтр запоминается
        org.junit.Assert.assertEquals(app.hy2setup.OpFilter(20, 2000), vm(3, withProfile = false).opFilter)
        model.opSetFilter(app.hy2setup.OpFilter())
    }

    /** Главная → «Протоколы»: прочитано с сервера, XHTTP отмечен к установке, Telegram — к удалению. */
    @Test
    fun homeProtocolsCard() {
        val model = vm(0).apply {
            homeProtoInstalled = setOf(app.hy2setup.ServerMode.HY2, app.hy2setup.ServerMode.VLESS, app.hy2setup.ServerMode.TG)
            homeProtoChosen = homeProtoInstalled!!
            homeProtoToggle(app.hy2setup.ServerMode.XHTTP)
            homeProtoToggle(app.hy2setup.ServerMode.TG)
        }
        org.junit.Assert.assertEquals(setOf(app.hy2setup.ServerMode.XHTTP), model.homeProtoAdd)
        org.junit.Assert.assertEquals(setOf(app.hy2setup.ServerMode.TG), model.homeProtoRemove)
        // снятый VLESS снимает и XHTTP
        model.homeProtoToggle(app.hy2setup.ServerMode.VLESS)
        org.junit.Assert.assertFalse(app.hy2setup.ServerMode.XHTTP in model.homeProtoChosen)
        model.homeProtoToggle(app.hy2setup.ServerMode.XHTTP)
        org.junit.Assert.assertTrue(app.hy2setup.ServerMode.VLESS in model.homeProtoChosen)
        captureRoboImage("build/screens/1-home-protocols-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                        ServerProtocolsCard(model, model.plusProfile!!, startExpanded = true)
                    }
                }
            }
        }
    }

    /** Длинный журнал на вкладке «Сервер»: окно результата невысокое, внутри — пролистывание. */
    @Test
    fun serverLongLog() {
        val model = vm(2).apply {
            log.clear()
            log += (1..12).flatMap { listOf("▶ Шаг $it: установка компонента $it", "OK: компонент $it готов") } + "Готово."
        }
        captureRoboImage("build/screens/2-server-longlog-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    /** «Создать новый сервер»: выбор протоколов и портов (бывший комбайн). */
    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun newServerProtocols() {
        val model = vm(0, withProfile = false).apply { plusHy2 = true; plusVless = true; plusXhttp = true; plusTg = true; tgPort = "993" }
        captureRoboImage("build/screens/5-new-server-protocols-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) { NewServerProtocols(model) }
                }
            }
        }
        // новый профиль получает выбранные протоколы и порты
        val p = model.newServerProfile("example.org", "vpn3")
        org.junit.Assert.assertTrue(p.hy2On && p.vlessOn && p.tgOn)
        org.junit.Assert.assertEquals(993, p.tg!!.port)
        org.junit.Assert.assertEquals("vpn3.example.org", p.realitySni)
        // без Hysteria2 VLESS маскируется под чужой сайт
        model.plusHy2 = false; model.plusTg = false; model.realitySni = "www.microsoft.com"
        val q = model.newServerProfile("example.org", "vpn4")
        org.junit.Assert.assertTrue(!q.hy2On && q.vlessOn && !q.tgOn && q.tg == null)
        org.junit.Assert.assertEquals("www.microsoft.com", q.realitySni)
    }

    /** «Заменить сервер → Сервер из комбайна»: текущий сервер профиля в списке не показывается. */
    @Test fun combineServerList() {
        val model = vm(0).apply {
            combineServers += CombineServer("201", "198.51.100.7", "vpn1.example.org", "vpn1", "Amsterdam, Netherlands", 1_760_000_000_000,
                listOf("VLESS + Reality", "Hysteria2", "AmneziaWG"), secured = true)
            combineServers += CombineServer("202", "198.51.100.8", "vpn2.example.org", "vpn2", "Frankfurt, Germany", 1_760_500_000_000,
                listOf("Hysteria2", "Telegram-прокси (MTProto)"))
            combineServers += CombineServer("123", "203.0.113.10", "vpn.example.org", origin = CombineServer.ORIGIN_PROFILE)
            plusTargetId = "202"
        }
        org.junit.Assert.assertEquals(listOf("201", "202"), model.plusTargets.map { it.vpsId })
        org.junit.Assert.assertEquals("202", model.plusTarget?.vpsId)
        captureRoboImage("build/screens/1-home-existing-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    Segmented(listOf("new" to "Новый VPS", "existing" to "Сервер из комбайна"), "existing", {})
                    CombineServerList(model, model.plusProfile!!)
                }
            }
        }
    }

    /** Главная с двумя серверами: переключатель вверху. */
    @Test fun homeTwoServers() {
        val model = vm(0).apply {
            plusProfile = StableProfile.create("example.org", "vpn2", "", "https://www.bing.com/")
                .copy(vps = ProfileVps("456", "198.51.100.20", "vpn2"), tg = app.hy2setup.TgParams.generate(993, "www.microsoft.com"), tgOn = true)
            selectStable("vpn.example.org")
        }
        captureRoboImage("build/screens/1-home-two-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    @Test fun addServerForm() {
        val model = vm(0).apply { importFqdn = "vpn2.example.org"; importHost = "203.0.113.66" }
        captureRoboImage("build/screens/1-home-add-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(20.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { AddServerForm(model) {} }
                }
            }
        }
    }

    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()

    /** Диалог живёт в отдельном окне — снимаем весь экран. */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test fun deleteDialog() {
        val model = vm(0)
        compose.setContent { Hy2Theme(dark = false, dynamic = false) { ServerDeleteDialog(model, model.plusProfile!!) } }
        compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screens/1-home-delete-light.png")
    }

    /** Список «Протоколы» на вкладке «Сервер» открыт: столбец с галочками. */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test fun protocolsDropdown() {
        val model = vm(2).apply { serverMode = app.hy2setup.ServerMode.VLESS; toggleServerMode(app.hy2setup.ServerMode.XHTTP) }
        compose.setContent { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
        compose.onNodeWithText("Что ставить").performClick()
        compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screens/2-server-protocols-light.png")
        // список не закрывается при выборе: отметили ещё и Hysteria2
        compose.onNodeWithText("Hysteria2 + Salamander").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertTrue(app.hy2setup.ServerMode.HY2 in model.serverModes)
        compose.onNodeWithText("AmneziaWG 3.1").assertExists()
    }

    private fun v2vm() = vm(0).apply {
        val u = app.hy2setup.ProfileUser.create("Мама", emptyList())
        val p0 = plusProfile!!
        plusProfile = p0.copy(
            users = listOf(u, app.hy2setup.ProfileUser.create("Ноутбук", listOf(u)).copy(disabled = true)),
            awg = app.hy2setup.Awg.generate(p0.fqdn, 30000), awgOn = true,
            checkAt = System.currentTimeMillis() - 2 * 3600_000L,
            status = mapOf("VLESS + Reality" to true, "Hysteria2 + Salamander" to true, "AmneziaWG" to false),
            trafficGb = 123.4,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun homeV2() {
        val model = v2vm()
        captureRoboImage("build/screens/v2-home-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun linksByApp() {
        val model = v2vm()
        captureRoboImage("build/screens/v2-links-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(20.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { LinksContent(model, model.plusProfile!!, null) }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun wizard() {
        val model = vm(0, withProfile = false)
        captureRoboImage("build/screens/v2-wizard-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    @Test
    fun serverQuick() {
        val model = vm(2)
        captureRoboImage("build/screens/v2-server-quick-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun toolsMonitor() {
        val model = vm(4)
        captureRoboImage("build/screens/v2-tools-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    @Test fun backupDialog() {
        captureRoboImage("build/screens/v201-backup-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) {
                        androidx.compose.material3.Text("Сохранить копию", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                        BackupSaveContent("") {}
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun vpnPanel() {
        val model = vm(0)
        captureRoboImage("build/screens/v3-home-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }
}
