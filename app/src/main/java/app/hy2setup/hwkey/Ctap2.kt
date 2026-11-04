package app.hy2setup.hwkey

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Ошибка токена FIDO2 с кодом CTAP2. */
class CtapError(val code: Int, message: String) : HwKeyException(message)

/**
 * FIDO2 (CTAP 2.0/2.1) — то, что нужно для SSH: сведения о токене, создание ключа (в том числе резидентного),
 * подпись касанием (без PIN), PIN-протоколы 1 и 2 (только для создания резидентного ключа и чтения ключей с токена)
 * и перечисление резидентных ключей «ssh:».
 */
object Ctap2 {
    const val ES256 = -7
    const val EDDSA = -8

    const val MAKE_CREDENTIAL = 0x01
    const val GET_ASSERTION = 0x02
    const val GET_INFO = 0x04
    const val CLIENT_PIN = 0x06
    const val CRED_MGMT = 0x0A
    const val CRED_MGMT_PRE = 0x41

    const val ERR_INVALID_COMMAND = 0x01
    const val ERR_NO_CREDENTIALS = 0x2E
    const val ERR_PIN_INVALID = 0x31
    const val ERR_PIN_BLOCKED = 0x32
    const val ERR_PIN_AUTH_BLOCKED = 0x34
    const val ERR_PIN_NOT_SET = 0x35
    const val ERR_PUAT_REQUIRED = 0x36

    /** Права токена доступа PIN (CTAP 2.1): создание ключа и управление ключами. */
    const val PERM_MC = 0x01
    const val PERM_CM = 0x04

    private fun message(code: Int) = when (code) {
        0x26 -> "Токен не поддерживает этот алгоритм ключа."
        0x27 -> "Токен отказал (операция отменена на токене)."
        0x28 -> "В токене нет места для нового резидентного ключа — удалите ненужные в программе производителя."
        0x2B -> "Токен не поддерживает этот режим."
        ERR_NO_CREDENTIALS -> "На токене нет этого ключа: вставьте тот токен, на котором ключ создавали."
        0x2F -> "Не дождался касания токена — попробуйте ещё раз и коснитесь токена."
        ERR_PIN_INVALID -> "Неверный PIN токена."
        ERR_PIN_BLOCKED -> "PIN токена заблокирован — сбросьте его в программе производителя."
        ERR_PIN_AUTH_BLOCKED -> "Слишком много неверных PIN подряд: выньте токен и вставьте снова."
        ERR_PIN_NOT_SET -> "На токене не задан PIN, а для резидентного ключа он нужен: задайте PIN в Yubico Authenticator или Рутокен MFA Manager."
        ERR_PUAT_REQUIRED -> "Токен требует PIN для этой операции."
        else -> "Токен ответил ошибкой FIDO2 0x%02X.".format(code)
    }

    /** Команда CTAP2: ответ — словарь CBOR (пустой, если данных нет). */
    fun call(t: HwTransport, cmd: Int, params: Map<Int, Any?>?): Map<*, *> {
        val r = t.cbor(byteArrayOf(cmd.toByte()) + (params?.let { Cbor.encode(it) } ?: ByteArray(0)))
        if (r.isEmpty()) throw HwKeyException("Пустой ответ токена.")
        val status = r[0].toInt() and 0xff
        if (status != 0) throw CtapError(status, message(status))
        return if (r.size == 1) emptyMap<Any, Any>() else Cbor.decode(r.copyOfRange(1, r.size)) as? Map<*, *>
            ?: throw HwKeyException("Непонятный ответ токена.")
    }

    class Info(
        val options: Map<String, Boolean>,
        val pinProtocols: List<Int>,
        /** Поддерживаемые алгоритмы; null — токен не сообщает (тогда только ES256). */
        val algorithms: List<Int>?,
        val extensions: List<String>,
    ) {
        val supportsEd25519: Boolean get() = algorithms?.contains(EDDSA) == true
        val pinSet: Boolean get() = options["clientPin"] == true
    }

