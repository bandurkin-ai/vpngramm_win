package app.hy2setup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Повтор SSH-подключения при временных ошибках: сервер принял TCP, но молчит (как перегруженный sshd).
 * Нужен стенд sshd на 127.0.0.1:2222 (SSH_TEST_DIR).
 */
class SshRetryTest {
    private val dir = System.getenv("SSH_TEST_DIR")

    /** Прокси на sshd стенда: первые [silent] соединений принимает и молчит, остальные пересылает. */
    private class Proxy(private val silent: Int) : AutoCloseable {
        val server = ServerSocket(0)
        val accepted = AtomicInteger()
        private val held = mutableListOf<Socket>()
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val c = runCatching { server.accept() }.getOrNull() ?: break
                    if (accepted.incrementAndGet() <= silent) { synchronized(held) { held += c }; continue }
                    val up = Socket("127.0.0.1", 2222)
                    thread(isDaemon = true) { runCatching { c.getInputStream().copyTo(up.getOutputStream()) }; runCatching { up.close() } }
                    thread(isDaemon = true) { runCatching { up.getInputStream().copyTo(c.getOutputStream()) }; runCatching { c.close() } }
                }
            }
        }
        override fun close() { server.close(); synchronized(held) { held.forEach { runCatching { it.close() } } } }
    }

    private fun runner() = SshRunner(
        File.createTempFile("known", "hosts").apply { delete() },
        connectTimeoutMs = 2_000, connectAttempts = 3, retryDelaysMs = listOf(100, 100),
    ) { true }

    private fun cfg(port: Int) = SshConfig("127.0.0.1", port, "root", "", File(dir, "k_ed25519").readText(), "")

    @Test
    fun silentFirstConnectionIsRetried() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        Proxy(silent = 1).use { p ->
            val out = mutableListOf<String>()
            val code = runBlocking { runner().runScript(cfg(p.server.localPort), "#!/bin/bash\necho RETRY-OK\n") { out += it } }
            assertEquals(0, code)
            assertEquals(1, out.count { it == "RETRY-OK" })
            assertTrue(out.joinToString("\n"), out.any { it.startsWith("SSH не ответил вовремя") && "попытка 2 из 3" in it })
            assertEquals(2, p.accepted.get())
        }
    }

    @Test
    fun alwaysSilentGivesClearMessage() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        Proxy(silent = 100).use { p ->
            val e = runCatching { runBlocking { runner().runScript(cfg(p.server.localPort), "#!/bin/bash\necho X\n") {} } }.exceptionOrNull()
            assertTrue(e.toString(), e is SshException && "не ответил по SSH" in e.message.orEmpty() && "попыток: 3" in e.message.orEmpty())
            assertEquals(3, p.accepted.get())
        }
    }

    @Test
    fun wrongPasswordIsNotRetried() {
        assumeTrue("SSH_TEST_DIR не задан", dir != null)
        Proxy(silent = 0).use { p ->
            val e = runCatching {
                runBlocking { runner().runScript(SshConfig("127.0.0.1", p.server.localPort, "root", "wrong-password", "", ""), "#!/bin/bash\n") {} }
            }.exceptionOrNull()
            assertTrue(e.toString(), e is SshException)
            assertEquals(1, p.accepted.get())
        }
    }
}
