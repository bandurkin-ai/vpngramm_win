package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val srv = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/")
    private val settings = BackupSettings(
        host = "203.0.113.10", user = "root", useKey = true, password = "rootpw",
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----", passphrase = "pp",
        zone = "example.org", subdomain = "vpn1", ncUser = "mike", ncToken = "nctok", opApiKey = "api", opClientKey = "client",
        servers = listOf(SavedServer(ServerProfile("198.51.100.1", 22, "root", true), "pw1", "KEY1", "")),
    )
    private val data = BackupData(listOf(srv), settings)

    @Test
    fun plainRoundTripKeepsEverything() {
        val text = Backup.encode(data, "")
        assertTrue(text.startsWith(Backup.PLAIN))
        assertFalse(Backup.isEncrypted(text))
        assertEquals(data, Backup.decode(text))
    }

    @Test
    fun encryptedNeedsRightPassword() {
        val text = Backup.encode(data, "correct horse")
        assertTrue(Backup.isEncrypted(text))
        assertFalse(text.contains("nctok"))
        assertEquals(data, Backup.decode(text, "correct horse"))
        try {
            Backup.decode(text, "wrong password")
            throw AssertionError("должен быть неверный пароль")
        } catch (e: BackupCrypto.WrongPassword) {
        }
    }

    @Test
    fun oldFormatsStillOpen() {
        val one = BackupCrypto.encrypt(srv.toJson(), "12345678")
        assertEquals(BackupData(listOf(srv), null), Backup.decode(one, "12345678"))
        val list = BackupCrypto.encrypt(ProfileImport.encodeList(listOf(srv, srv.copy(subdomain = "b"))), "12345678")
        val d = Backup.decode(list, "12345678")
        assertEquals(2, d.servers.size)
        assertNull(d.settings)
    }

    @Test
    fun fingerprintFollowsSettingsButNotChecks() {
        val f = Backup.fingerprint(data)
        assertEquals(f, Backup.fingerprint(data.copy(servers = listOf(srv.copy(checkAt = 9, trafficGb = 1.0)))))
        assertNotEquals(f, Backup.fingerprint(data.copy(settings = settings.copy(opApiKey = "other"))))
    }

    @Test
    fun serverFingerprintIgnoresMovesAndChecks() {
        val p = StableProfile.create("example.org", "vpn1", "", "https://www.bing.com/")
        val fp = Backup.serverFingerprint(p)
        // переезд, проверка, трафик и продление сертификата — копия по-прежнему свежая
        val moved = p.copy(vps = ProfileVps("9", "203.0.113.9", "x"), movedAt = 5, checkAt = 7, status = mapOf("VLESS" to true), trafficGb = 1.5,
            cert = CertBackup("crt", "key", "", 1))
        assertEquals(fp, Backup.serverFingerprint(moved))
        // новый человек или новые ключи — устарела
        assertNotEquals(fp, Backup.serverFingerprint(p.copy(users = listOf(ProfileUser.create("Мама", emptyList())))))
        assertNotEquals(fp, Backup.serverFingerprint(p.copy(hy2Auth = "other")))
        // отпечатки по доменам сохраняются и читаются
        val saved = mapOf(p.fqdn to fp)
        assertEquals(saved, Backup.decodeSaved(Backup.encodeSaved(saved)))
        assertEquals(emptyMap<String, String>(), Backup.decodeSaved("oops"))
    }
}