    fun info(t: HwTransport): Info {
        val m = call(t, GET_INFO, null)
        @Suppress("UNCHECKED_CAST")
        val opts = (m.at(4) as? Map<*, *>)?.entries?.associate { it.key.toString() to (it.value == true) } ?: emptyMap()
        return Info(
            opts,
            (m.at(6) as? List<*>)?.mapNotNull { (it as? Long)?.toInt() }.orEmpty(),
            (m.at(10) as? List<*>)?.mapNotNull { ((it as? Map<*, *>)?.get("alg") as? Long)?.toInt() },
            (m.at(2) as? List<*>)?.map { it.toString() }.orEmpty(),
        )
    }

    /** Открытый ключ из COSE: EC2 P-256 → 0x04|X|Y, OKP Ed25519 → 32 байта. Возвращает (алгоритм, ключ). */
    fun coseKey(m: Map<*, *>): Pair<Int, ByteArray> = when ((m.at(1) as? Long)?.toInt()) {
        2 -> ES256 to (byteArrayOf(4) + pad32(m.at(-2) as ByteArray) + pad32(m.at(-3) as ByteArray))
        1 -> EDDSA to (m.at(-2) as ByteArray)
        else -> throw HwKeyException("Токен вернул ключ неизвестного типа.")
    }

