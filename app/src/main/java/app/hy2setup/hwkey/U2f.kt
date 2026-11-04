package app.hy2setup.hwkey

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Канал к токену для команд U2F (CTAP1): команда [ins] с параметром [p1] и данными [data]; ответ — данные и два
 * байта статуса (SW1 SW2) в конце. USB (CTAPHID) и NFC (ISO 7816) по-разному упаковывают команду — это их дело.
 */
interface HwTransport {
    fun send(ins: Int, p1: Int, data: ByteArray): ByteArray

    /** Команда CTAP2: байт команды и CBOR → байт статуса и CBOR. Токен только с U2F — [CtapUnsupported]. */
    fun cbor(request: ByteArray): ByteArray = throw CtapUnsupported()

    /** Название токена («YubiKey 5C Nano») — для списка ключей; пусто — неизвестно. */
    val name: String get() = ""
}

open class HwKeyException(message: String) : Exception(message)

/** Токен не понимает команды FIDO2 (CTAP2) — только U2F. */
class CtapUnsupported : HwKeyException("Токен не поддерживает FIDO2 (CTAP2).")

/** Команды U2F: регистрация ключа на токене и подпись (обе требуют касания токена). */
object U2f {
    const val INS_REGISTER = 0x01
    const val INS_AUTHENTICATE = 0x02
    const val INS_VERSION = 0x03
    private const val SW_OK = 0x9000
    /** Нужно касание (пользователь ещё не дотронулся до токена). */
    private const val SW_CONDITIONS = 0x6985
    /** Токен не знает этот key handle — это другой токен. */
    private const val SW_WRONG_DATA = 0x6A80

    /** Как долго ждать касания, мс. */
    var touchTimeoutMs = 30_000L

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun sw(resp: ByteArray): Int =
        if (resp.size < 2) -1 else ((resp[resp.size - 2].toInt() and 0xff) shl 8) or (resp[resp.size - 1].toInt() and 0xff)

    /** Повторяет команду, пока токен просит касания; возвращает данные ответа без статуса. */
    private fun untilTouched(t: HwTransport, ins: Int, p1: Int, data: ByteArray): ByteArray {
        val deadline = System.currentTimeMillis() + touchTimeoutMs
        while (true) {
            val r = t.send(ins, p1, data)
            when (val s = sw(r)) {
                SW_OK -> return r.copyOf(r.size - 2)
                SW_CONDITIONS -> {
                    if (System.currentTimeMillis() > deadline) throw HwKeyException("Не дождался касания Рутокена — попробуйте ещё раз и коснитесь токена.")
                    Thread.sleep(200)
                }
                SW_WRONG_DATA -> throw HwKeyException("Этот токен не знает ключ: приложите тот Рутокен, на котором ключ создавали.")
                else -> throw HwKeyException("Рутокен ответил ошибкой %04X.".format(s))
            }
        }
    }

    /** Создаёт на токене новый ключ для SSH (приложение «ssh:»). */
    fun register(t: HwTransport, application: String = SkKey.APPLICATION): HwKey {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val r = untilTouched(t, INS_REGISTER, 0x03, challenge + sha256(application.toByteArray()))
        return parseRegister(r, application, t.name)
    }

    /** Разбор ответа REGISTER: 0x05 | открытый ключ (65) | длина key handle | key handle | сертификат | подпись. */
    fun parseRegister(r: ByteArray, application: String = SkKey.APPLICATION, name: String = ""): HwKey {
        if (r.size < 67 || r[0].toInt() != 0x05 || r[1].toInt() != 0x04) throw HwKeyException("Непонятный ответ Рутокена при создании ключа.")
        val pub = r.copyOfRange(1, 66)
        val khLen = r[66].toInt() and 0xff
        if (khLen == 0 || r.size < 67 + khLen) throw HwKeyException("Непонятный ответ Рутокена при создании ключа.")
        return HwKey(pub, r.copyOfRange(67, 67 + khLen), application, name = name)
    }

    /** Подпись токеном над appParam | flags | counter | [clientDataHash]: ECDSA — DER, Ed25519 — 64 байта. */
    class Assertion(val flags: Int, val counter: Long, val der: ByteArray)

    fun authenticate(t: HwTransport, key: HwKey, clientDataHash: ByteArray): Assertion {
        val data = clientDataHash + sha256(key.application.toByteArray()) + byteArrayOf(key.keyHandle.size.toByte()) + key.keyHandle
        val r = untilTouched(t, INS_AUTHENTICATE, 0x03, data)
        if (r.size < 6) throw HwKeyException("Непонятный ответ Рутокена при подписи.")
        val counter = ((r[1].toLong() and 0xff) shl 24) or ((r[2].toLong() and 0xff) shl 16) or
            ((r[3].toLong() and 0xff) shl 8) or (r[4].toLong() and 0xff)
        return Assertion(r[0].toInt() and 0xff, counter, r.copyOfRange(5, r.size))
    }
}
