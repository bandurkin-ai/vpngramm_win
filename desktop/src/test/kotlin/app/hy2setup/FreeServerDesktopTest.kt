package app.hy2setup

import app.hy2setup.platform.DesktopPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** «Добавить сервер по IP»: любой сервер по адресу и паролю или SSH-ключу попадает в «Свободные серверы». */
class FreeServerDesktopTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dir = System.getenv("SSH_TEST_DIR")

    private fun ui(block: () -> Unit) = javax.swing.SwingUtilities.invokeAndWait(block)

    private fun waitIdle(vm: MainViewModel) {
        val end = System.currentTimeMillis() + 60_000
        Thread.sleep(200)
        while (vm.running && System.currentTimeMillis() < end) {
            if (vm.hostKeyPrompt != null) ui { vm.answerHostKey(true) }
            Thread.sleep(100)
        }
        assertFalse("задача не закончилась", vm.running)
    }

    private fun vmWithHome(data: File) = MainViewModel(DesktopPlatform(data)).also { vm ->
        ui { vm.plusProfile = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/").copy(vps = ProfileVps("123", "203.0.113.10", "vpn")) }
    }

    @Test
    fun serverByIpAndKeyBecomesFreeServer() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val data = tmp.newFolder()
        val vm = vmWithHome(data)
        var done = false
        ui {
            vm.importHost = "127.0.0.1"; vm.freePort = "2222"; vm.freeUser = "root"
            vm.freeUseKey = true; vm.freeKey = File(dir, "k_ed25519").readText()
            vm.addFreeServer { done = true }
            assertTrue(vm.freeBusy)
        }
        waitIdle(vm)
        assertNull(vm.plusLog.joinToString("\n"), vm.freeError)
        assertTrue(done)
        assertFalse(vm.freeBusy)
        val srv = vm.plusTargets.single()
        assertEquals("ip:127.0.0.1", srv.key)
        assertEquals(CombineServer.ORIGIN_MANUAL, srv.origin)
        assertTrue(srv.manual)
        assertNull(vm.combineServerAlive(srv))            // с OneProvider не сверяется
        assertEquals(srv, vm.plusTarget)                    // сразу выбран для переезда
        assertEquals(listOf("vpn.example.org"), vm.stableProfiles.map { it.fqdn })   // на Главную не попал
        assertTrue(vm.profiles.any { it.id == "root@127.0.0.1:2222" })
        assertEquals("", vm.importHost)                     // форма очищена
        assertEquals("", vm.freeKey)

        // после перезапуска сервер на месте; повторное добавление без пароля и ключа берёт сохранённые (и порт 2222)
        val again = MainViewModel(DesktopPlatform(data))
        assertEquals(listOf("ip:127.0.0.1"), again.plusTargets.map { it.key })
        ui { again.importHost = "127.0.0.1"; again.addFreeServer() }
        waitIdle(again)
        assertNull(again.plusLog.joinToString("\n"), again.freeError)
        assertEquals(1, again.plusTargets.size)

        // «убрать из списка» работает по ключу записи
        ui { again.forgetCombineServer(again.plusTargets.single()) }
        assertTrue(again.plusTargets.isEmpty())
        assertNull(again.plusTarget)
    }

    @Test
    fun badInputGivesClearErrors() {
        val vm = vmWithHome(tmp.newFolder())
        ui { vm.importHost = ""; vm.addFreeServer() }
        assertEquals("Укажите IP-адрес сервера, например 203.0.113.10.", vm.freeError)
        ui { vm.importHost = "198.51.100.7"; vm.freePort = "99999"; vm.addFreeServer() }
        assertEquals("SSH-порт должен быть числом 1–65535.", vm.freeError)
        assertFalse(vm.running)
        // сервер Главной свободным быть не может
        ui { vm.importHost = "203.0.113.10"; vm.freePort = "22"; vm.freePassword = "x"; vm.addFreeServer() }
        waitIdle(vm)
        assertTrue(vm.freeError.orEmpty(), vm.freeError.orEmpty().contains("уже работает сервер Главной"))
        assertTrue(vm.plusTargets.isEmpty())
        // нет ни пароля, ни ключа, ни сохранённых данных
        ui { vm.importHost = "198.51.100.7"; vm.freePassword = ""; vm.addFreeServer() }
        waitIdle(vm)
        assertTrue(vm.freeError.orEmpty(), vm.freeError.orEmpty().contains("не сохранены ни пароль, ни SSH-ключ"))
    }
}
