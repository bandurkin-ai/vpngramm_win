package app.hy2setup

import app.hy2setup.platform.DesktopPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** «Инструменты → Произвольный VPS»: чужой сервер по ключу на стенде sshd — запоминается только в своём списке, журнал свой. */
class VpsDesktopTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun waitIdle(vm: MainViewModel) {
        val end = System.currentTimeMillis() + 60_000
        Thread.sleep(200)
        while (vm.running && System.currentTimeMillis() < end) {
            if (vm.hostKeyPrompt != null) javax.swing.SwingUtilities.invokeAndWait { vm.answerHostKey(true) }
            Thread.sleep(100)
        }
        assertFalse("задача не закончилась", vm.running)
    }

    @Test
    fun foreignServerIsRememberedOnlyInVpsList() {
        val dir = System.getenv("SSH_TEST_DIR")
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val data = tmp.newFolder()
        val vm = MainViewModel(DesktopPlatform(data))
        javax.swing.SwingUtilities.invokeAndWait {
            vm.vpsHost = "127.0.0.1"; vm.vpsPort = "2222"; vm.vpsUser = "root"
            vm.vpsUseKey = true; vm.vpsKey = File(dir, "k_ed25519").readText()
            vm.vpsFetchLinks()
        }
        waitIdle(vm)
        assertTrue(vm.vpsLog.joinToString("\n"), vm.vpsLog.any { "Подключаюсь к 127.0.0.1:2222" in it })
        assertTrue(vm.toolsLog.isEmpty())
        assertEquals(listOf("root@127.0.0.1:2222"), vm.vpsServers.map { it.id })
        assertTrue(vm.profiles.none { it.host == "127.0.0.1" })
        assertTrue(vm.stableProfiles.isEmpty())

        // после перезапуска сервер и ключ на месте; выбор подставляет их
        val again = MainViewModel(DesktopPlatform(data))
        javax.swing.SwingUtilities.invokeAndWait {
            again.vpsNew()
            assertEquals("", again.vpsHost)
            again.vpsSelect(again.vpsServers.single())
        }
        assertEquals("127.0.0.1", again.vpsHost)
        assertTrue(again.vpsUseKey)
        assertTrue(again.vpsKey.contains("PRIVATE KEY"))
        javax.swing.SwingUtilities.invokeAndWait { again.vpsForget(again.vpsServers.single()) }
        assertTrue(MainViewModel(DesktopPlatform(data)).vpsServers.isEmpty())
    }

    @Test
    fun badFieldsDoNotLeakIntoTools() {
        val vm = MainViewModel(DesktopPlatform(tmp.newFolder()))
        javax.swing.SwingUtilities.invokeAndWait {
            vm.vpsHost = ""
            vm.vpsInstall()
        }
        assertEquals("Укажите IP-адрес или домен сервера.", vm.vpsError)
        assertEquals(null, vm.errorMessage)   // ошибка показывается в самом блоке, не в «Доступе к серверам»
        assertFalse(vm.running)
        // режим VPS не залип: журнал — снова «Инструментов»
        javax.swing.SwingUtilities.invokeAndWait { vm.log.add("x") }
        assertEquals(listOf("x"), vm.toolsLog.toList())
        assertTrue(vm.vpsLog.isEmpty())
    }

    /** Вход по паролю + «Защитить после установки»: установка не блокируется, защита пропускается с записью в журнале. */
    @Test
    fun passwordInstallSkipsHardening() {
        val vm = MainViewModel(DesktopPlatform(tmp.newFolder()))
        javax.swing.SwingUtilities.invokeAndWait {
            vm.vpsHost = "127.0.0.1"; vm.vpsPort = "1"; vm.vpsUser = "root"   // закрытый порт: до установки дело не дойдёт
            vm.vpsUseKey = false; vm.vpsPassword = "secret"; vm.vpsHarden = true
            vm.vpsInstall()
            assertEquals(null, vm.errorMessage)
            assertTrue(vm.running)
        }
        waitIdle(vm)
        assertTrue(vm.vpsLog.joinToString("\n"), vm.vpsLog.any { "Защита пропущена" in it })
        assertTrue(vm.vpsLog.any { "ОШИБКА" in it })
        assertTrue(vm.toolsLog.isEmpty())

        // в «Инструментах» без ключа защита по-прежнему не даёт начать
        javax.swing.SwingUtilities.invokeAndWait {
            vm.host = "127.0.0.1"; vm.sshPort = "1"; vm.useKey = false; vm.password = "secret"; vm.privateKey = ""
            vm.hardenBeforeInstall = true
            vm.install()
        }
        assertTrue(vm.errorMessage.orEmpty().startsWith("Режим безопасности"))
        assertFalse(vm.running)
    }

    /** Маскировка под свой сервер: без домена — понятная ошибка; домен не на этот сервер — VLESS не ставится, скрипты не запускаются. */
    @Test
    fun selfMaskNeedsDomainPointingToServer() {
        val data = tmp.newFolder()
        val vm = MainViewModel(DesktopPlatform(data))
        javax.swing.SwingUtilities.invokeAndWait {
            vm.vpsHost = "127.0.0.1"; vm.vpsPort = "1"; vm.vpsUser = "root"; vm.vpsUseKey = false; vm.vpsPassword = "secret"
            if (ServerMode.XHTTP !in vm.serverModes) vm.toggleServerMode(ServerMode.XHTTP)
            vm.vpsSelf = true; vm.vpsDomain = ""
            vm.vpsInstall()
        }
        assertTrue(vm.vpsError.orEmpty(), vm.vpsError.orEmpty().startsWith("Для маскировки под свой сервер впишите домен"))
        assertFalse(vm.running)

        javax.swing.SwingUtilities.invokeAndWait {
            vm.vpsDomain = "vps-test.invalid"
            vm.vpsInstall()
            assertEquals(null, vm.vpsError)
        }
        waitIdle(vm)
        val log = vm.vpsLog.joinToString("\n")
        assertTrue(log, log.contains("имя vps-test.invalid не найдено в DNS"))
        assertFalse(log, log.contains("Hysteria2: сертификат Let's Encrypt"))   // до установки дело не дошло

        // домен и переключатель запоминаются для сервера и возвращаются при выборе
        javax.swing.SwingUtilities.invokeAndWait {
            vm.vpsNew()
            assertEquals("", vm.vpsDomain)
            vm.vpsSelect(vm.vpsServers.single())
        }
        assertEquals("vps-test.invalid", vm.vpsDomain)
        assertTrue(vm.vpsSelf)
        assertEquals("vps-test.invalid", MainViewModel(DesktopPlatform(data)).vpsDomain)
    }
}
