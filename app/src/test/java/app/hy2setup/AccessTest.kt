package app.hy2setup

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** «Инструменты → Доступ по SSH»: правка данных входа на серверы Главной и «Применить». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessTest {
    private fun app() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun vmWithServer(ip: String = "203.0.113.10") = MainViewModel(app()).apply {
        plusProfile = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/").copy(vps = ProfileVps("123", ip, "vpn"))
        password = "общий-пароль"; persist()
    }

    @Test
    fun legacyServerUsesCommonPasswordUntilApplied() {
        val vm = vmWithServer()
        val ssh = vm.plusSsh()!!
        assertEquals("203.0.113.10", ssh.host); assertEquals(22, ssh.port); assertEquals("root", ssh.user)
        assertEquals("общий-пароль", ssh.password)
        // выбор сервера Главной подставляет его данные; правок нет — «Применить» недоступна
        vm.accessSelect("vpn.example.org")
        assertEquals("203.0.113.10", vm.host)
        assertEquals("общий-пароль", vm.password)
        assertFalse(vm.accessDirty)
    }

    @Test
    fun fixWrongIpPortUserAndPassword() {
        val vm = vmWithServer()
        vm.accessSelect("vpn.example.org")
        vm.host = "198.51.100.7"; vm.sshPort = "2200"; vm.user = "admin"; vm.password = "новый"
        assertTrue(vm.accessDirty)
        // до «Применить» Главная входит со старыми данными
        assertEquals("203.0.113.10", vm.plusSsh()!!.host)
        vm.accessApply()
        assertEquals(null, vm.errorMessage)
        assertFalse(vm.accessDirty)
        assertEquals("198.51.100.7", vm.plusProfile!!.vps!!.ip)
        assertEquals("123", vm.plusProfile!!.vps!!.id)   // VPS тот же, поменялся только адрес
        val ssh = vm.plusSsh()!!
        assertEquals(listOf("198.51.100.7", "2200", "admin", "новый"), listOf(ssh.host, ssh.port.toString(), ssh.user, ssh.password))

    }

    @Test
    fun badInputIsRejected() {
        val vm = vmWithServer()
        vm.accessSelect("vpn.example.org")
        vm.host = "vpn.example.org"   // серверу Главной нужен IP
        vm.accessApply()
        assertNotNull(vm.errorMessage)
        assertEquals("203.0.113.10", vm.plusProfile!!.vps!!.ip)
        vm.host = "198.51.100.7"; vm.sshPort = "99999"
        vm.accessApply()
        assertNotNull(vm.errorMessage)
        vm.accessRevert()
        assertEquals("203.0.113.10", vm.host); assertEquals("22", vm.sshPort)
        assertFalse(vm.accessDirty)
    }

    @Test
    fun editAccessFromHomeOpensTools() {
        val vm = vmWithServer()
        vm.accessSelect("")
        vm.editAccess("vpn.example.org")
        assertEquals(TAB_TOOLS, vm.selectedTab)
        assertEquals("vpn.example.org", vm.accessTarget)
        assertEquals("203.0.113.10", vm.host)
    }

    /** Сквозная проверка на sshd стенда: IP сервера Главной был неверный — исправили ключом и портом, вход работает. */
    @Test
    fun appliedAccessLogsInOnRealSshd() {
        val dir = System.getenv("SSH_TEST_DIR")
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val vm = vmWithServer()
        vm.accessSelect("vpn.example.org")
        vm.host = "127.0.0.1"; vm.sshPort = "2222"
        vm.useKey = true; vm.privateKey = File(dir, "k_ed25519").readText()
        vm.accessApply()
        assertEquals(null, vm.errorMessage)
        val ssh = vm.plusSsh()!!
        val known = File.createTempFile("known", "hosts").apply { delete() }
        val lines = mutableListOf<String>()
        val code = runBlocking { SshRunner(known) { true }.runScript(ssh, "#!/bin/bash\necho ACCESS-OK\n") { lines += it } }
        assertEquals(0, code)
        assertTrue(lines.contains("ACCESS-OK"))
    }
}
