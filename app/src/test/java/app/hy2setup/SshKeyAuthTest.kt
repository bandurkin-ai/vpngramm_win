package app.hy2setup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Интеграционный тест входа по SSH-ключу на настоящий sshd.
 * Запускается, только если задана переменная SSH_TEST_DIR (каталог с ключами и запущенным sshd на 127.0.0.1:2222).
 */
class SshKeyAuthTest {

    private val dir = System.getenv("SSH_TEST_DIR")

    private fun login(keyFile: String, passphrase: String = "", mangle: (String) -> String = { it }): Pair<Int, List<String>> = runBlocking {
        val known = File.createTempFile("known", "hosts").apply { delete() }
        val runner = SshRunner(known) { true }
        val cfg = SshConfig(
            host = "127.0.0.1", port = 2222, user = "root", password = "",
            privateKey = mangle(File(dir, keyFile).readText()), keyPassphrase = passphrase,
        )
        val lines = mutableListOf<String>()
        val code = runner.runScript(cfg, "#!/bin/bash\necho KEYOK \$(id -un)\n") { lines += it }
        code to lines
    }

    private fun check(keyFile: String, passphrase: String = "") {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val (code, lines) = login(keyFile, passphrase)
        assertEquals("код выхода для $keyFile", 0, code)
        assertTrue("вывод для $keyFile: $lines", lines.contains("KEYOK root"))
    }

    private fun errorOf(keyFile: String, passphrase: String = ""): String {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val e = runCatching { login(keyFile, passphrase) }.exceptionOrNull()
        assertTrue("ожидалась ошибка SshException, получено $e", e is SshException)
        println("MSG[$keyFile]: ${e!!.message} | cause=${e.cause}")
        return e.message!!
    }

    @Test fun wrongPassphraseIsExplained() {
        assertTrue(errorOf("k_ed25519_pass", "wrong").contains("пароль от ключа"))
    }

    @Test fun missingPassphraseIsExplained() {
        assertTrue(errorOf("k_ed25519_pass", "").contains("пароль от ключа"))
    }

    @Test fun keyNotOnServerIsExplained() {
        assertTrue(errorOf("k_unknown").contains("authorized_keys"))
    }

    @Test fun publicKeyPastedIsExplained() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val e = runCatching { login("k_ed25519.pub") }.exceptionOrNull()
        assertTrue(e is SshException && e.message!!.contains("публичный ключ"))
    }

    @Test fun keyPastedAsOneLineStillWorks() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val (code, lines) = login("k_ed25519", mangle = { it.replace("\n", " ") })
        assertEquals(0, code)
        assertTrue(lines.contains("KEYOK root"))
    }

    @Test fun keyWithWindowsLineEndingsWorks() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        val (code, _) = login("k_rsa_pem", mangle = { it.replace("\n", "\r\n") })
        assertEquals(0, code)
    }

    @Test fun ed25519() = check("k_ed25519")
    @Test fun ed25519WithPassphrase() = check("k_ed25519_pass", "pp123")
    @Test fun rsaOpenSshFormat() = check("k_rsa_openssh")
    @Test fun rsaPemFormat() = check("k_rsa_pem")
    @Test fun ecdsa() = check("k_ecdsa")
}
