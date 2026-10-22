package app.hy2setup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class FreezeTestTest {

    /** Сервер отдаёт [send] байт и потом либо закрывает соединение, либо «замерзает». */
    private fun server(send: Int, freeze: Boolean): Int {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            ss.accept().use { s ->
                s.getInputStream().read(ByteArray(4096))
                val out = s.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                var left = send
                while (left > 0) { val n = minOf(left, 4096); out.write(ByteArray(n)); left -= n }
                out.flush()
                if (freeze) Thread.sleep(5_000)
            }
            ss.close()
        }
        return ss.localPort
    }

    @Test
    fun bigDownloadIsOk() = runBlocking {
        val r = FreezeTest.download("127.0.0.1", "example.com", port = server(200_000, false), tls = false)
        assertTrue(r.bytes >= FreezeTest.ENOUGH)
    }

    @Test
    fun freezeAfter16kIsDetected() = runBlocking {
        val r = FreezeTest.download("127.0.0.1", "example.com", port = server(15_000, true), idleMs = 1_500, tls = false)
        assertFalse(r.complete)
        assertEquals(14, r.kb)
    }
}
