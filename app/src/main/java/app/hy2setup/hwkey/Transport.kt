package app.hy2setup.hwkey

import java.security.SecureRandom

/**
 * Упаковка команд U2F для двух способов связи с токеном (общая часть, без Android):
 * USB — пакеты CTAPHID по 64 байта, NFC — короткие APDU ISO 7816 с добором ответа (61xx).
 */
object CtapHid {
    const val PACKET = 64
    const val BROADCAST = -1          // канал 0xFFFFFFFF
    const val CMD_MSG = 0x83
    const val CMD_CBOR = 0x90
    const val CMD_INIT = 0x86
    const val CMD_KEEPALIVE = 0xBB
    const val CMD_ERROR = 0xBF

    /** Команда U2F в «сыром» виде для CTAPHID_MSG: расширенная длина (CLA INS P1 P2 00 Lc1 Lc2 data 00 00). */
    fun extendedApdu(ins: Int, p1: Int, data: ByteArray): ByteArray =
        byteArrayOf(0, ins.toByte(), p1.toByte(), 0, 0, (data.size shr 8).toByte(), data.size.toByte()) + data + byteArrayOf(0, 0)

    private fun cidBytes(cid: Int) = byteArrayOf((cid shr 24).toByte(), (cid shr 16).toByte(), (cid shr 8).toByte(), cid.toByte())

    /** Разбивает сообщение на пакеты: первый — канал, команда, длина и 57 байт; следующие — канал, номер и 59 байт. */
    fun frames(cid: Int, cmd: Int, payload: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val first = ByteArray(PACKET)
        cidBytes(cid).copyInto(first, 0)
        first[4] = (cmd or 0x80).toByte()
        first[5] = (payload.size shr 8).toByte(); first[6] = payload.size.toByte()
        val n0 = minOf(payload.size, PACKET - 7)
        payload.copyInto(first, 7, 0, n0)
        out += first
        var off = n0; var seq = 0
        while (off < payload.size) {
            val p = ByteArray(PACKET)
            cidBytes(cid).copyInto(p, 0)
            p[4] = seq++.toByte()
            val n = minOf(payload.size - off, PACKET - 5)
            payload.copyInto(p, 5, off, off + n)
            out += p; off += n
        }
        return out
    }

    /**
     * Читает ответ на канале [cid]: пропускает KEEPALIVE («жду касания»), собирает продолжения.
     * Возвращает (команда, данные). [read] отдаёт очередной пакет.
     */
    fun readMessage(cid: Int, read: () -> ByteArray): Pair<Int, ByteArray> {
        while (true) {
            val p = read()
            if (p.size < 7 || cidOf(p) != cid) continue
            val cmd = p[4].toInt() and 0xff
            if (cmd == CMD_KEEPALIVE) continue
            val len = ((p[5].toInt() and 0xff) shl 8) or (p[6].toInt() and 0xff)
            val data = ByteArray(len)
            var got = minOf(len, p.size - 7)
            p.copyInto(data, 0, 7, 7 + got)
            var seq = 0
            while (got < len) {
                val c = read()
                if (cidOf(c) != cid) continue
                if ((c[4].toInt() and 0xff) != seq) throw HwKeyException("Рутокен: сбой обмена по USB (пакет ${c[4]} вместо $seq).")
                seq++
                val n = minOf(len - got, c.size - 5)
                c.copyInto(data, got, 5, 5 + n); got += n
            }
            if (cmd == CMD_ERROR) {
                val code = data.firstOrNull()?.toInt()?.and(0xff)
                if (code == 0x01) throw CtapUnsupported()      // команда не поддерживается (токен только U2F)
                throw HwKeyException("Токен: ошибка USB-обмена $code.")
            }
            return cmd to data
        }
    }

