package app.hy2setup.hwkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class HwKeyTest {
    @Test
    fun registerAndJsonRoundTrip() {
        val token = FakeU2fToken(touchesNeeded = 1)
        val key = U2f.register(token)
        assertEquals(2, token.calls)           // первая попытка — «коснитесь», вторая — ключ
        assertEquals(65, key.publicKey.size)
        assertEquals(key, HwKey.fromJson(key.toJson()))
        val line = key.authorizedKeysLine()
        assertTrue(line, line.startsWith("sk-ecdsa-sha2-nistp256@openssh.com AAAA") && line.endsWith(" vpngramm-hwkey"))
        assertTrue(key.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun wrongTokenIsExplained() {
        val key = U2f.register(FakeU2fToken())
        val e = runCatching { U2f.authenticate(FakeU2fToken(), key, ByteArray(32)) }.exceptionOrNull()
        assertTrue("$e", e is HwKeyException && e.message!!.contains("тот Рутокен"))
    }

    @Test
    fun touchTimeout() {
        U2f.touchTimeoutMs = 300
        try {
            val e = runCatching { U2f.register(FakeU2fToken(touchesNeeded = 1000)) }.exceptionOrNull()
            assertTrue("$e", e is HwKeyException && e.message!!.contains("касания"))
        } finally {
            U2f.touchTimeoutMs = 30_000
        }
    }

    @Test
    fun derParsing() {
        val r = BigInteger("00ff01", 16); val s = BigInteger("7f", 16)
        val der = byteArrayOf(0x30, 0x08, 0x02, 0x03, 0x00, 0xff.toByte(), 0x01, 0x02, 0x01, 0x7f)
        assertEquals(r to s, SkKey.derToRs(der))
    }
}
