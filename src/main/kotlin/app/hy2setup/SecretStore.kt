package app.hy2setup

import com.sun.jna.platform.win32.Crypt32Util
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Хранилище паролей, SSH-ключа и API-токена.
 * На Windows значения шифруются через DPAPI (ключ привязан к учётной записи Windows и не лежит на диске),
 * в файл попадает только шифротекст. Вне Windows (разработка, тесты) — AES-256-GCM с ключом в файле рядом.
 */
class SecretStore {

    private val prefs = Prefs("secrets")

    fun put(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        try {
            prefs.edit().putString(name, encrypt(value)).apply()
        } catch (e: Throwable) {
            // Не удалось зашифровать — лучше не сохранять вовсе, чем сохранить открытым текстом.
            prefs.edit().remove(name).apply()
        }
    }

    fun get(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return try {
            decrypt(stored)
        } catch (e: Throwable) {
            ""
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(value: String): String {
        val data = value.toByteArray(Charsets.UTF_8)
        val enc = Base64.getEncoder()
        if (isWindows) return "dpapi:" + enc.encodeToString(Crypt32Util.cryptProtectData(data))
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, fileKey(), GCMParameterSpec(128, iv)) }
        return "aes:" + enc.encodeToString(iv) + ":" + enc.encodeToString(cipher.doFinal(data))
    }

    private fun decrypt(stored: String): String {
        val dec = Base64.getDecoder()
        if (stored.startsWith("dpapi:")) {
            return String(Crypt32Util.cryptUnprotectData(dec.decode(stored.removePrefix("dpapi:"))), Charsets.UTF_8)
        }
        val (iv, ct) = stored.removePrefix("aes:").split(":").let { it[0] to it[1] }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, fileKey(), GCMParameterSpec(128, dec.decode(iv))) }
        return String(cipher.doFinal(dec.decode(ct)), Charsets.UTF_8)
    }

    private fun fileKey(): SecretKeySpec {
        val f = AppDirs.file("secret.key")
        if (!f.exists()) f.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
        return SecretKeySpec(f.readBytes(), "AES")
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}
