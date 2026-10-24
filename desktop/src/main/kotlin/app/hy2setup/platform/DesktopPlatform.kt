package app.hy2setup.platform

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ПК (Windows 11): настройки и секреты — файлы в %APPDATA%\vpngramm. Пароли, ключи и токены шифруются
 * Windows DPAPI: расшифровать их может только эта учётная запись Windows на этом компьютере.
 */
class DesktopPlatform(override val dataDir: File = defaultDataDir()) : Platform {
    init {
        dataDir.mkdirs()
    }

    override val prefs: KeyValueStore = PropertiesStore(File(dataDir, "settings.properties"))
    override val secrets: SecretStorage = FileSecretStore(File(dataDir, "secrets.properties"), secretCipher(dataDir))

    override val monitorSupported get() = false
    override fun scheduleMonitor(hours: Int) {}
    override fun monitorLastState(fqdn: String): Pair<Long, Boolean>? = null

    /** VPN на компьютере: поднят интерфейс туннеля (Happ, v2rayN, WireGuard, OpenVPN…). */
    override fun vpnActive(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { n ->
            val name = (n.name + " " + n.displayName).lowercase()
            n.isUp && !n.isLoopback && (n.isPointToPoint || VPN_NAMES.any { it in name })
        }
    }.getOrDefault(false)

    override val vpnSupported get() = false
    override fun vpnStart(config: String, server: String, protocol: String) =
        throw UnsupportedOperationException("Встроенный VPN есть только в версии для Android.")
    override fun vpnStop() {}

    companion object {
        private val VPN_NAMES = listOf("wintun", "tun", "tap-", "tap ", "wireguard", "vpn", "happ", "v2ray", "sing-box", "amnezia")

        val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

        /** %APPDATA%\vpngramm на Windows, ~/.vpngramm в остальных системах; VPNGRAMM_HOME — для тестов. */
        fun defaultDataDir(): File {
            System.getenv("VPNGRAMM_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val appData = System.getenv("APPDATA")
            return if (isWindows && !appData.isNullOrBlank()) File(appData, "vpngramm") else File(System.getProperty("user.home"), ".vpngramm")
        }

        private fun secretCipher(dir: File): SecretCipher = if (isWindows) DpapiCipher else AesFileCipher(File(dir, "secret.key"))
    }
}

/** Настройки в файле .properties: читаются при запуске, записываются целиком при apply(). */
internal class PropertiesStore(private val file: File) : KeyValueStore {
    private val props = Properties().apply { if (file.exists()) runCatching { file.inputStream().use { load(it) } } }

    @Synchronized override fun getString(key: String, default: String?): String? = props.getProperty(key) ?: default
    @Synchronized override fun getBoolean(key: String, default: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: default
    @Synchronized override fun getInt(key: String, default: Int) = props.getProperty(key)?.toIntOrNull() ?: default

    override fun edit(): KeyValueStore.Editor = Ed()

    @Synchronized private fun commit(changes: Map<String, String?>) {
        changes.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v) }
        // как SharedPreferences.apply(): сбой записи не роняет приложение, значения остаются в памяти
        runCatching { writeAtomically(file) { out -> props.store(out, "vpngramm") } }
    }

    @Synchronized fun clearMemory() = props.clear()

    private inner class Ed : KeyValueStore.Editor {
        private val changes = LinkedHashMap<String, String?>()
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun apply() = commit(changes)
    }
}

/** Шифрование одного значения. */
internal interface SecretCipher {
    fun encrypt(data: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/** Windows DPAPI (CryptProtectData) — ключом служит учётная запись Windows. */
internal object DpapiCipher : SecretCipher {
    override fun encrypt(data: ByteArray): ByteArray = Crypt32Util.cryptProtectData(data)
    override fun decrypt(data: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(data)
}

/** Не Windows (сборка и тесты): AES-256-GCM с ключом в отдельном файле рядом с данными. */
internal class AesFileCipher(private val keyFile: File) : SecretCipher {
    private val key by lazy {
        if (!keyFile.exists()) writeAtomically(keyFile) { it.write(ByteArray(32).also { b -> SecureRandom().nextBytes(b) }) }
        SecretKeySpec(keyFile.readBytes(), "AES")
    }

    override fun encrypt(data: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(data)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12)) }
        return c.doFinal(data, 12, data.size - 12)
    }
}

/** Пароли, ключи и токены: в файле лежит только шифротекст (Base64). */
internal class FileSecretStore(private val file: File, private val cipher: SecretCipher) : SecretStorage {
    private val store = PropertiesStore(file)

    override fun put(name: String, value: String) {
        if (value.isEmpty()) {
            store.edit().remove(name).apply()
            return
        }
        try {
            store.edit().putString(name, Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))).apply()
        } catch (e: Exception) {
            // не удалось зашифровать — лучше не сохранять вовсе, чем сохранить открытым текстом
            store.edit().remove(name).apply()
        }
    }

    override fun get(name: String): String {
        val stored = store.getString(name, null) ?: return ""
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    override fun clear() {
        file.delete()
        store.clearMemory()
    }
}

/** Файл, выбранный в диалоге Windows. */
class FileDoc(val file: File) : Doc {
    override fun read(): ByteArray = file.readBytes()
    override fun write(bytes: ByteArray) = writeAtomically(file) { it.write(bytes) }
}

/** Запись через временный файл: при сбое старый файл не портится. */
internal fun writeAtomically(file: File, body: (java.io.OutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use(body)
    try {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        // файл занят (антивирус, облачная синхронизация) — обычная замена
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