    private fun pad32(b: ByteArray) = if (b.size >= 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b

    /** Создание ключа «ssh:» (как ssh-keygen -t ed25519-sk / ecdsa-sk; резидентный — как с -O resident). */
    fun makeCredential(t: HwTransport, info: Info, alg: Int, resident: Boolean, pin: PinToken?, application: String = SkKey.APPLICATION): HwKey {
        val cdh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val params = linkedMapOf<Int, Any?>(
            1 to cdh,
            2 to mapOf("id" to application),
            3 to mapOf("id" to ByteArray(32), "name" to "openssh"),
            4 to listOf(mapOf("alg" to alg, "type" to "public-key")),
        )
        if (resident && "credProtect" in info.extensions) params[6] = mapOf("credProtect" to 2)
        if (resident) params[7] = mapOf("rk" to true)
        if (pin != null) { params[8] = pin.authenticate(cdh); params[9] = pin.protocol.version }
        val r = call(t, MAKE_CREDENTIAL, params)
        val auth = r.at(2) as? ByteArray ?: throw HwKeyException("Токен не вернул данные ключа.")
        if (auth.size < 55 || (auth[32].toInt() and 0x40) == 0) throw HwKeyException("Токен не вернул данные ключа.")
        val idLen = ((auth[53].toInt() and 0xff) shl 8) or (auth[54].toInt() and 0xff)
        val credId = auth.copyOfRange(55, 55 + idLen)
        val (a, pub) = coseKey(Cbor.Reader(auth, 55 + idLen).read() as Map<*, *>)
        return HwKey(pub, credId, application, a, resident, t.name)
    }

    /** Подпись касанием (без PIN): токен подписывает authData | [clientDataHash]. */
    fun getAssertion(t: HwTransport, key: HwKey, clientDataHash: ByteArray): U2f.Assertion {
        val r = call(t, GET_ASSERTION, linkedMapOf(
            1 to key.application,
            2 to clientDataHash,
            3 to listOf(mapOf("id" to key.keyHandle, "type" to "public-key")),
            5 to mapOf("up" to true),
        ))
        val auth = r.at(2) as? ByteArray ?: throw HwKeyException("Токен не вернул подпись.")
        val sig = r.at(3) as? ByteArray ?: throw HwKeyException("Токен не вернул подпись.")
        if (auth.size < 37) throw HwKeyException("Непонятный ответ токена при подписи.")
        val counter = (33..36).fold(0L) { acc, i -> (acc shl 8) or (auth[i].toLong() and 0xff) }
        return U2f.Assertion(auth[32].toInt() and 0xff, counter, sig)
    }

    // ---------------- PIN ----------------

    /** PIN-протокол CTAP2: общий секрет с токеном (ECDH P-256) и шифрование PIN и токена доступа. */
    class PinProtocol(val version: Int, private val shared: ByteArray) {
        private val aesKey = if (version == 1) shared else shared.copyOfRange(32, 64)

        fun encrypt(pt: ByteArray): ByteArray {
            val iv = if (version == 1) ByteArray(16) else ByteArray(16).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv)) }
            val ct = c.doFinal(pt)
            return if (version == 1) ct else iv + ct
        }

        fun decrypt(data: ByteArray): ByteArray {
            val (iv, ct) = if (version == 1) ByteArray(16) to data else data.copyOfRange(0, 16) to data.copyOfRange(16, data.size)
            return Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv)) }.doFinal(ct)
        }

        fun authenticate(key: ByteArray, msg: ByteArray): ByteArray {
            val k = if (version == 2 && key.size == 64) key.copyOfRange(0, 32) else key
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(msg)
            return if (version == 1) mac.copyOf(16) else mac
        }

        companion object {
            fun hkdf(ikm: ByteArray, info: String): ByteArray {
                val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }.doFinal(ikm)
                return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }.doFinal(info.toByteArray() + byteArrayOf(1))
            }

            /** Общий секрет из x-координаты ECDH: протокол 1 — SHA-256(Z), протокол 2 — ключи HMAC и AES через HKDF. */
            fun fromZ(version: Int, z: ByteArray): PinProtocol =
                if (version == 1) PinProtocol(1, MessageDigest.getInstance("SHA-256").digest(z))
                else PinProtocol(2, hkdf(z, "CTAP2 HMAC key") + hkdf(z, "CTAP2 AES key"))
        }
    }

    /** Токен доступа, полученный по PIN: подписывает параметры команд (pinUvAuthParam). */
    class PinToken(val protocol: PinProtocol, private val token: ByteArray) {
        fun authenticate(msg: ByteArray): ByteArray = protocol.authenticate(token, msg)
    }

    /** ECDH с ключом токена: (наш открытый ключ в COSE, x-координата общего секрета). */
    fun ecdh(peer: Map<*, *>): Pair<Map<Int, Any>, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        val params = (kp.public as ECPublicKey).params
        val peerKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(BigInteger(1, peer.at(-2) as ByteArray), BigInteger(1, peer.at(-3) as ByteArray)), params),
        )
        val z = KeyAgreement.getInstance("ECDH").apply { init(kp.private); doPhase(peerKey, true) }.generateSecret()
        val w = (kp.public as ECPublicKey).w
        fun c(v: BigInteger) = pad32(v.toByteArray())
        return mapOf(1 to 2, 3 to -25, -1 to 1, -2 to c(w.affineX), -3 to c(w.affineY)) to pad32(z)
    }

    /** Токен доступа по PIN с правами [permissions] (CTAP 2.1) или общий (CTAP 2.0). */
    fun pinToken(t: HwTransport, info: Info, pin: String, permissions: Int, rpId: String?): PinToken {
        val version = if (2 in info.pinProtocols) 2 else 1
        val ka = call(t, CLIENT_PIN, linkedMapOf(1 to version, 2 to 2)).at(1) as? Map<*, *> ?: throw HwKeyException("Токен не дал ключ для PIN.")
        val (mine, z) = ecdh(ka)
        val proto = PinProtocol.fromZ(version, z)
        val pinHashEnc = proto.encrypt(MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8)).copyOf(16))
        val params = if (info.options["pinUvAuthToken"] == true) linkedMapOf<Int, Any?>(1 to version, 2 to 9, 3 to mine, 6 to pinHashEnc, 9 to permissions)
            .also { if (rpId != null) it[10] = rpId }
        else linkedMapOf(1 to version, 2 to 5, 3 to mine, 6 to pinHashEnc)
        val enc = call(t, CLIENT_PIN, params).at(2) as? ByteArray ?: throw HwKeyException("Токен не выдал доступ по PIN.")
        return PinToken(proto, proto.decrypt(enc))
    }

    // ---------------- резидентные ключи ----------------

    /** Резидентные ключи «ssh:» на токене (нужен PIN). */
    fun residentKeys(t: HwTransport, pin: PinToken, application: String = SkKey.APPLICATION): List<HwKey> {
        val sub = linkedMapOf<Int, Any?>(1 to MessageDigest.getInstance("SHA-256").digest(application.toByteArray()))
        val begin = linkedMapOf<Int, Any?>(1 to 4, 2 to sub, 3 to pin.protocol.version, 4 to pin.authenticate(byteArrayOf(4) + Cbor.encode(sub)))
        // CTAP 2.1 — команда 0x0A; YubiKey 5.2–5.6 знают только «предварительную» 0x41
        for (cmd in listOf(CRED_MGMT, CRED_MGMT_PRE)) {
            val first = try {
                call(t, cmd, begin)
            } catch (e: CtapError) {
                when (e.code) {
                    ERR_NO_CREDENTIALS -> return emptyList()
                    ERR_INVALID_COMMAND -> continue
                    else -> throw e
                }
            }
            return collect(t, cmd, first, application)
        }
        throw HwKeyException("Токен не умеет показывать резидентные ключи.")
    }

    private fun collect(t: HwTransport, cmd: Int, first: Map<*, *>, application: String): List<HwKey> {
        val total = (first.at(9) as? Long)?.toInt() ?: 1
        val out = mutableListOf<HwKey>()
        var m = first
        repeat(total) { i ->
            if (i > 0) m = call(t, cmd, linkedMapOf(1 to 5))
            val id = ((m.at(7) as? Map<*, *>)?.get("id") as? ByteArray) ?: return@repeat
            val (alg, pub) = coseKey(m.at(8) as Map<*, *>)
            out += HwKey(pub, id, application, alg, resident = true, name = t.name)
        }
        return out
    }
}

