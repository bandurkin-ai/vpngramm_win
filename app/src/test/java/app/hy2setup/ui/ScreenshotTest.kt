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
    /** Блок «Инструменты → Произвольный VPS» развёрнутым, отдельно от экрана. */
    private fun vpsCardShot(name: String, model: MainViewModel) {
        captureRoboImage("build/screens/$name-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { VpsCard(model, startExpanded = true) }
                }
            }
        }
    }

    /** «Произвольный VPS»: запомненный чужой сервер, журнал и ссылки, выбрано несколько протоколов; журнал «Инструментов» не трогается. */
    @Test
    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    fun vpsTall() {
        val model = vm(0).apply {
            vpsHost = "198.51.100.7"; vpsPassword = "secret"; vpsHarden = true   // без ключа защита недоступна
            toggleServerMode(app.hy2setup.ServerMode.HY2); toggleServerMode(app.hy2setup.ServerMode.AWG)
            vpsLog += listOf("Подключаюсь к 198.51.100.7:22 и читаю настройки…", "Ссылок получено: 2. Они ниже.")
            vpsResultLinks += ShareLink("VLESS", "vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443?security=reality#vps")
        }
        org.junit.Assert.assertTrue(model.toolsLog.none { "198.51.100.7" in it })
        vpsCardShot("3-tools-vps", model)
    }

    /** Вход по паролю: «Защитить после установки» недоступна, в заголовке — «выкл (нет SSH-ключа)». */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun vpsHardenNeedsKey() {
        val model = vm(0).apply { vpsHost = "198.51.100.7"; vpsUseKey = false; vpsPassword = "secret"; vpsHarden = true }
        compose.setContent {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) { VpsCard(model, startExpanded = true) }
                }
            }
        }
        compose.onNodeWithText("Защитить после установки: выкл (нет SSH-ключа)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Нужен SSH-ключ — выберите «SSH-ключ» в блоке «Сервер»").assertExists()
    }

    /** «Параметры VLESS» в «Произвольном VPS»: маскировка под свой сервер с доменом. */
    @Test
    @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun vpsSelfMask() {
        val model = vm(0).apply {
            vpsHost = "198.51.100.7"; vpsPassword = "secret"; vpsSelf = true; vpsDomain = ""
            if (app.hy2setup.ServerMode.XHTTP !in serverModes) toggleServerMode(app.hy2setup.ServerMode.XHTTP)
        }
        vpsCardShot("3-tools-vps-selfmask", model)
    }

    /** «Инструменты»: все блоки закрыты, «Произвольный VPS» — первым в «Сервисе», «Контроля серверов» нет. */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun toolsAllCollapsed() {
        val model = vm(2)
        compose.setContent { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
        compose.onNodeWithText("Произвольный VPS").assertExists()
        compose.onNodeWithText("Контроль серверов").assertDoesNotExist()
        compose.onNodeWithText("IP-адрес или домен сервера").assertDoesNotExist()   // «Доступ по SSH» закрыт
        compose.onNodeWithText("Запомненные серверы").assertDoesNotExist()           // «Произвольный VPS» закрыт
        compose.onNodeWithText("VPS").assertDoesNotExist()                             // вкладки «VPS» больше нет
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screens/3-tools-collapsed-light.png")
    }

    /** Главная после неудачного переезда: карточка «Переезд не завершён» и «$» у баланса. */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun homeRollbackCard() {
        val model = vm(0).apply {
            val before = plusProfile!!
            plusProfile = before.copy(
                vps = ProfileVps("999", "198.51.100.9", "vpn-new"), trafficGb = 12.3, checkAt = System.currentTimeMillis(),
                status = mapOf("VLESS" to true),
            )
            setBalanceForTest("17.42")
            setRollbackForTest(
                app.hy2setup.MoveRollback(
                    fqdn = "vpn.example.org", before = before.toJson(), oldIp = "203.0.113.10",
                    newId = "999", newIp = "198.51.100.9", newHost = "vpn-new", bought = true, dnsChanged = true,
                ),
            )
            plusLog.clear()
            plusLog += listOf("▶ Hysteria2 + Salamander (ключи профиля)", "ОШИБКА: Установка Hysteria2 завершилась с ошибкой (код 1).")
        }
        compose.setContent { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
        compose.onNodeWithText("Переезд vpn.example.org не завершён").assertExists()
        compose.onNodeWithText("Баланс OneProvider: $17.42").assertExists()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screens/1-home-rollback-light.png")
        // окно подтверждения: куплен новый VPS — выбор «удалить» или «оставить»
        compose.onNodeWithText("Откатить переезд").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Откатить и удалить новый VPS").assertExists()
        compose.onNodeWithText("Откатить, VPS оставить").assertExists()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screens/1-home-rollback-dialog-light.png")
    }

    /** «Свободный сервер»: сервер, добавленный вручную, и форма «Добавить сервер по IP». */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun freeServerForm() {
        val model = vm(0).apply {
            combineServers += CombineServer("", "198.51.100.7", "", hostname = "my-vps", protocols = listOf("VLESS"), origin = CombineServer.ORIGIN_MANUAL)
            plusTargetId = "ip:198.51.100.7"
        }
        compose.setContent {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { CombineServerList(model, model.plusProfile!!) }
                }
            }
        }
        compose.onNodeWithText("добавлен вручную", substring = true).assertExists()
        compose.onNodeWithText("Сверить с OneProvider").assertDoesNotExist()   // ручной сервер с OneProvider не сверяется
        compose.onNodeWithText("Добавить сервер по IP").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Добавить в свободные серверы").assertExists()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screens/1-home-freeserver-form-light.png")
    }

    @Test fun cloudLight() = shot("2-cloud", 1, false)
    @Test fun cloudDark() = shot("2-cloud", 1, true)
    @Test fun toolsLight() = shot("3-tools", 2, false)
    @Test fun toolsDark() = shot("3-tools", 2, true)

    @Test fun regionThenLocation() {
        val model = vm(1, withProfile = false)
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
            plusProfile = plusProfile!!.copy(vps = ProfileVps("123", "203.0.113.10", "vpn", trafficLimitGb = 1000.0), trafficGb = 312.4, checkAt = System.currentTimeMillis(), status = mapOf("VLESS" to true, "Hysteria2" to true))
        }
        org.junit.Assert.assertEquals(1000.0, model.trafficLimitGb(model.plusProfile!!))
        captureRoboImage("build/screens/1-home-traffic-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    /** «Инструменты → Ключи для всех серверов»: два ключа токена (тестовые). */
    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun defaultKeysCard() {
        val model = vm(2)
        // начальный список сборки (если есть локальный default-keys.pub) заменяем тестовыми ключами
        model.defaultKeys.toList().forEach { model.removeDefaultKey(it.blob) }
        org.junit.Assert.assertTrue(model.addDefaultKeys("sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29tAAAAICCk8FhAsU6IeB/jEusJXp65VKtjeiTegEYM72oNsR1VAAAACnNzaDpteW5hbWU= token-main\nsk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29tAAAAIPvXyZsLqQHOBp1eGq776pyrG2NfxCuVe75DpCFpWdePAAAACHNzaDpza18y token-backup"))
        org.junit.Assert.assertEquals(listOf("token-main", "token-backup"), model.defaultKeys.map { it.comment })
        captureRoboImage("build/screens/3-tools-defaultkeys-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { DefaultKeysCard(model, startExpanded = true) }
                }
            }
        }
        // удалённый ключ после перезапуска не возвращается
        model.removeDefaultKey(model.defaultKeys.first().blob)
        val again = MainViewModel(ApplicationProvider.getApplicationContext<android.app.Application>())
        org.junit.Assert.assertEquals(listOf("token-backup"), again.defaultKeys.map { it.comment })
    }

    /** «Инструменты → Доступ по SSH»: выбран сервер Главной, IP исправлен, но ещё не применён. */
    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun toolsAccessEdit() {
        val model = vm(2)
        model.accessSelect("vpn.example.org")
        model.host = "198.51.100.7"
        org.junit.Assert.assertTrue(model.accessDirty)
        captureRoboImage("build/screens/3-tools-access-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { SshAccessCard(model, startExpanded = true) }
                }
            }
        }
    }

    /** Главная: вход на сервер не удался — кнопка «Исправить доступ по SSH». */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun homeAccessProblem() {
        val model = vm(0).apply { plusAccessProblem = true; plusLog += "ОШИБКА: Не удалось подключиться к 203.0.113.10:22" }
        captureRoboImage("build/screens/1-home-access-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }

    /** «Инструменты → Аппаратные ключи»: YubiKey (ed25519, резидентный) и Рутокен (ecdsa, нерезидентный). */
    @Test
    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun hwKeysCard() {
        val model = vm(2, withProfile = false)
        val yk = app.hy2setup.hwkey.FakeFidoToken(pin = "123456", ed25519 = true, name = "YubiKey 5C Nano")
        val ru = app.hy2setup.hwkey.FakeFidoToken(ed25519 = false, name = "Рутокен MFA")
        model.setHwKeysForTest(listOf(
            app.hy2setup.hwkey.Hw.create(yk, resident = true) { "123456" },
            app.hy2setup.hwkey.Hw.create(ru, resident = false) { null },
        ))
        org.junit.Assert.assertTrue(model.hwKeySupported)
        captureRoboImage("build/screens/3-tools-hwkeys-light.png") {
            Hy2Theme(dark = false, dynamic = false) {
                androidx.compose.material3.Surface {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { HwKeysCard(model, startExpanded = true) }
                }
            }
        }
        // включить вход по ключу нельзя, пока вход не проверен
        model.setHwLoginEnabled(true)
        org.junit.Assert.assertFalse(model.hwLogin)
    }

    /** Выбор локации с пингом: замер (до VPS в локации) и оценки «≈»; локации региона — по возрастанию пинга. */
    @Test
    fun orderPing() {
        val model = vm(1, withProfile = false)
        model.opSizes += app.hy2setup.OpSize("44", "", "General", "1", "1024", "20", "1000", "1000", "4.00", "0.006", true)
        model.opLocations += listOf(
            app.hy2setup.OpLocation("1", "Europe", "Italy", "Florence", setOf("44")),
            app.hy2setup.OpLocation("2", "Europe", "Netherlands", "Amsterdam", setOf("44")),
            app.hy2setup.OpLocation("3", "North America", "USA", "Miami", setOf("44")),
        )
        model.opTemplates += app.hy2setup.OpTemplate("t", "Debian 12")
        model.opTemplateId = "t"
        model.opCatalogLoaded = true
        model.opPing["1"] = app.hy2setup.PingResult(58, estimated = true)
        model.opPing["2"] = app.hy2setup.PingResult(41, estimated = false)
        model.opPing["3"] = app.hy2setup.PingResult(142, estimated = true)
        model.opSelectRegion("Europe")
        org.junit.Assert.assertEquals(listOf("2", "1"), model.opLocationsInRegion.map { it.id })
        org.junit.Assert.assertEquals(41, model.opRegionPing("Europe")?.ms)
        captureRoboImage("build/screens/3-cloud-order-ping-light.png") {
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

    /** Заказ VPS с фильтром: тарифы дороже $10 видны, фильтр «до $20, от 2 ТБ» оставляет подходящие. */
    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun orderFilter() {
        val model = vm(1, withProfile = false)
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
        org.junit.Assert.assertEquals(app.hy2setup.OpFilter(20, 2000), vm(1, withProfile = false).opFilter)
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

    /** Длинный журнал в «Инструментах»: окно результата невысокое, внутри — пролистывание. */
    @Test
    fun serverLongLog() {
        val model = vm(2).apply {
            log.clear()
            log += (1..12).flatMap { listOf("▶ Шаг $it: установка компонента $it", "OK: компонент $it готов") } + "Готово."
        }
        captureRoboImage("build/screens/3-tools-longlog-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
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

    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun addServerForm() {
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

    /** «Добавить работающий сервер» без домена: сервер пойдёт в «Свободные серверы». */
    @Test
    @Config(qualifiers = "w411dp-h1300dp-xxhdpi")
    fun addServerFormFree() {
        val model = vm(0).apply { importFqdn = ""; importHost = "198.51.100.7"; freePassword = "secret" }
        captureRoboImage("build/screens/1-home-add-free-light.png") {
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
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun toolsMonitor() {
        val model = vm(2)
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
    fun homeTall() {
        val model = vm(0)
        captureRoboImage("build/screens/v3-home-light.png") { Hy2Theme(dark = false, dynamic = false) { AppRoot(model) } }
    }
}
