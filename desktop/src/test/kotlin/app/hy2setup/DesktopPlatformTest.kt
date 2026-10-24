package app.hy2setup

import app.hy2setup.platform.DesktopPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopPlatformTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun settingsSurviveRestart() {
        val dir = tmp.newFolder()
        DesktopPlatform(dir).prefs.edit().putString("host", "203.0.113.10").putBoolean("useKey", true).putInt("monitorHours", 3).apply()
        val p = DesktopPlatform(dir).prefs
        assertEquals("203.0.113.10", p.getString("host", ""))
        assertTrue(p.getBoolean("useKey", false))
        assertEquals(3, p.getInt("monitorHours", 0))
        p.edit().remove("host").apply()
        assertEquals("нет", DesktopPlatform(dir).prefs.getString("host", "нет"))
    }

    @Test
    fun secretsAreEncryptedOnDisk() {
        val dir = tmp.newFolder()
        val secret = "очень-секретный-пароль-123"
        DesktopPlatform(dir).secrets.put("password", secret)
        val onDisk = File(dir, "secrets.properties").readText()
        assertFalse("пароль лежит открытым текстом", onDisk.contains(secret))
        assertEquals(secret, DesktopPlatform(dir).secrets.get("password"))
        // пустое значение удаляет запись
        DesktopPlatform(dir).secrets.put("password", "")
        assertEquals("", DesktopPlatform(dir).secrets.get("password"))
    }

    @Test
    fun clearRemovesAllSecrets() {
        val dir = tmp.newFolder()
        val s = DesktopPlatform(dir).secrets
        s.put("a", "1"); s.put("b", "2")
        s.clear()
        assertEquals("", s.get("a"))
        s.put("c", "3")
        assertEquals("", DesktopPlatform(dir).secrets.get("b"))
        assertEquals("3", DesktopPlatform(dir).secrets.get("c"))
    }

    /** Модель целиком на ПК: введённое сохраняется при persist() и читается при следующем запуске. */
    @Test
    fun viewModelPersistsAcrossLaunches() {
        val dir = tmp.newFolder()
        val vm = MainViewModel(DesktopPlatform(dir))
        vm.host = "203.0.113.10"; vm.password = "пароль-root"; vm.ncToken = "token-xyz"
        vm.persist()
        val again = MainViewModel(DesktopPlatform(dir))
        assertEquals("203.0.113.10", again.host)
        assertEquals("пароль-root", again.password)
        assertEquals("token-xyz", again.ncToken)
        assertFalse(again.monitorSupported)
        assertFalse(again.vpnSupported)
        assertFalse(File(dir, "secrets.properties").readText().contains("пароль-root"))
    }
}
