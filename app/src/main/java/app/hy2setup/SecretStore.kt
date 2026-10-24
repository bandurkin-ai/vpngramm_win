package app.hy2setup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.hy2setup.platform.SecretStorage
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище паролей, SSH-ключа и API-токена.
 * Значения шифруются AES-256-GCM ключом из Android Keystore (ключ не покидает защищённое хранилище телефона),
 * в настройки приложения попадает только шифротекст.
 */
class SecretStore(context: Context) : SecretStorage {

    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun put(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val enc = Base64.getEncoder()
            prefs.edit().putString(name, enc.encodeToString(cipher.iv) + ":" + enc.encodeToString(ct)).apply()
        } catch (e: Exception) {
            // Не удалось зашифровать — лучше не сохранять вовсе, чем сохранить открытым текстом.
            prefs.edit().remove(name).apply()
        }
    }

    override fun get(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return try {
            val (iv, ct) = stored.split(":").let { it[0] to it[1] }
            val dec = Base64.getDecoder()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, dec.decode(iv)))
            String(cipher.doFinal(dec.decode(ct)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hy2setup_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
