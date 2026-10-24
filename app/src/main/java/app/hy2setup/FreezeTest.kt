package app.hy2setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Проверка «заморозки после ~16 КБ» (ТСПУ): соединение с зарубежным сервером работает, пока данных мало,
 * а потом замирает. Скачиваем большую страницу: через наш сервер (Reality отдаёт неавторизованным подключениям
 * сайт маскировки) и напрямую — для сравнения.
 */
object FreezeTest {

    data class Result(val bytes: Int, val complete: Boolean, val error: String?) {
        val kb: Int get() = bytes / 1024
    }

    /** Сколько данных нужно получить, чтобы считать, что заморозки нет. */
    const val ENOUGH = 64 * 1024

    /**
     * GET [path] у [sni] по TLS, подключаясь к [connectHost] (IP сервера или сам сайт).
     * Читает, пока не придёт [ENOUGH] байт, конец ответа или [idleMs] без данных.
     */
    suspend fun download(
        connectHost: String, sni: String, path: String = "/", port: Int = 443,
        idleMs: Int = 8_000, tls: Boolean = true, redirects: Int = 2,
    ): Result = withContext(Dispatchers.IO) {
        var total = 0
        try {
            val raw = Socket()
            raw.connect(InetSocketAddress(connectHost, port), 8_000)
            raw.soTimeout = idleMs
            val sock: Socket = if (tls) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni, port, true).also {
                    (it as SSLSocket).startHandshake()
                }
            } else raw
            sock.use { s ->
                s.getOutputStream().apply {
                    write(("GET $path HTTP/1.1\r\nHost: $sni\r\nUser-Agent: Mozilla/5.0 (Linux; Android 14)\r\n" +
                        "Accept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").toByteArray())
                    flush()
                }
                val head = StringBuilder()
                val input: InputStream = s.getInputStream()
                val buf = ByteArray(16 * 1024)
                var eof = false
                try {
                    while (total < ENOUGH) {
                        val n = input.read(buf)
                        if (n < 0) { eof = true; break }
                        if (head.length < 2048) head.append(String(buf, 0, minOf(n, 2048), Charsets.ISO_8859_1))
                        total += n
                    }
                } catch (e: SocketTimeoutException) {
                    return@withContext Result(total, false, "нет данных ${idleMs / 1000} с")
                }
                // короткий ответ-перенаправление — идём по нему, чтобы скачать что-то большое
                val loc = Regex("(?im)^location:\\s*(\\S+)").find(head)?.groupValues?.get(1)
                if (eof && total < ENOUGH && redirects > 0 && loc != null && head.startsWith("HTTP/1.1 3")) {
                    val p = Regex("^https?://[^/]+").replace(loc, "").ifEmpty { "/" }
                    return@withContext download(connectHost, sni, p, port, idleMs, tls, redirects - 1)
                }
                Result(total, true, null)
            }
        } catch (e: Exception) {
            Result(total, false, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
