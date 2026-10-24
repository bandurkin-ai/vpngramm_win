package app.hy2setup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Живая проверка через настоящий SSH (SshRunner приложения) на стенде tools/os-test.
 * Запуск: LIVE_HOST=<ip контейнера> (root, пароль TestRootPw-123).
 */
class LiveCheckSshTest {
    private val host = System.getenv("LIVE_HOST")

    @Test
    fun startsAndReturnsResult() {
        assumeTrue("LIVE_HOST не задан", host != null)
        val runner = SshRunner(File.createTempFile("known", "hosts").apply { delete() }) { true }
        val cfg = SshConfig(host!!, 22, "root", "TestRootPw-123", "", "")
        val out = mutableListOf<String>()
        runBlocking {
            runner.runScript(cfg, Scripts.liveCheck(20, "203.0.113.5")) { out += it }
            assertTrue(out.toString(), out.any { it.trim() == Scripts.LIVE_STARTED })
            runner.runScript(cfg, Scripts.liveResult()) { out += it; println("LIVE: $it") }
        }
        assertTrue(out.toString(), out.any { it.startsWith("Живая проверка завершена") })
    }
}
