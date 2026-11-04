package app.hy2setup.hwkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class TransportTest {
    /** Программный HID-токен: принимает пакеты CTAPHID, отвечает через [token], перед ответом шлёт KEEPALIVE. */
    private class FakeHid(val token: FakeU2fToken) {
        val outbox = ArrayDeque<ByteArray>()
        private val inbox = mutableListOf<ByteArray>()
        private val myCid = 0x01020304

        fun write(p: ByteArray) {
            assertEquals(CtapHid.PACKET, p.size)
            inbox += p
            val first = inbox.first()
            val len = ((first[5].toInt() and 0xff) shl 8) or (first[6].toInt() and 0xff)
            val have = minOf(len, 57) + (inbox.size - 1) * 59
            if (have < len) return
            val data = ByteArray(len)
            var got = minOf(len, 57); first.copyInto(data, 0, 7, 7 + got)
            inbox.drop(1).forEach { c -> val n = minOf(len - got, 59); c.copyInto(data, got, 5, 5 + n); got += n }
            inbox.clear()
            val cid = ((first[0].toInt() and 0xff) shl 24) or ((first[1].toInt() and 0xff) shl 16) or ((first[2].toInt() and 0xff) shl 8) or (first[3].toInt() and 0xff)
            when (first[4].toInt() and 0xff) {
                CtapHid.CMD_INIT -> outbox.addAll(CtapHid.frames(cid, CtapHid.CMD_INIT,
                    data + byteArrayOf(1, 2, 3, 4) + ByteArray(5)))
                CtapHid.CMD_MSG -> {
                    assertEquals(myCid, cid)
                    // расширенная APDU: 00 ins p1 00 00 Lc1 Lc2 data 00 00
                    val lc = ((data[5].toInt() and 0xff) shl 8) or (data[6].toInt() and 0xff)
                    val resp = token.send(data[1].toInt() and 0xff, data[2].toInt() and 0xff, data.copyOfRange(7, 7 + lc))
                    outbox.addAll(CtapHid.frames(cid, CtapHid.CMD_KEEPALIVE, byteArrayOf(2)))
                    outbox.addAll(CtapHid.frames(0x0BADBEEF, CtapHid.CMD_MSG, byteArrayOf(0x6F, 0)))   // чужой канал — пропустить
                    outbox.addAll(CtapHid.frames(cid, CtapHid.CMD_MSG, resp))
                }
            }
        }
    }

    @Test
    fun framesSplitAndJoin() {
        val payload = ByteArray(300) { it.toByte() }
        val f = CtapHid.frames(7, CtapHid.CMD_MSG, payload)
        assertEquals(1 + 5, f.size)            // 57 + 5×59 ≥ 300
        val q = ArrayDeque(f)
        val (cmd, data) = CtapHid.readMessage(7) { q.poll() }
        assertEquals(CtapHid.CMD_MSG, cmd)
        assertTrue(payload.contentEquals(data))
    }

    @Test
    fun u2fOverUsbHid() {
        val token = FakeU2fToken(touchesNeeded = 1)
        val hid = FakeHid(token)
        val t = CtapHid.Transport({ hid.write(it) }, { hid.outbox.poll() })
        val key = U2f.register(t)
        val a = U2f.authenticate(t, key, ByteArray(32) { 7 })
        assertEquals(1, a.flags)
        assertTrue(a.der.isNotEmpty())
    }

    @Test
    fun u2fOverNfcWithChainedResponse() {
        val token = FakeU2fToken()
        var selected = false
        val pending = ArrayDeque<Byte>()
        val transceive: (ByteArray) -> ByteArray = { apdu ->
            when {
                apdu[1] == 0xA4.toByte() -> { selected = apdu.copyOfRange(5, 13).contentEquals(NfcU2f.AID); "U2F_V2".toByteArray() + byteArrayOf(0x90.toByte(), 0) }
                apdu[1] == 0xC0.toByte() -> chunk(pending)
                else -> {
                    assertTrue(selected)
                    val lc = apdu[4].toInt() and 0xff
                    token.send(apdu[1].toInt(), apdu[2].toInt(), apdu.copyOfRange(5, 5 + lc)).forEach { pending.add(it) }
                    chunk(pending)
                }
            }
        }
        val t = NfcU2f.Transport(transceive)
        val key = U2f.register(t)
        assertEquals(65, key.publicKey.size)
        assertEquals(1, U2f.authenticate(t, key, ByteArray(32)).flags)
    }

    /** Отдаёт ответ кусками по 40 байт с SW 61xx, как токен с коротким буфером NFC. */
    private fun chunk(q: ArrayDeque<Byte>): ByteArray {
        if (q.size <= 42) return ByteArray(q.size) { q.poll()!! }
        val part = ByteArray(40) { q.poll()!! }
        val rest = q.size - 2
        return part + byteArrayOf(0x61, minOf(rest, 255).toByte())
    }

    @Test
    fun nonFidoTagIsRejected() {
        val e = runCatching { NfcU2f.Transport({ byteArrayOf(0x6A, 0x82.toByte()) }) }.exceptionOrNull()
        assertTrue("$e", e is HwKeyException)
    }
}
