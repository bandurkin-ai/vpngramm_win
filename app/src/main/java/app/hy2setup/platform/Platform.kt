package app.hy2setup.platform

import java.io.File

/*
 * Платформенный слой: всё, чем версии для Android и Windows отличаются (хранение настроек и секретов,
 * файлы, фоновый контроль, встроенный VPN). Остальной код у них общий.
 */

/** Настройки «ключ — значение» (то, что используется из SharedPreferences). */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun remove(key: String): Editor
        fun apply()
    }
}

/** Зашифрованное хранилище паролей, ключей и токенов. */
interface SecretStorage {
    /** Значение или пустая строка. */
    fun get(name: String): String
    /** Пустое значение удаляет запись. */
    fun put(name: String, value: String)
    fun clear()
}

/** Файл, выбранный человеком (для копии серверов и SSH-ключа). */
interface Doc {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}

interface Platform {
    val prefs: KeyValueStore
    val secrets: SecretStorage
    /** Папка данных приложения (known_hosts и т. п.). */
    val dataDir: File

    /** Фоновый контроль серверов (есть только на Android). */
    val monitorSupported: Boolean
    fun scheduleMonitor(hours: Int)
    /** Последняя фоновая проверка сервера: время и успех. */
    fun monitorLastState(fqdn: String): Pair<Long, Boolean>?

    /** Включён ли на устройстве какой-нибудь VPN. */
    fun vpnActive(): Boolean

    /** Аппаратный ключ для входа по SSH (Рутокен MFA и другие U2F/FIDO2) — по USB-C или NFC; есть только на Android. */
    val hwKeySupported: Boolean
    /**
     * Ждёт, пока токен вставят в USB или приложат к NFC, и возвращает канал к нему. Блокирует поток до [timeoutMs];
     * при отмене ([hwKeyCancel]) или тайм-ауте бросает HwKeyException.
     */
    fun hwKeyOpen(timeoutMs: Long): app.hy2setup.hwkey.HwTransport
    /** Закончили работу с токеном: выключить ожидание NFC и закрыть USB. */
    fun hwKeyClose()
    fun hwKeyCancel()
}
