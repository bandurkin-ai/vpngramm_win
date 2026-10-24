package app.hy2setup

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Интеграционный тест режима безопасности на настоящем sshd (127.0.0.1:2223; вход root по паролю разрешён,
 * как на свежем облачном VPS). Ключ пользователя создаётся ssh-keygen и заранее лежит в authorized_keys —
 * как ключ, добавленный в кабинете OneProvider при создании VPS. Запуск: HARDEN_TEST=1 (HARDEN_UPDATES=1 — с обновлениями).
 * Для проверки на других ОС: HARDEN_CONTAINER=<имя docker-контейнера с systemd и sshd>, HARDEN_HOST, HARDEN_PORT=22.
 */
class HardenerTest {
    private val enabled = System.getenv("HARDEN_TEST") == "1"
    private val withUpdates = System.getenv("HARDEN_UPDATES") == "1"
    private val pw = "TestRootPw-123"
    private val container = System.getenv("HARDEN_CONTAINER").orEmpty()
    private val passwordCfg = SshConfig(
        System.getenv("HARDEN_HOST") ?: "127.0.0.1", System.getenv("HARDEN_PORT")?.toInt() ?: 2223, "root", pw, "", "",
    )
    private val known = File.createTempFile("known", "hosts").apply { delete() }
    private val runner = SshRunner(known) { true }
    private val dir = File(System.getProperty("java.io.tmpdir"), "hardener-keys")

    private fun sh(cmd: String): String {
        val p = ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    /** Команда на сервере: в контейнере через docker exec, иначе локально. */
    private fun srv(cmd: String): String {
        if (container.isEmpty()) return sh(cmd)
        val p = ProcessBuilder("docker", "exec", container, "bash", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private fun effective(key: String) =
        srv((if (container.isEmpty()) "/usr/sbin/sshd -T -p 2223" else "mkdir -p /run/sshd; /usr/sbin/sshd -T") +
            " | awk 'tolower(\$1)==\"$key\"{print \$2}'").trim()

    /** Ключ «из кабинета»: на сервере. Второй ключ — посторонний, на сервер не добавлен. */
    private fun keys() {
        sh("rm -rf $dir; mkdir -p $dir; ssh-keygen -q -t ed25519 -N '' -C cabinet -f $dir/cabinet; " +
            "ssh-keygen -q -t ed25519 -N '' -C other -f $dir/other")
        srv("mkdir -p /root/.ssh; chmod 700 /root/.ssh; sed -i '/ cabinet\$/d;/ other\$/d' /root/.ssh/authorized_keys 2>/dev/null; " +
            "echo '${File(dir, "cabinet.pub").readText().trim()}' >> /root/.ssh/authorized_keys; chmod 600 /root/.ssh/authorized_keys")
    }

    private fun reset() {
        srv("rm -f /etc/ssh/sshd_config.d/00-hy2setup-hardening.conf; " +
            (if (container.isEmpty()) "kill -HUP \$(cat /run/sshd-test.pid)" else "systemctl reload ssh || systemctl restart ssh") + "; sleep 1")
    }

    @Before fun before() { if (enabled) { reset(); keys() } }
    @After fun after() { if (enabled) reset() }

    private fun key(name: String) = File(dir, name).readText()

    private fun canLogin(cfg: SshConfig): Boolean = runBlocking {
        try {
            val out = mutableListOf<String>()
            runner.runScript(cfg, Scripts.ping()) { out += it }
            out.any { it.trim() == Scripts.PING_MARKER }
        } catch (e: SshException) {
            false
        }
    }

    @Test
    fun keyMatchingAgainstCabinetKeys() {
        assumeTrue("HARDEN_TEST=1 не задан", enabled)
        val cabinetPub = key("cabinet.pub")
        val otherPub = key("other.pub")
        assertEquals(SshKeys.normalizePublic(cabinetPub), SshKeys.publicOf(key("cabinet")))
        assertTrue(SshKeys.matchesAny(key("cabinet"), "", listOf(otherPub, cabinetPub)))
        assertFalse(SshKeys.matchesAny(key("other"), "", listOf(cabinetPub)))
        assertNull(SshKeys.publicOf("это не ключ"))
    }

    @Test
    fun secureWithCabinetKeyDisablesPassword() {
        assumeTrue("HARDEN_TEST=1 не задан", enabled)
        assertEquals("yes", effective("passwordauthentication"))
        val keyCfg = passwordCfg.copy(privateKey = key("cabinet"))
        val log = mutableListOf<String>()
        val result = runBlocking { Hardener(runner) { log += it; println("LOG: $it") }.harden(keyCfg, pw, updates = withUpdates) }

        assertEquals(keyCfg, result)
        assertEquals("no", effective("passwordauthentication"))
        assertTrue("по ключу вход должен работать", canLogin(keyCfg))
        assertFalse("по паролю вход должен быть отклонён", canLogin(passwordCfg))
        assertTrue(log.any { "Вход по паролю больше не принимается" in it })
        if (withUpdates) assertTrue(log.any { "Автообновления безопасности включены" in it })
    }

    @Test
    fun wrongKeyLeavesServerUntouched() {
        assumeTrue("HARDEN_TEST=1 не задан", enabled)
        val wrong = passwordCfg.copy(privateKey = key("other"))
        val e = runCatching { runBlocking { Hardener(runner) { println("LOG: $it") }.harden(wrong, pw, updates = false) } }
            .exceptionOrNull()
        assertTrue("ожидалась ошибка, получено $e", e is IllegalStateException && "не изменялся" in e.message!!)
        assertEquals("yes", effective("passwordauthentication"))
        assertTrue("пароль должен по-прежнему работать", canLogin(passwordCfg))
    }
}
