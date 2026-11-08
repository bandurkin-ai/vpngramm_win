package app.hy2setup

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Работа с SSH-ключами пользователя. */
object SshKeys {
    /**
     * Публичная часть приватного ключа в виде "тип base64" (как в authorized_keys, без комментария)
     * или null, если ключ не читается / неверный пароль от ключа.
     */
    fun publicOf(privateKey: String, passphrase: String = ""): String? {
        SshRunner.configure()
        return try {
            val kp = KeyPair.load(JSch(), privateKey.trim().replace("\r", "").toByteArray() + '\n'.code.toByte(), null)
            try {
                if (kp.isEncrypted && !kp.decrypt(passphrase.toByteArray())) return null
                val blob = kp.publicKeyBlob ?: return null
                val type = String(blob, 4, ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
                    ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff))
                type + " " + Base64.getEncoder().encodeToString(blob)
            } finally {
                kp.dispose()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Публичный ключ из authorized_keys/кабинета без комментария: "тип base64". */
    fun normalizePublic(value: String): String = value.trim().split(Regex("\\s+")).take(2).joinToString(" ")

    /** Соответствует ли приватный ключ одному из публичных. */
    fun matchesAny(privateKey: String, passphrase: String, publics: List<String>): Boolean {
        val pub = publicOf(privateKey, passphrase) ?: return false
        return publics.any { normalizePublic(it) == pub }
    }
}

/**
 * Защита сервера перед установкой протокола, вход — по SSH-ключу пользователя
 * (например, добавленному при создании VPS в OneProvider):
 * проверка входа по ключу → обновления → автообновления → отключение входа по паролю →
 * повторная проверка → перезагрузка при необходимости.
 * Если по ключу войти не удалось, сервер не изменяется и вход по паролю остаётся.
 */
class Hardener(private val runner: SshRunner, private val log: (String) -> Unit) {

    /**
     * [keyCfg] — вход по ключу (пароль в нём используется только для sudo).
     * [password] — прежний пароль: после отключения проверяем, что сервер его больше не принимает.
     */
    suspend fun harden(keyCfg: SshConfig, password: String, updates: Boolean = true): SshConfig {
        require(keyCfg.privateKey.isNotBlank()) { "Не задан приватный SSH-ключ." }

        log("==> Проверка входа по SSH-ключу")
        when (login(keyCfg, attempts = 10)) {
            Login.OK -> log("✓ Вход по ключу работает.")
            Login.REJECTED -> throw IllegalStateException(
                "Сервер не принял SSH-ключ — сервер не изменялся, вход по паролю остался. Проверьте, что ключ " +
                    "в «Доступе к серверам» (Инструменты) — это приватная часть ключа, добавленного на сервер (в кабинете OneProvider).",
            )
            Login.UNREACHABLE -> throw IllegalStateException("Сервер недоступен по SSH — защита не выполнялась.")
        }

        val reboot = AtomicBoolean(false)
        if (updates) {
            runUpdates(keyCfg, reboot)
        } else {
            val code = runner.runScript(keyCfg, Scripts.harden(false)) { line ->
                if (line.trim() == Scripts.REBOOT_MARKER) reboot.set(true) else log(line)
            }
            if (code != 0) throw IllegalStateException("Подготовка сервера завершилась с ошибкой (код $code). Вход по паролю не менялся.")
        }

        val code2 = runner.runScript(keyCfg, Scripts.disablePasswordAuth()) { log(it) }
        if (code2 != 0) {
            throw IllegalStateException("Не удалось отключить вход по паролю (изменения откатились, доступ прежний).")
        }
        if (login(keyCfg, attempts = 8) != Login.OK) {
            throw IllegalStateException(
                "После изменения настроек SSH повторная проверка входа по ключу не прошла. " +
                    "Проверьте доступ через консоль VNC в панели провайдера.",
            )
        }
        log("✓ Повторная проверка: вход по ключу работает.")

        if (password.isNotEmpty()) {
            when (login(keyCfg.copy(privateKey = "", keyPassphrase = "", password = password), attempts = 5)) {
                Login.OK -> log("ПРЕДУПРЕЖДЕНИЕ: сервер всё ещё принимает вход по паролю.")
                Login.REJECTED -> log("✓ Вход по паролю больше не принимается.")
                Login.UNREACHABLE -> log("Не удалось проверить отказ во входе по паролю: сервер не ответил.")
            }
        }

        firewallAndFail2ban(keyCfg)

        if (reboot.get()) rebootAndWait(keyCfg)
        return keyCfg
    }

    /**
     * Файрвол (снаружи только SSH и порты VPN) и fail2ban. Ошибка здесь не отменяет уже сделанную защиту.
     * Файрвол включается с таймером отката: подтверждаем его только после входа новым подключением.
     */
    private suspend fun firewallAndFail2ban(keyCfg: SshConfig) {
        log("")
        log("==> Файрвол и fail2ban")
        try {
            if (runner.runScript(keyCfg, Scripts.fail2banInstall()) { log(it) } != 0) {
                log("ПРЕДУПРЕЖДЕНИЕ: fail2ban не включён — защита от перебора паролей не работает, остальное в силе.")
            }
        } catch (e: SshException) {
            log("ПРЕДУПРЕЖДЕНИЕ: fail2ban не включён: ${e.message}")
        }
        val applied = try {
            runner.runScript(keyCfg, Scripts.firewallApply()) { log(it) } == 0
        } catch (e: SshException) {
            log("ПРЕДУПРЕЖДЕНИЕ: файрвол не включён: ${e.message}")
            return
        }
        if (!applied) {
            log("ПРЕДУПРЕЖДЕНИЕ: файрвол не включён (правила не применились). Вход по ключу работает, VPN не затронут.")
            return
        }
        when (login(keyCfg, attempts = 4)) {
            Login.OK -> try {
                runner.runScript(keyCfg, Scripts.firewallConfirm()) { log(it) }
            } catch (e: SshException) {
                log("ПРЕДУПРЕЖДЕНИЕ: не удалось подтвердить файрвол (${e.message}) — правила снимутся через 3 минуты; повторите «Защитить сервер».")
            }
            else -> log(
                "ПРЕДУПРЕЖДЕНИЕ: после включения файрвола войти не удалось — через 3 минуты после включения правила снимутся сами, " +
                    "и доступ вернётся. Проверьте порт SSH и повторите «Защитить сервер».",
            )
        }
    }

    /**
     * Обновления идут на сервере отдельной службой, а приложение короткими сессиями читает их журнал.
     * Обрыв связи (мобильный интернет, сворачивание приложения) не прерывает обновление: переподключаемся и читаем дальше.
     */
    private suspend fun runUpdates(cfg: SshConfig, reboot: AtomicBoolean) {
        val code = BackgroundJob(runner, log).run(cfg, "upd", Scripts.updatesStart(), "Обновление") { line ->
            if (line.trim() == Scripts.REBOOT_MARKER) { reboot.set(true); true } else false
        }
        if (code != 0) throw IllegalStateException("Обновление системы завершилось с ошибкой (код $code). Вход по паролю не менялся.")
    }

    private enum class Login { OK, REJECTED, UNREACHABLE }

    /** Пытается войти; при недоступности сервера (например, во время перезапуска sshd) повторяет. */
    private suspend fun login(cfg: SshConfig, attempts: Int): Login {
        repeat(attempts) { i ->
            try {
                val seen = AtomicBoolean(false)
                runner.runScript(cfg, Scripts.ping()) { if (it.trim() == Scripts.PING_MARKER) seen.set(true) }
                if (seen.get()) return Login.OK
            } catch (e: SshException) {
                val m = e.message.orEmpty()
                if ("Ключ сервера" in m || "Вставлен" in m || "пароль от ключа" in m || "прочитать приватный" in m) throw e
                if ("Неверный" in m || "не принял" in m) return Login.REJECTED
            }
            if (i < attempts - 1) delay(3_000)
        }
        return Login.UNREACHABLE
    }

    suspend fun rebootAndWait(cfg: SshConfig) {
        log("")
        try {
            runner.runScript(cfg, Scripts.reboot()) { log(it) }
        } catch (e: SshException) {
            // соединение могло оборваться — это нормально
        }
        var waited = 0
        while (tcpOpen(cfg.host, cfg.port) && waited < 90) {
            delay(3_000); waited += 3
        }
        log("Сервер перезагружается…")
        waited = 0
        while (!tcpOpen(cfg.host, cfg.port)) {
            if (waited >= 600) throw IllegalStateException("Сервер не поднялся после перезагрузки за 10 минут.")
            delay(10_000); waited += 10
        }
        if (login(cfg, attempts = 20) != Login.OK) throw IllegalStateException("После перезагрузки не удалось войти по ключу.")
        log("✓ Сервер снова доступен после перезагрузки.")
    }

    private suspend fun tcpOpen(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 5_000) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
