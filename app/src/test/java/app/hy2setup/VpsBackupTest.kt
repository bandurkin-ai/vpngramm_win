package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Чужие серверы вкладки «VPS» уходят в копию отдельно от своих. */
class VpsBackupTest {
    @Test
    fun vpsServersTravelInBackup() {
        val s = BackupSettings(
            servers = listOf(SavedServer(ServerProfile("203.0.113.10", 22, "root", false), "own", "", "")),
            vpsServers = listOf(
                SavedServer(ServerProfile("198.51.100.7", 2200, "admin", true), "sudo", "KEY", "pp", domain = "vpn.example.net"),
                SavedServer(ServerProfile("198.51.100.8", 22, "root", false), "p", "", "", self = false),
            ),
        )
        val back = BackupSettings.fromJson(s.toJson())
        assertEquals(s.servers, back.servers)
        assertEquals(s.vpsServers, back.vpsServers)
        assertFalse(BackupSettings().toJson().has("vpsServers"))
        assertEquals(emptyList<SavedServer>(), BackupSettings.fromJson(BackupSettings().toJson()).vpsServers)
    }
}
