package app.hy2setup.hwkey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ctap2Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun cborRoundTripAndCanonicalOrder() {
        val m = mapOf(3 to listOf(mapOf("type" to "public-key", "alg" to -8)), 1 to ByteArray(2), 2 to mapOf("id" to "ssh:"), -1 to true)
        val back = Cbor.decode(Cbor.encode(m)) as Map<*, *>
        assertEquals("ssh:", (back.at(2) as Map<*, *>)["id"])
        assertEquals(-8L, (((back.at(3) as List<*>)[0]) as Map<*, *>)["alg"])
        // канонический порядок: 1, 2, 3, затем -1 (кодировка 0x20 длиннее нет, но байт больше)
        assertEquals(listOf(1L, 2L, 3L, -1L), back.keys.toList())
        // «alg» (3 символа) раньше «type» (4)
        assertTrue(Cbor.encode(mapOf("type" to 1, "alg" to 2)).let { it[1] == 0x63.toByte() })
        assertArrayEquals(hex("1903e8"), Cbor.encode(1000))
        assertArrayEquals(hex("3863"), Cbor.encode(-100))
    }

    @Test
    fun hkdfKnownAnswer() {
        // RFC 5869, тест 3: соль — нули, info пустой
        val okm = Ctap2.PinProtocol.hkdf(ByteArray(22) { 0x0b }, "")
        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d"), okm)
    }

    @Test
    fun yubikeyCreatesEd25519WithoutPin() {
        val t = FakeFidoToken(pin = "123456")
        val key = Hw.create(t, resident = false) { error("PIN не должен спрашиваться") }
        assertTrue(key.ed25519)
        assertEquals(32, key.publicKey.size)
        assertTrue(key.authorizedKeysLine().startsWith("sk-ssh-ed25519@openssh.com AAAA"))
        assertEquals(1, Hw.sign(t, key, ByteArray(32)).flags)
        assertEquals(64, Hw.sign(t, key, ByteArray(32)).der.size)
    }

    @Test
    fun rutokenCreatesEcdsa() {
        val t = FakeFidoToken(ed25519 = false, name = "Rutoken MFA")
        val key = Hw.create(t, resident = false) { null }
        assertFalse(key.ed25519)
        assertEquals("Rutoken MFA", key.name)
        assertTrue(key.title.contains("ecdsa") && key.title.contains("нерезидентный"))
    }

    @Test
    fun residentAsksPinOnceBothProtocols() {
        val t = FakeFidoToken(pin = "2468")
        var asked = 0
        val key = Hw.create(t, resident = true) { asked++; "2468" }
        assertEquals(1, asked)
        assertTrue(key.resident)
        // вход — без PIN
        Hw.sign(t, key, ByteArray(32))
        assertEquals(1, t.pinRequests)
        // загрузка с токена (новый телефон)
        val loaded = Hw.loadResident(t) { "2468" }
        assertEquals(listOf(key.fingerprint), loaded.map { it.fingerprint })
        assertTrue(loaded.single().resident)
    }

    @Test
    fun pinProtocolOne() {
        val p1 = Ctap2.PinProtocol.fromZ(1, ByteArray(32) { 1 })
        val data = ByteArray(32) { it.toByte() }
        assertArrayEquals(data, p1.decrypt(p1.encrypt(data)))
        assertEquals(16, p1.authenticate(ByteArray(32), byteArrayOf(1)).size)
        val p2 = Ctap2.PinProtocol.fromZ(2, ByteArray(32) { 1 })
        assertEquals(48, p2.encrypt(data).size)        // IV + шифротекст
        assertEquals(32, p2.authenticate(ByteArray(32), byteArrayOf(1)).size)
    }

    @Test
    fun wrongPinAndNoPinExplained() {
        val e1 = runCatching { Hw.create(FakeFidoToken(pin = "1111"), resident = true) { "0000" } }.exceptionOrNull()
        assertTrue("$e1", e1 is CtapError && e1.code == Ctap2.ERR_PIN_INVALID)
        val e2 = runCatching { Hw.create(FakeFidoToken(pin = null), resident = true) { null } }.exceptionOrNull()
        assertTrue("$e2", e2 is CtapError && e2.message!!.contains("задайте PIN"))
    }

    @Test
    fun ctap20TokenWithPinFallsBackToU2fForEcdsa() {
        val t = FakeFidoToken(pin = "1234", ed25519 = false, strictPin = true)
        val key = Hw.create(t, resident = false) { error("PIN не нужен — U2F") }
        assertEquals(0, t.pinRequests)
        assertEquals(1, Hw.sign(t, key, ByteArray(32)).flags)
    }

    @Test
    fun u2fOnlyTokenAndOldKeysStillWork() {
        val u2f = FakeU2fToken()
        val key = Hw.create(u2f, resident = false) { null }     // только U2F → ecdsa через U2F
        assertEquals(1, Hw.sign(u2f, key, ByteArray(32)).flags)
        // ключ из 3.8 (U2F) на FIDO2-токене подписывается через CTAP2
        val fido = FakeFidoToken()
        val old = U2f.register(fido)
        assertEquals(1, Hw.sign(fido, old, ByteArray(32)).flags)
        // старая запись в настройках — одиночный ключ
        assertEquals(listOf(old), HwKey.listFromJson(old.toJson()))
        assertEquals(listOf(old, key), HwKey.listFromJson(HwKey.listToJson(listOf(old, key))))
    }
}
