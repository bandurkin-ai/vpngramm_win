package app.hy2setup.platform

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import app.hy2setup.SecretStore
import java.io.File

/** Android: SharedPreferences, Android Keystore, USB и NFC. */
class AndroidPlatform(private val app: Application) : Platform {
    override val prefs: KeyValueStore = PrefsStore(app.getSharedPreferences("settings", 0))
    override val secrets: SecretStorage = SecretStore(app)
    override val dataDir: File get() = app.filesDir

    override fun vpnActive(): Boolean {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }

    init {
        // «Контроль серверов» убран в 4.6: снять фоновую проверку, заведённую прежними версиями, и её канал уведомлений
        runCatching { androidx.work.WorkManager.getInstance(app).cancelUniqueWork("server-monitor") }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                app.getSystemService(android.app.NotificationManager::class.java)?.deleteNotificationChannel("monitor")
            }
        }
    }

    private val hw = HwKeyAndroid(app)
    override val hwKeySupported get() = true
    override fun hwKeyOpen(timeoutMs: Long) = hw.open(timeoutMs)
    override fun hwKeyClose() = hw.close()
    override fun hwKeyCancel() = hw.cancel()

}

private class PrefsStore(private val p: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String?) = p.getString(key, default)
    override fun getBoolean(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = p.getInt(key, default)
    override fun edit(): KeyValueStore.Editor = Ed(p.edit())

    private class Ed(private val e: SharedPreferences.Editor) : KeyValueStore.Editor {
        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun apply() = e.apply()
    }
}

/** Файл, выбранный через системный диалог Android. */
class UriDoc(private val app: Application, private val uri: Uri) : Doc {
    override fun read(): ByteArray =
        app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Не удалось открыть файл.")

    override fun write(bytes: ByteArray) {
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Не удалось открыть файл для записи.")
    }
}
