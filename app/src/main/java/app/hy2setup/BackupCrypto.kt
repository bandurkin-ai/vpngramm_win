package app.hy2setup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Резервная копия профиля под паролем: PBKDF2-HMAC-SHA256 + AES-256-GCM. Формат: «HY2B1:» + base64(salt|iv|шифртекст). */
object BackupCrypto {
    private const val PREFIX = "HY2B1:"
    private const val ITERATIONS = 200_000

    class WrongPassword : Exception("Неверный пароль или файл повреждён.")

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray())
        return PREFIX + Base64.getEncoder().encodeToString(salt + iv + ct)
    }

    fun decrypt(text: String, password: String): String {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) throw IllegalArgumentException("Это не файл резервной копии vpngramm.")
        val all = try {
            Base64.getDecoder().decode(t.removePrefix(PREFIX))
        } catch (e: IllegalArgumentException) {
            throw WrongPassword()
        }
        if (all.size < 16 + 12 + 16) throw WrongPassword()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, all.copyOfRange(0, 16)), GCMParameterSpec(128, all.copyOfRange(16, 28)))
        return try {
            String(c.doFinal(all.copyOfRange(28, all.size)))
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        }
    }
}
