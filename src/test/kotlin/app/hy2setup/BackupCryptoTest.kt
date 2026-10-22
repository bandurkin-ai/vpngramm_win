package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    @Test
    fun roundTripAndWrongPassword() {
        val json = StableProfile.create("example.com", "vpn", "", "https://www.bing.com/").toJson()
        val enc = BackupCrypto.encrypt(json, "correct horse")
        assertTrue(enc.startsWith("HY2B1:"))
        assertTrue("секреты не видны в файле", !enc.contains("example.com"))
        assertEquals(json, BackupCrypto.decrypt(enc, "correct horse"))
        val e = runCatching { BackupCrypto.decrypt(enc, "wrong password") }.exceptionOrNull()
        assertTrue(e is BackupCrypto.WrongPassword)
        val e2 = runCatching { BackupCrypto.decrypt("hello", "x") }.exceptionOrNull()
        assertTrue(e2 is IllegalArgumentException)
    }
}