    private fun cidOf(p: ByteArray) =
        ((p[0].toInt() and 0xff) shl 24) or ((p[1].toInt() and 0xff) shl 16) or ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff)

    /** Получает канал у токена: INIT с 8 случайными байтами, в ответе те же байты и новый канал. */
    fun init(write: (ByteArray) -> Unit, read: () -> ByteArray): Int {
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        frames(BROADCAST, CMD_INIT, nonce).forEach(write)
        while (true) {
            val (cmd, data) = readMessage(BROADCAST, read)
            if (cmd == CMD_INIT && data.size >= 12 && data.copyOfRange(0, 8).contentEquals(nonce)) {
                return ((data[8].toInt() and 0xff) shl 24) or ((data[9].toInt() and 0xff) shl 16) or
                    ((data[10].toInt() and 0xff) shl 8) or (data[11].toInt() and 0xff)
            }
        }
    }

    /** Канал U2F поверх готовых функций записи и чтения 64-байтных пакетов HID. */
    class Transport(
        private val write: (ByteArray) -> Unit,
        private val read: () -> ByteArray,
        override val name: String = "",
    ) : HwTransport {
        private val cid = init(write, read)
        override fun send(ins: Int, p1: Int, data: ByteArray): ByteArray = exchange(CMD_MSG, extendedApdu(ins, p1, data))
        override fun cbor(request: ByteArray): ByteArray = exchange(CMD_CBOR, request)

        private fun exchange(cmd: Int, payload: ByteArray): ByteArray {
            frames(cid, cmd, payload).forEach(write)
            val (got, resp) = readMessage(cid, read)
            if (got != cmd) throw HwKeyException("Токен: неожиданный ответ по USB.")
            return resp
        }
    }
}

object NfcU2f {
    /** AID приложения FIDO на NFC-токенах. */
    val AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x06, 0x47, 0x2F, 0x00, 0x01)

    fun select(): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    /** Короткая APDU: CLA INS P1 P2 Lc data Le(00). Данные U2F укладываются в 255 байт. */
    fun shortApdu(ins: Int, p1: Int, data: ByteArray): ByteArray =
        byteArrayOf(0x00, ins.toByte(), p1.toByte(), 0x00, data.size.toByte()) + data + byteArrayOf(0x00)

    /** Отправка с добором длинного ответа: пока SW1 = 61, просим GET RESPONSE. */
    fun exchange(transceive: (ByteArray) -> ByteArray, apdu: ByteArray): ByteArray {
        var r = transceive(apdu)
        var acc = ByteArray(0)
        while (r.size >= 2 && (r[r.size - 2].toInt() and 0xff) == 0x61) {
            acc += r.copyOf(r.size - 2)
            r = transceive(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, r[r.size - 1]))
        }
        return acc + r
    }

    /** CTAP2 по NFC: NFCCTAP_MSG (80 10) с расширенной длиной; 9100 — токен ещё работает, спрашиваем NFCCTAP_GETRESPONSE. */
    fun ctap2(transceive: (ByteArray) -> ByteArray, request: ByteArray): ByteArray {
        val n = request.size
        var r = exchange(transceive, byteArrayOf(0x80.toByte(), 0x10, 0x00, 0x00, 0x00, (n shr 8).toByte(), n.toByte()) + request + byteArrayOf(0, 0))
        while (r.size >= 2 && r[r.size - 2] == 0x91.toByte() && r[r.size - 1] == 0x00.toByte()) {
            Thread.sleep(100)
            r = exchange(transceive, byteArrayOf(0x80.toByte(), 0x11, 0x00, 0x00, 0x00))
        }
        val sw = if (r.size >= 2) ((r[r.size - 2].toInt() and 0xff) shl 8) or (r[r.size - 1].toInt() and 0xff) else -1
        if (sw == 0x6D00 || sw == 0x6E00) throw CtapUnsupported()
        if (sw != 0x9000) throw HwKeyException("Токен по NFC ответил ошибкой %04X.".format(sw))
        return r.copyOf(r.size - 2)
    }

    /** Канал U2F/FIDO2 поверх NFC (IsoDep): сначала выбираем приложение FIDO. */
    class Transport(private val transceive: (ByteArray) -> ByteArray, override val name: String = "NFC-токен") : HwTransport {
        init {
            val r = exchange(transceive, select())
            if (r.size < 2 || r[r.size - 2] != 0x90.toByte() || r[r.size - 1] != 0x00.toByte()) {
                throw HwKeyException("Это не FIDO-токен или на нём нет приложения U2F.")
            }
        }
        override fun send(ins: Int, p1: Int, data: ByteArray): ByteArray = exchange(transceive, shortApdu(ins, p1, data))
        override fun cbor(request: ByteArray): ByteArray = ctap2(transceive, request)
    }
}
