package app.hy2setup.hwkey

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * Программный FIDO2-токен для тестов (как YubiKey 5.7 или Рутокен MFA): CTAP2 и U2F, ed25519 и P-256,
 * резидентные ключи с credProtect, PIN (протоколы 1 и 2), перечисление резидентных ключей.
 * [pin] — заданный PIN (null — не задан); [ed25519] — умеет ли Ed25519 (Рутокен — нет).
 */
class FakeFidoToken(
    val pin: String? = null,
    val ed25519: Boolean = true,
    var userPresent: Boolean = true,
    /** CTAP 2.0 без makeCredUvNotRqd: при заданном PIN любое создание ключа требует PIN. */
    val strictPin: Boolean = false,
    override val name: String = "Fake FIDO2",
) : HwTransport {
    private class Cred(val alg: Int, val kp: KeyPair, val rpIdHash: ByteArray, val resident: Boolean, val rpId: String = "")
    private val creds = LinkedHashMap<String, Pair<ByteArray, Cred>>()
    private var counter = 0L
    private var pinToken: ByteArray? = null
    private var pinProto: Ctap2.PinProtocol? = null
    private val agreement = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private var enumeration = ArrayDeque<Pair<ByteArray, Cred>>()
    var pinRequests = 0
        private set

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun key(id: ByteArray) = id.toList().toString()
    private fun ok(m: Map<Int, Any?>?) = byteArrayOf(0) + (m?.let { Cbor.encode(it) } ?: ByteArray(0))
    private fun err(code: Int) = byteArrayOf(code.toByte())
    private fun pad32(v: BigInteger) = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }

    private fun cose(c: Cred): Map<Int, Any> = if (c.alg == Ctap2.EDDSA) {
        val enc = c.kp.public.encoded   // X.509: последние 32 байта — ключ
        mapOf(1 to 1, 3 to -8, -1 to 6, -2 to enc.copyOfRange(enc.size - 32, enc.size))
    } else {
        val w = (c.kp.public as ECPublicKey).w
        mapOf(1 to 2, 3 to -7, -1 to 1, -2 to pad32(w.affineX), -3 to pad32(w.affineY))
    }

    private fun newCred(alg: Int, rpIdHash: ByteArray, resident: Boolean, rpId: String = ""): Pair<ByteArray, Cred> {
        val kp = if (alg == Ctap2.EDDSA) KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        else KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val id = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return (id to Cred(alg, kp, rpIdHash, resident, rpId)).also { creds[key(id)] = it }
    }

    private fun sign(c: Cred, msg: ByteArray): ByteArray =
        Signature.getInstance(if (c.alg == Ctap2.EDDSA) "Ed25519" else "SHA256withECDSA").apply { initSign(c.kp.private); update(msg) }.sign()

    private fun counterBytes() = byteArrayOf((counter shr 24).toByte(), (counter shr 16).toByte(), (counter shr 8).toByte(), counter.toByte())

    // ---------------- U2F ----------------
    override fun send(ins: Int, p1: Int, data: ByteArray): ByteArray {
        val okSw = byteArrayOf(0x90.toByte(), 0)
        return when (ins) {
            U2f.INS_REGISTER -> {
                val (id, c) = newCred(Ctap2.ES256, data.copyOfRange(32, 64), false)
                val w = (c.kp.public as ECPublicKey).w
                byteArrayOf(0x05, 0x04) + pad32(w.affineX) + pad32(w.affineY) + byteArrayOf(id.size.toByte()) + id + byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00) + okSw
            }
            U2f.INS_AUTHENTICATE -> {
                val kh = data.copyOfRange(65, 65 + (data[64].toInt() and 0xff))
                val c = creds[key(kh)]?.second ?: return byteArrayOf(0x6A, 0x80.toByte())
                counter++
                val flags = if (userPresent) 1 else 0
                byteArrayOf(flags.toByte()) + counterBytes() + sign(c, data.copyOfRange(32, 64) + byteArrayOf(flags.toByte()) + counterBytes() + data.copyOfRange(0, 32)) + okSw
            }
            else -> byteArrayOf(0x6D, 0)
        }
    }

    // ---------------- CTAP2 ----------------
    override fun cbor(request: ByteArray): ByteArray {
        val cmd = request[0].toInt() and 0xff
        val p = if (request.size > 1) Cbor.decode(request.copyOfRange(1, request.size)) as Map<*, *> else emptyMap<Any, Any>()
        return when (cmd) {
            Ctap2.GET_INFO -> ok(mapOf(
                1 to listOf("U2F_V2", "FIDO_2_0", "FIDO_2_1"), 2 to listOf("credProtect"), 3 to ByteArray(16),
                4 to mapOf("rk" to true, "clientPin" to (pin != null), "pinUvAuthToken" to true, "makeCredUvNotRqd" to !strictPin),
                6 to listOf(2, 1),
                10 to (if (ed25519) listOf(mapOf("alg" to -8, "type" to "public-key")) else emptyList()) + listOf(mapOf("alg" to -7, "type" to "public-key")),
            ))
            Ctap2.MAKE_CREDENTIAL -> makeCredential(p)
            Ctap2.GET_ASSERTION -> getAssertion(p)
            Ctap2.CLIENT_PIN -> clientPin(p)
            Ctap2.CRED_MGMT -> credMgmt(p)
            else -> err(Ctap2.ERR_INVALID_COMMAND)
        }
    }

    private fun pinOk(msg: ByteArray, param: Any?): Boolean {
        val t = pinToken ?: return false
        val pp = pinProto ?: return false
        return param is ByteArray && pp.authenticate(t, msg).contentEquals(param)
    }

    private fun makeCredential(p: Map<*, *>): ByteArray {
        val cdh = p.at(1) as ByteArray
        val rpId = (p.at(2) as Map<*, *>)["id"] as String
        val alg = ((p.at(4) as List<*>).first() as Map<*, *>)["alg"].let { (it as Long).toInt() }
        if (alg == Ctap2.EDDSA && !ed25519) return err(0x26)
        val rk = (p.at(7) as? Map<*, *>)?.get("rk") == true
        val needPin = pin != null && (rk || strictPin)
        if (needPin && !pinOk(cdh, p.at(8))) return err(Ctap2.ERR_PUAT_REQUIRED)
        if (rk && pin == null) return err(Ctap2.ERR_PIN_NOT_SET)       // как YubiKey: резидентный — только с PIN
        val (id, c) = newCred(alg, sha(rpId.toByteArray()), rk, rpId)
        val auth = c.rpIdHash + byteArrayOf(0x45) + ByteArray(4) + ByteArray(16) + byteArrayOf((id.size shr 8).toByte(), id.size.toByte()) + id + Cbor.encode(cose(c))
        return ok(mapOf(1 to "packed", 2 to auth, 3 to emptyMap<String, Any>()))
    }

    private fun getAssertion(p: Map<*, *>): ByteArray {
        val rpIdHash = sha((p.at(1) as String).toByteArray())
        val cdh = p.at(2) as ByteArray
        val ids = (p.at(3) as List<*>).map { (it as Map<*, *>)["id"] as ByteArray }
        val (id, c) = ids.firstNotNullOfOrNull { creds[key(it)] }?.takeIf { it.second.rpIdHash.contentEquals(rpIdHash) } ?: return err(Ctap2.ERR_NO_CREDENTIALS)
        counter++
        val flags = if (userPresent) 1 else 0
        val auth = rpIdHash + byteArrayOf(flags.toByte()) + counterBytes()
        return ok(mapOf(1 to mapOf("id" to id, "type" to "public-key"), 2 to auth, 3 to sign(c, auth + cdh)))
    }

    private fun clientPin(p: Map<*, *>): ByteArray {
        val version = (p.at(1) as Long).toInt()
        when ((p.at(2) as Long).toInt()) {
            2 -> {
                val w = (agreement.public as ECPublicKey).w
                return ok(mapOf(1 to mapOf(1 to 2, 3 to -25, -1 to 1, -2 to pad32(w.affineX), -3 to pad32(w.affineY))))
            }
            5, 9 -> {
                pinRequests++
                val platform = p.at(3) as Map<*, *>
                val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
                    ECPoint(BigInteger(1, platform.at(-2) as ByteArray), BigInteger(1, platform.at(-3) as ByteArray)),
                    (agreement.public as ECPublicKey).params,
                ))
                val z = KeyAgreement.getInstance("ECDH").apply { init(agreement.private); doPhase(peer, true) }.generateSecret()
                val proto = Ctap2.PinProtocol.fromZ(version, z)
                val got = proto.decrypt(p.at(6) as ByteArray)
                if (pin == null) return err(Ctap2.ERR_PIN_NOT_SET)
                if (!got.contentEquals(sha(pin.toByteArray()).copyOf(16))) return err(Ctap2.ERR_PIN_INVALID)
                val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
                pinToken = token; pinProto = proto
                return ok(mapOf(2 to proto.encrypt(token)))
            }
            else -> return err(0x2B)
        }
    }

    private fun credMgmt(p: Map<*, *>): ByteArray {
        when ((p.at(1) as Long).toInt()) {
            2 -> {
                if (!pinOk(byteArrayOf(2), p.at(4))) return err(Ctap2.ERR_PUAT_REQUIRED)
                rps = ArrayDeque(creds.values.filter { it.second.resident }.map { it.second.rpId }.distinct())
                if (rps.isEmpty()) return err(Ctap2.ERR_NO_CREDENTIALS)
                val total = rps.size
                return ok(nextRp() + (5 to total))
            }
            3 -> return ok(nextRp())
            4 -> {
                val sub = p.at(2) as Map<*, *>
                if (!pinOk(byteArrayOf(4) + Cbor.encode(sub), p.at(4))) return err(Ctap2.ERR_PUAT_REQUIRED)
                val hash = sub.at(1) as ByteArray
                enumeration = ArrayDeque(creds.values.filter { it.second.resident && it.second.rpIdHash.contentEquals(hash) })
                if (enumeration.isEmpty()) return err(Ctap2.ERR_NO_CREDENTIALS)
                val total = enumeration.size
                return ok(next() + (9 to total))
            }
            5 -> return ok(next())
            else -> return err(0x2B)
        }
    }

    private var rps = ArrayDeque<String>()
    private fun nextRp(): Map<Int, Any?> {
        val id = rps.removeFirst()
        return mapOf(3 to mapOf("id" to id), 4 to sha(id.toByteArray()))
    }

    private fun next(): Map<Int, Any?> {
        val (id, c) = enumeration.removeFirst()
        return mapOf(6 to mapOf("id" to ByteArray(32)), 7 to mapOf("id" to id, "type" to "public-key"), 8 to cose(c), 10 to 2)
    }
}