/** Ключи токена для SSH: создание (FIDO2, при необходимости — U2F), подпись касанием, загрузка резидентных. */
object Hw {
    /**
     * Создаёт ключ на токене: ed25519, если токен умеет (YubiKey 5.2.3+), иначе ecdsa P-256 (Рутокен MFA).
     * [askPin] спрашивает PIN у человека — только если токен его требует (резидентный ключ); null — отказался.
     */
    fun create(t: HwTransport, resident: Boolean, askPin: () -> String?): HwKey {
        val info = try {
            Ctap2.info(t)
        } catch (e: CtapUnsupported) {
            if (resident) throw HwKeyException("Этот токен понимает только U2F — резидентные ключи на нём невозможны.")
            return U2f.register(t)
        }
        if (resident && info.options["rk"] != true) throw HwKeyException("Этот токен не умеет хранить резидентные ключи.")
        val alg = if (info.supportsEd25519) Ctap2.EDDSA else Ctap2.ES256
        fun withPin(): HwKey {
            val pin = askPin() ?: throw HwKeyException("Создание ключа отменено: PIN не введён.")
            return Ctap2.makeCredential(t, info, alg, resident, Ctap2.pinToken(t, info, pin, Ctap2.PERM_MC, SkKey.APPLICATION))
        }
        if (resident && info.pinSet) return withPin()
        return try {
            Ctap2.makeCredential(t, info, alg, resident, null)
        } catch (e: CtapError) {
            if (e.code != Ctap2.ERR_PUAT_REQUIRED) throw e
            // старый CTAP 2.0 с заданным PIN: нерезидентный ecdsa создаём по U2F — там PIN не нужен
            if (!resident && alg == Ctap2.ES256) U2f.register(t) else withPin()
        }
    }

    /** Подпись касанием: FIDO2, а для токенов только с U2F — U2F (там бывает только ecdsa). */
    fun sign(t: HwTransport, key: HwKey, clientDataHash: ByteArray): U2f.Assertion = try {
        Ctap2.getAssertion(t, key, clientDataHash)
    } catch (e: CtapUnsupported) {
        if (key.ed25519) throw HwKeyException("Токен не поддерживает FIDO2, а ключ ed25519 — только FIDO2.")
        U2f.authenticate(t, key, clientDataHash)
    }

    /** Резидентные ключи с токена (новый телефон): нужен PIN. */
    fun loadResident(t: HwTransport, askPin: () -> String?): List<HwKey> {
        val info = Ctap2.info(t)
        val pin = askPin() ?: throw HwKeyException("Загрузка отменена: PIN не введён.")
        return Ctap2.residentKeys(t, Ctap2.pinToken(t, info, pin, Ctap2.PERM_CM, null))
    }
}
