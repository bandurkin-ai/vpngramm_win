package app.hy2setup.hwkey

import app.hy2setup.Scripts
import app.hy2setup.SshConfig
import app.hy2setup.SshException
import app.hy2setup.SshRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Вход по ключу аппаратного токена на настоящий OpenSSH: программный U2F-токен вместо Рутокена.
 * Запускается, если задан SSH_TEST_DIR — каталог с authorized_keys запущенного sshd на 127.0.0.1:2222 (как SshKeyAuthTest).
 */
class HwKeySshTest {
    private val dir = System.getenv("SSH_TEST_DIR")

    private fun login(token: FakeU2fToken, key: HwKey): List<String> = runBlocking {
        val known = File.createTempFile("known", "hosts").apply { delete() }
        val runner = SshRunner(known, hwTransport = { token }) { true }
        val lines = mutableListOf<String>()
        val code = runner.runScript(SshConfig("127.0.0.1", 2222, "root", "", "", "", hwKey = key), "#!/bin/bash\necho HWOK\n") { lines += it }
        assertEquals(0, code)
        lines
    }

    private fun authorize(key: HwKey) = File(dir, "authorized_keys").appendText(key.authorizedKeysLine() + "\n")

    @Test
    fun loginWithTouch() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val token = FakeU2fToken(touchesNeeded = 2)
        val key = U2f.register(token)
        authorize(key)
        token.touchesNeeded = 3   // при входе токен дважды-трижды просит касания, потом подписывает
        assertTrue(login(token, key).contains("HWOK"))
    }

    private fun loginFido(token: FakeFidoToken, key: HwKey): List<String> = runBlocking {
        val known = File.createTempFile("known", "hosts").apply { delete() }
        val runner = SshRunner(known, hwTransport = { token }) { true }
        val lines = mutableListOf<String>()
        val code = runner.runScript(SshConfig("127.0.0.1", 2222, "root", "", "", "", hwKey = key), "#!/bin/bash\necho HWOK\n") { lines += it }
        assertEquals(0, code)
        lines
    }

    /** YubiKey: ed25519-sk, нерезидентный и резидентный (PIN только при создании). */
    @Test
    fun ed25519SkLogin() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val token = FakeFidoToken(pin = "2468")
        val plain = Hw.create(token, resident = false) { null }
        val resident = Hw.create(token, resident = true) { "2468" }
        authorize(plain); authorize(resident)
        assertTrue(loginFido(token, plain).contains("HWOK"))
        assertTrue(loginFido(token, resident).contains("HWOK"))
        // загруженный с токена резидентный ключ тоже входит
        val loaded = Hw.loadResident(token) { "2468" }.single()
        assertTrue(loginFido(token, loaded).contains("HWOK"))
    }

    /** Рутокен MFA через FIDO2: ecdsa-sk; без касания сервер отказывает. */
    @Test
    fun ecdsaSkViaCtap2() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val token = FakeFidoToken(ed25519 = false)
        val key = Hw.create(token, resident = false) { null }
        authorize(key)
        assertTrue(loginFido(token, key).contains("HWOK"))
        token.userPresent = false
        assertTrue(runCatching { loginFido(token, key) }.exceptionOrNull() is SshException)
    }

    @Test
    fun unknownKeyIsExplained() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val key = U2f.register(FakeU2fToken())          // на сервер не добавлен
        val e = runCatching { login(FakeU2fToken(), key) }.exceptionOrNull()
        assertTrue("$e", e is SshException)
    }

    @Test
    fun withoutUserPresenceServerRefuses() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val token = FakeU2fToken()
        val key = U2f.register(token)
        authorize(key)
        token.userPresent = false
        val e = runCatching { login(token, key) }.exceptionOrNull()
        assertTrue("$e", e is SshException)
    }

    /** Ключ, созданный ssh-keygen с -O application=ssh:myname: загрузка находит его на токене, вход касанием работает. */
    @Test
    fun customApplicationResidentKey() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val token = FakeFidoToken(pin = "2468")
        val info = Ctap2.info(token)
        fun mc(app: String) = Ctap2.makeCredential(token, info, Ctap2.EDDSA, true, Ctap2.pinToken(token, info, "2468", Ctap2.PERM_MC, app), app)
        val named = mc("ssh:myname")
        mc("example.com")   // ключ сайта (WebAuthn) — не для SSH
        val loaded = Hw.loadResident(token) { "2468" }
        assertEquals(listOf("ssh:myname"), loaded.map { it.application })
        assertEquals(named.authorizedKeysLine(), loaded.single().authorizedKeysLine())
        authorize(named)
        assertTrue(loginFido(token, loaded.single()).contains("HWOK"))
    }

    /** «Ключи для всех серверов»: скрипт ставит ключи в ~/.ssh/authorized_keys один раз (стенд — пользователь root). */
    @Test
    fun defaultKeysInstalledOnce() = runBlocking {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val ak = File(System.getProperty("user.home"), ".ssh/authorized_keys")
        val before = if (ak.exists()) ak.readText() else null
        try {
            val token = FakeFidoToken()
            val fido = Hw.create(token, resident = false) { null }
            val edPub = File(dir, "k_ed25519.pub").readText().trim().split(" ").take(2).joinToString(" ") + " list-key"
            val cfg = SshConfig("127.0.0.1", 2222, "root", "", File(dir, "k_ed25519").readText(), "")
            val runner = SshRunner(File.createTempFile("known", "hosts").apply { delete() }) { true }
            val out = mutableListOf<String>()
            val lines = listOf(fido.authorizedKeysLine("yk-test"), edPub)
            assertEquals(0, runner.runScript(cfg, Scripts.addAuthorizedKeys(lines)) { out += it })
            assertEquals(0, runner.runScript(cfg, Scripts.addAuthorizedKeys(lines)) { out += it })
            val text = ak.readText()
            assertEquals(1, text.lines().count { it == fido.authorizedKeysLine("yk-test") })
            assertEquals(1, text.lines().count { it == edPub })
            before?.let { assertTrue(text.startsWith(it.trimEnd())) }
            assertTrue(out.any { "уже есть" in it })
        } finally {
            if (before == null) ak.delete() else ak.writeText(before)
        }
    }
}
