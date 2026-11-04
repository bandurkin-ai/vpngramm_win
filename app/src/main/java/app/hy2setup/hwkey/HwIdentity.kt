package app.hy2setup.hwkey

import com.jcraft.jsch.Identity

/**
 * Ключ на токене для JSch: открытая часть — из [key], подпись делает сам токен (касание при каждом входе).
 * [transport] вызывается в момент подписи: ждёт, пока токен вставят или приложат, и возвращает канал к нему.
 */
class HwIdentity(private val key: HwKey, private val transport: () -> HwTransport) : Identity {
    /** Почему подпись не удалась (для понятного сообщения вместо «Auth fail»). */
    @Volatile var lastError: String? = null
        private set

    override fun setPassphrase(passphrase: ByteArray?) = true
    override fun getPublicKeyBlob(): ByteArray = SkKey.publicBlob(key)
    override fun getAlgName(): String = key.type
    override fun getName(): String = "rutoken"
    override fun isEncrypted() = false
    override fun clear() {}

    override fun getSignature(data: ByteArray): ByteArray? = try {
        val t = transport()
        val a = try { Hw.sign(t, key, U2f.sha256(data)) } finally { (t as? java.io.Closeable)?.close() }
        if (key.ed25519) SkKey.signatureBlobEd25519(a.der, a.flags, a.counter) else SkKey.signatureBlob(a.der, a.flags, a.counter)
    } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        null
    }

    override fun getSignature(data: ByteArray, alg: String): ByteArray? = getSignature(data)
}
