package app.hy2setup

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.SecureRandom

data class SshConfig(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    /** Приватный ключ в текстовом виде (PEM/OpenSSH). Пусто — вход по паролю. */
    val privateKey: String,
    val keyPassphrase: String,
    /** Ключ на аппаратном токене (Рутокен MFA): если задан, вход только по нему — с касанием токена. */
    val hwKey: app.hy2setup.hwkey.HwKey? = null,
)

class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Выполняет bash-скрипт на сервере по SSH.
 *
 * Отпечаток ключа сервера запоминается при первом подключении (после подтверждения
 * пользователем) и затем проверяется — защита от подмены сервера.
 */
class SshRunner(
    private val knownHostsFile: File,
    /** Канал к аппаратному токену (ждёт, пока его вставят или приложат); null — токены не поддерживаются. */
    private val hwTransport: (() -> app.hy2setup.hwkey.HwTransport)? = null,
    /** Сколько ждать приветствия SSH при подключении. */
    private val connectTimeoutMs: Int = 45_000,
    /** Попыток подключения при временных сетевых ошибках (таймаут, сброс соединения). */
    private val connectAttempts: Int = 3,
    /** Паузы перед повторными попытками. */
    private val retryDelaysMs: List<Long> = listOf(10_000, 20_000),
    /** Вызывается, когда сервер неизвестен. Возвращает true, если пользователь доверяет. */
    private val confirmHostKey: suspend (String) -> Boolean,
) {

    companion object {
        /** Вызывается перед генерацией ключей, чтобы применились настройки ниже. */
        fun configure() {}

        /**
         * Скрипт больше 3 КБ отправляется сжатым: маленькая оболочка распаковывает его во временный файл и запускает.
         * Сам скрипт удаляет себя (trap в HEADER), оболочка удаляет себя сразу.
         */
        fun packed(script: String): String {
            if (script.length < 3_000) return script
            val bytes = java.io.ByteArrayOutputStream().also { out ->
                java.util.zip.GZIPOutputStream(out).use { it.write(script.toByteArray(Charsets.UTF_8)) }
            }.toByteArray()
            val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
            return "#!/bin/bash\n" +
                "D=${'$'}(mktemp /tmp/.hy2setup-XXXXXX.sh) || exit 1\n" +
                "echo '$b64' | base64 -d | gunzip > \"${'$'}D\" || { echo \"ОШИБКА: не удалось распаковать скрипт\"; rm -f \"${'$'}D\" \"${'$'}0\"; exit 1; }\n" +
                "rm -f \"${'$'}0\"\n" +
                "exec bash \"${'$'}D\"\n"
        }

        init {
            // На Android JSch видит себя как Java 8 и для Ed25519/Ed448/X25519 берёт реализации
            // на Bouncy Castle. Задаём их явно, чтобы поведение было одинаковым везде (и в тестах).
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        }
    }

    /** Возвращает код выхода скрипта. Каждая строка вывода уходит в [onLine]. */
    suspend fun runScript(cfg: SshConfig, script: String, onLine: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val session = connectWithRetry(cfg, onLine)
            try {
                val remotePath = "/tmp/.hy2setup-${randomName()}.sh"
                upload(session, packed(script), remotePath)
                exec(session, cfg, remotePath, onLine)
            } finally {
                session.disconnect()
            }
        }

    /**
     * Подключение с повтором при временных сетевых ошибках — только до запуска скрипта, поэтому без побочных эффектов.
     * Ошибки входа (пароль, ключ, ключ сервера) не повторяются.
     */
    private suspend fun connectWithRetry(cfg: SshConfig, onLine: (String) -> Unit): Session {
        var attempt = 1
        while (true) {
            try {
                return connect(cfg)
            } catch (e: SshException) {
                val kind = transientKind(e) ?: throw e
                if (attempt >= connectAttempts) {
                    val where = "${cfg.host}:${cfg.port}"
                    throw SshException(
                        if (kind == Transient.SILENT) {
                            "Сервер $where принял соединение, но не ответил по SSH за ${connectTimeoutMs / 1000} с (попыток: $attempt). " +
                                "Возможно, он перегружен или SSH ограничивает частые подключения (fail2ban). Подождите минуту и повторите; проверьте порт SSH."
                        } else {
                            "Не удаётся подключиться к $where (попыток: $attempt): ${e.cause?.message ?: e.message}. " +
                                "Проверьте адрес и порт SSH и что сервер включён."
                        },
                        e.cause ?: e,
                    )
                }
                val pause = retryDelaysMs.getOrElse(attempt - 1) { retryDelaysMs.lastOrNull() ?: 0L }
                onLine("SSH не ответил вовремя (${e.cause?.message ?: e.message}). Повтор через ${(pause + 999) / 1000} с (попытка ${attempt + 1} из $connectAttempts)…")
                kotlinx.coroutines.delay(pause)
                attempt++
            }
        }
    }

    private enum class Transient { SILENT, NO_CONNECT }

    /** Временная ли сетевая ошибка: SILENT — соединение есть, но SSH молчит; NO_CONNECT — не подключиться; null — не временная. */
    private fun transientKind(e: Throwable): Transient? {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message.orEmpty()
            when {
                t is java.net.SocketTimeoutException && m.contains("connect", true) -> return Transient.NO_CONNECT
                m.contains("connect timed out", true) || m.contains("Connection refused", true) -> return Transient.NO_CONNECT
                t is java.net.SocketTimeoutException || m.contains("timed out", true) || m.contains("timeout: socket", true) -> return Transient.SILENT
                m.contains("Connection reset", true) || m.contains("foreign host", true) || m.contains("connection is closed", true) -> return Transient.SILENT
            }
            t = t.cause
        }
        return null
    }

    private fun connect(cfg: SshConfig): Session {
        if (!knownHostsFile.exists()) {
            knownHostsFile.parentFile?.mkdirs()
            knownHostsFile.createNewFile()
        }
        val jsch = JSch()
        jsch.setKnownHosts(knownHostsFile.absolutePath)
        val hw = cfg.hwKey?.let { k ->
            val t = hwTransport ?: throw SshException("Вход по Рутокену на этом устройстве недоступен.")
            app.hy2setup.hwkey.HwIdentity(k, t).also { jsch.addIdentity(it, null) }
        }
        val useKey = hw == null && cfg.privateKey.isNotBlank()
        if (useKey) {
            val keyBytes = normalizeKey(cfg.privateKey).toByteArray()
            checkKey(jsch, keyBytes, cfg.keyPassphrase)
            val passphrase = cfg.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray()
            try {
                jsch.addIdentity("key", keyBytes, null, passphrase)
            } catch (e: Exception) {
                throw SshException("Не удалось загрузить приватный ключ: ${e.message}", e)
            }
        }
        val session = jsch.getSession(cfg.user, cfg.host, cfg.port)
        if (!useKey && hw == null) session.setPassword(cfg.password)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig(
            "PreferredAuthentications",
            if (!useKey && hw == null) "password,keyboard-interactive" else "publickey",
        )
        if (hw != null) {
            val algs = session.getConfig("PubkeyAcceptedAlgorithms").orEmpty()
            session.setConfig("PubkeyAcceptedAlgorithms", (listOf(cfg.hwKey!!.type) + algs.split(",").filter { it.isNotBlank() }).joinToString(","))
        }
        session.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = null
            override fun promptPassword(message: String?) = false
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?): Boolean =
                runBlocking { confirmHostKey(message.orEmpty()) }
            override fun showMessage(message: String?) {}
        }
        try {
            session.connect(connectTimeoutMs)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val authFail = msg.contains("Auth fail", true) || msg.contains("Auth cancel", true)
            val hint = when {
                authFail && hw != null -> hw.lastError ?: ("Сервер не принял ключ Рутокена. Добавьте его на сервер кнопкой «Добавить ключ Рутокена на сервер» " +
                    "(нужен OpenSSH 8.2 или новее) и проверьте имя пользователя «${cfg.user}».")
                (msg.contains("Auth fail", true) || msg.contains("Auth cancel", true)) && useKey ->
                    "Сервер не принял ключ. Проверьте, что публичный ключ (.pub) добавлен в файл " +
                        "~/.ssh/authorized_keys пользователя «${cfg.user}» на сервере и что имя пользователя указано верно."
                msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
                    "Неверный логин или пароль."
                e.message?.contains("reject HostKey", ignoreCase = true) == true ||
                    e.message?.contains("HostKey has been changed", ignoreCase = true) == true ->
                    "Ключ сервера не принят или изменился. Если вы переустанавливали сервер — сбросьте доверенные серверы."
                else -> e.message ?: e.javaClass.simpleName
            }
            throw SshException(hint, e)
        }
        return session
    }

    /** Убирает \r и восстанавливает переносы строк, если ключ вставили «в одну строку». */
    private fun normalizeKey(raw: String): String {
        var k = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (!k.contains('\n')) {
            val m = Regex("^(-----BEGIN [A-Z0-9 ]+-----)\\s*(.*?)\\s*(-----END [A-Z0-9 ]+-----)$").find(k)
            if (m != null) {
                val body = m.groupValues[2].trim().split(Regex("\\s+")).joinToString("\n")
                k = m.groupValues[1] + "\n" + body + "\n" + m.groupValues[3]
            }
        }
        return k + "\n"
    }

    /** Заранее проверяет ключ, чтобы дать понятную ошибку вместо «Auth cancel». */
    private fun checkKey(jsch: JSch, keyBytes: ByteArray, passphrase: String) {
        val text = String(keyBytes).trim()
        if (text.startsWith("ssh-") || text.startsWith("ecdsa-")) {
            throw SshException("Вставлен публичный ключ (.pub). Нужен приватный ключ — файл без расширения .pub, начинается с «-----BEGIN».")
        }
        val kp = try {
            KeyPair.load(jsch, keyBytes, null)
        } catch (e: Exception) {
            throw SshException(
                "Не удалось прочитать приватный ключ. Поддерживаются ключи OpenSSH («-----BEGIN OPENSSH PRIVATE KEY-----»), " +
                    "PEM (RSA/EC) и PuTTY .ppk. Проверьте, что ключ скопирован целиком.",
                e,
            )
        }
        try {
            if (kp.isEncrypted) {
                if (passphrase.isEmpty()) throw SshException("Ключ защищён паролем — введите пароль от ключа.")
                if (!kp.decrypt(passphrase.toByteArray())) throw SshException("Неверный пароль от ключа.")
            }
        } finally {
            kp.dispose()
        }
    }

    private fun upload(session: Session, script: String, remotePath: String) {
        // на путях с «заморозкой после ~16 КБ» (ТСПУ) большие передачи обрываются, поэтому скрипты сжимаются
        val sftp = session.openChannel("sftp") as ChannelSftp
        try {
            sftp.connect(10_000)
            sftp.put(script.toByteArray(Charsets.UTF_8).inputStream(), remotePath)
            sftp.chmod("700".toInt(8), remotePath)
        } catch (e: Exception) {
            throw SshException("Не удалось загрузить скрипт на сервер (SFTP): ${e.message}", e)
        } finally {
            sftp.disconnect()
        }
    }

    private fun exec(session: Session, cfg: SshConfig, remotePath: String, onLine: (String) -> Unit): Int {
        val needSudo = cfg.user != "root"
        val command = if (needSudo) "sudo -S -p '' bash $remotePath 2>&1" else "bash $remotePath 2>&1"
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val reader = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))
        val stdin = channel.outputStream
        channel.connect(10_000)
        if (needSudo) {
            // Пароль для sudo передаётся через stdin и нигде не логируется.
            stdin.write((cfg.password + "\n").toByteArray())
            stdin.flush()
        }
        try {
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
            // Дожидаемся закрытия канала, чтобы получить код выхода.
            var waited = 0
            while (!channel.isClosed && waited < 50) {
                Thread.sleep(100); waited++
            }
            return channel.exitStatus
        } finally {
            channel.disconnect()
        }
    }

    private fun randomName(): String {
        val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun forgetHosts() {
        knownHostsFile.delete()
    }
}
