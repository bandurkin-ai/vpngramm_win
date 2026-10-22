package app.hy2setup

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread

/** Проверка клиента name.com на локальном сервере, имитирующем Core API. */
class NameComClientTest {

    private class Req(val method: String, val path: String, val auth: String?, val body: String)
    private class Resp(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    private lateinit var socket: ServerSocket
    private val seen = java.util.Collections.synchronizedList(mutableListOf<Req>())
    private var handler: (Req) -> Resp = { Resp(200, "{}") }
    private val port get() = socket.localPort

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8).trimEnd('\r')
            buf.write(b)
        }
    }

    private fun serve() {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (e: Exception) { return }
            client.use { c ->
                val input = c.getInputStream()
                val requestLine = readLine(input) ?: return@use
                val (method, path) = requestLine.split(" ").let { it[0] to it[1] }
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    headers[line.substring(0, i).lowercase()] = line.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toInt() ?: 0
                val body = if (len > 0) String(input.readNBytes(len), Charsets.UTF_8) else ""
                val req = Req(method, path, headers["authorization"], body)
                seen += req
                val resp = handler(req)
                val bytes = resp.body.toByteArray()
                val out = c.getOutputStream()
                val head = StringBuilder("HTTP/1.1 ${resp.code} X\r\nConnection: close\r\nContent-Type: application/json\r\n")
                resp.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                head.append("Content-Length: ${bytes.size}\r\n\r\n")
                out.write(head.toString().toByteArray())
                out.write(bytes)
                out.flush()
            }
        }
    }

    @Before
    fun start() {
        socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { serve() }
    }

    @After
    fun stop() = socket.close()

    private fun client(user: String = "alice", token: String = "s3cret") =
        NameComClient(user, token, "http://127.0.0.1:$port/core/v1")

    @Test
    fun basicAuthHeaderIsUsernameColonToken() = runBlocking {
        handler = { Resp(200, """{"serverName":"x"}""") }
        client().hello()
        val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        assertEquals(expected, seen.single().auth)
        assertEquals("/core/v1/hello", seen.single().path)
    }

    @Test
    fun listRecordsFollowsPagination() = runBlocking {
        handler = { req ->
            if (req.path.contains("page=1")) {
                Resp(200, """{"records":[{"id":1,"type":"A","host":"vpn","fqdn":"vpn.example.com.","answer":"1.2.3.4","ttl":300}],"nextPage":2}""")
            } else {
                // корневая запись: пустые поля API не присылает
                Resp(200, """{"records":[{"id":2,"type":"NS","fqdn":"example.com.","answer":"ns1.name.com","ttl":300}]}""")
            }
        }
        val list = client().listRecords("example.com")
        assertEquals(2, list.size)
        assertEquals("vpn.example.com", list[0].displayName)
        assertEquals("1.2.3.4", list[0].answer)
        assertEquals("", list[1].host)
        assertTrue(seen.all { it.path.startsWith("/core/v1/domains/example.com/records?perPage=1000&page=") })
        assertEquals(2, seen.size)
    }

    @Test
    fun createRecordSendsExpectedJson() = runBlocking {
        handler = { Resp(200, """{"id":77,"type":"A","host":"vpn","fqdn":"vpn.example.com.","answer":"203.0.113.10","ttl":300}""") }
        val rec = client().createRecord("example.com", "vpn", "A", "203.0.113.10", 300)
        val s = seen.single()
        assertEquals("POST", s.method)
        assertEquals("/core/v1/domains/example.com/records", s.path)
        val body = JSONObject(s.body)
        assertEquals("vpn", body.getString("host"))
        assertEquals("A", body.getString("type"))
        assertEquals("203.0.113.10", body.getString("answer"))
        assertEquals(300, body.getInt("ttl"))
        assertEquals(77, rec.id)
    }

    @Test
    fun createApexRecordOmitsHost() = runBlocking {
        handler = { Resp(200, """{"id":5,"type":"A","fqdn":"example.com.","answer":"203.0.113.10","ttl":300}""") }
        client().createRecord("example.com", "", "A", "203.0.113.10", 300)
        assertFalse(JSONObject(seen.single().body).has("host"))
    }

    @Test
    fun mxRecordSendsPriority() = runBlocking {
        handler = { Resp(200, """{"id":9,"type":"MX","host":"","fqdn":"example.com.","answer":"mail.example.com","ttl":300,"priority":10}""") }
        client().createRecord("example.com", "", "MX", "mail.example.com", 300, priority = 10)
        val body = JSONObject(seen.single().body)
        assertEquals(10, body.getInt("priority"))
        assertEquals("MX", body.getString("type"))
    }

    @Test
    fun nonMxRecordHasNoPriority() = runBlocking {
        handler = { Resp(200, """{"id":10,"type":"TXT","host":"_x","fqdn":"_x.example.com.","answer":"hello","ttl":300}""") }
        client().createRecord("example.com", "_x", "TXT", "hello", 300)
        assertFalse(JSONObject(seen.single().body).has("priority"))
    }

    @Test
    fun deleteRecordUsesDeleteAndId() = runBlocking {
        handler = { Resp(204, "") }
        client().deleteRecord("example.com", 42)
        assertEquals("DELETE", seen.single().method)
        assertEquals("/core/v1/domains/example.com/records/42", seen.single().path)
    }

    @Test
    fun unauthorizedGivesFriendlyMessage() {
        handler = { Resp(401, """{"message":"Unauthorized"}""") }
        val e = runCatching { runBlocking { client().hello() } }.exceptionOrNull() as NameComException
        assertEquals(401, e.httpCode)
        assertTrue(e.message!!.contains("логин"))
        assertFalse(e.message!!.contains("s3cret"))
    }

    @Test
    fun twoFactorErrorExplainsHowToFix() {
        handler = { Resp(403, """{"message":"Permission Denied","details":"Authentication Error - Account Has Two-Step Verification Enabled"}""") }
        val e = runCatching { runBlocking { client().hello() } }.exceptionOrNull() as NameComException
        assertTrue(e.message!!.contains("двухфакторная"))
    }

    @Test
    fun notFoundMentionsDomain() {
        handler = { Resp(404, """{"message":"Not Found","details":"The requested domain does not exist."}""") }
        val e = runCatching { runBlocking { client().listRecords("nope.com") } }.exceptionOrNull() as NameComException
        assertTrue(e.message!!.contains("домен"))
    }

    @Test
    fun rateLimitIsRetried() = runBlocking {
        var calls = 0
        handler = {
            calls++
            if (calls == 1) Resp(429, """{"message":"Rate Limit Exceeded"}""", mapOf("Retry-After" to "1"))
            else Resp(200, """{"records":[]}""")
        }
        val list = client().listRecords("example.com")
        assertEquals(0, list.size)
        assertEquals(2, calls)
    }

    @Test
    fun connectionErrorIsReported() {
        val dead = NameComClient("a", "b", "http://127.0.0.1:1/core/v1")
        val e = runCatching { runBlocking { dead.hello() } }.exceptionOrNull()
        assertTrue(e is NameComException)
    }
}
