package app.hy2setup.hwkey

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Программный U2F-токен для тестов: отвечает на REGISTER и AUTHENTICATE так же, как настоящий (Рутокен MFA, YubiKey).
 * [touchesNeeded] — сколько раз ответить «коснитесь» (6985) до подписи; [userPresent] = false — флаг UP не ставится.
 */
class FakeU2fToken(var touchesNeeded: Int = 0, var userPresent: Boolean = true) : HwTransport {
    private val keys = HashMap<String, KeyPair>()
    private var counter = 0L
    var calls = 0
        private set

    private fun ok(b: ByteArray) = b + byteArrayOf(0x90.toByte(), 0x00)
    private val needTouch = byteArrayOf(0x69, 0x85.toByte())

    override fun send(ins: Int, p1: Int, data: ByteArray): ByteArray {
        calls++
        if (touchesNeeded > 0) { touchesNeeded--; return needTouch }
        return when (ins) {
            U2f.INS_REGISTER -> {
                val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
                val kh = ByteArray(48).also { SecureRandom().nextBytes(it) }
                keys[kh.toList().toString()] = kp
                val pub = (kp.public as ECPublicKey).w
                fun c(v: java.math.BigInteger) = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
                val q = byteArrayOf(0x04) + c(pub.affineX) + c(pub.affineY)
                val fakeCert = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)
                ok(byteArrayOf(0x05) + q + byteArrayOf(kh.size.toByte()) + kh + fakeCert + byteArrayOf(0x30, 0x00))
            }
            U2f.INS_AUTHENTICATE -> {
                val challenge = data.copyOfRange(0, 32)
                val app = data.copyOfRange(32, 64)
                val kh = data.copyOfRange(65, 65 + (data[64].toInt() and 0xff))
                val kp = keys[kh.toList().toString()] ?: return byteArrayOf(0x6A, 0x80.toByte())
                counter++
                val flags = if (userPresent) 0x01 else 0x00
                val ctr = byteArrayOf((counter shr 24).toByte(), (counter shr 16).toByte(), (counter shr 8).toByte(), counter.toByte())
                val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private) }
                sig.update(app + byteArrayOf(flags.toByte()) + ctr + challenge)
                ok(byteArrayOf(flags.toByte()) + ctr + sig.sign())
            }
            else -> byteArrayOf(0x6D, 0x00)
        }
    }
}
