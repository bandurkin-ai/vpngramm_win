package app.hy2setup

import kotlinx.coroutines.runBlocking
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
import java.net.URLDecoder
import kotlin.concurrent.thread

/** Проверка клиента OneProvider на локальном сервере, имитирующем api.oneprovider.com. */
class OneProviderClientTest {

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
        val form: Map<String, String>
            get() = body.split("&").filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split("=", limit = 2)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
    }

    private class Resp(val code: Int, val body: String)

    private lateinit var socket: ServerSocket
    private val seen = java.util.Collections.synchronizedList(mutableListOf<Req>())
    private var handler: (Req) -> Resp = { Resp(200, """{"result":"success","response":{}}""") }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8).trimEnd('\r')
            buf.write(b)
        }
    }

    @Before
    fun start() {
        socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = try { socket.accept() } catch (e: Exception) { return@thread }
                c.use {
                    val input = it.getInputStream()
                    val line = readLine(input) ?: return@use
                    val (method, path) = line.split(" ").let { p -> p[0] to p[1] }
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val h = readLine(input) ?: break
                        if (h.isEmpty()) break
                        val i = h.indexOf(':')
                        headers[h.substring(0, i).lowercase()] = h.substring(i + 1).trim()
                    }
                    val len = headers["content-length"]?.toInt() ?: 0
                    val body = if (len > 0) String(input.readNBytes(len), Charsets.UTF_8) else ""
                    val req = Req(method, path, headers, body)
                    seen += req
                    val resp = handler(req)
                    val bytes = resp.body.toByteArray()
                    val out = it.getOutputStream()
                    out.write("HTTP/1.1 ${resp.code} X\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    @After
    fun stop() = socket.close()

    private fun client() = OneProviderClient("API-SECRET", "CLIENT-SECRET", "http://127.0.0.1:${socket.localPort}")

    private fun ok(response: String) = Resp(200, """{"result":"success","response":$response}""")

    @Test
    fun sendsAuthHeadersAndUserAgent() = runBlocking {
        handler = { ok("""{"keys":[{"uuid":"K1","name":"laptop","value":"ssh-ed25519 AAA me@pc"}]}""") }
        val keys = client().sshKeys()
        val r = seen.single()
        assertEquals("API-SECRET", r.headers["api-key"])
        assertEquals("CLIENT-SECRET", r.headers["client-key"])
        assertEquals("OneApi/1.0", r.headers["user-agent"])
        assertEquals("/vm/sshkeys/list", r.path)
        assertEquals(listOf(OpSshKey("K1", "laptop", "ssh-ed25519 AAA me@pc")), keys)
    }

    @Test
    fun listsVmsFromAllProjects() = runBlocking {
        handler = { req ->
            when (req.path) {
                "/vm/project/list" -> ok("""{"projects":[{"uuid":"p1","name":"Main"},{"uuid":"p2","name":"Other"}]}""")
                "/vm/listing/p1" -> ok("""{"project_uuid":"p1","instances":[{"id":"10","ip":"1.1.1.1","hostname":"a","billing_status":"Active","memory":"1024","storage":"20","cores":"1","bandwidth_limit":"1000","cycle_cost":"5.00","operating_system":"Ubuntu","location":"Paris - FR"}]}""")
                "/vm/listing/p2" -> ok("""{"project_uuid":"p2","instances":[{"id":"11","ip":"2.2.2.2","hostname":"b","billing_status":"Active"}]}""")
                else -> Resp(404, "{}")
            }
        }
        val vms = client().listVms()
        assertEquals(listOf("10", "11"), vms.map { it.id })
        assertEquals("1.1.1.1", vms[0].ip)
        assertEquals("1000", vms[0].bandwidthGb)
    }

    @Test
    fun parsesLocationsGroupedByRegion() = runBlocking {
        handler = {
            ok("""{"Europe":[{"id":"1","region":"Europe","country":"FR","city":"Paris","available_sizes":["44","45"]}],
                   "Asia":[{"id":"7","region":"Asia","country":"SG","city":"Singapore","available_sizes":{"0":"44"}}]}""")
        }
        val locs = client().locations()
        assertEquals(2, locs.size)
        val paris = locs.first { it.id == "1" }
        assertEquals(setOf("44", "45"), paris.sizeIds)
        assertEquals(setOf("44"), locs.first { it.id == "7" }.sizeIds)
        assertTrue(paris.title.contains("Paris"))
    }

    @Test
    fun parsesSizesSortedByPrice() = runBlocking {
        handler = {
            ok("""[{"id":"45","cores":"2","ram":"2048","hdd":"40","bandwidth":"2000","monthly_price":"8.00","creation_allowed":"1"},
                   {"id":"44","cores":"1","ram":"512","hdd":"20","bandwidth":"750","monthly_price":"4.00","creation_allowed":"1"}]""")
        }
        val sizes = client().sizes()
        assertEquals(listOf("44", "45"), sizes.map { it.id })
        assertTrue(sizes[1].summary.contains("2 ГБ RAM"))
        assertTrue(sizes[0].summary.contains("512 МБ RAM"))
        assertTrue(sizes[0].summary.contains("трафик 750 ГБ"))
    }

    @Test
    fun createSendsFormWithSshKeyArray() = runBlocking {
        handler = { ok("""{"message":"Virtual server created","id":123,"ip_address":"203.0.113.7","hostname":"vpn1","password":"rootpw"}""") }
        val created = client().createVm("1", "44", "812", "vpn1", listOf("K1", "K2"))
        val r = seen.single()
        assertEquals("POST", r.method)
        assertEquals("/vm/create", r.path)
        assertEquals("application/x-www-form-urlencoded", r.headers["content-type"])
        val f = r.form
        assertEquals("1", f["location_id"])
        assertEquals("44", f["instance_size"])
        assertEquals("812", f["template"])
        assertEquals("vpn1", f["hostname"])
        assertEquals("K1", f["ssh_keys[0]"])
        assertEquals("K2", f["ssh_keys[1]"])
        assertEquals(OpCreated("123", "203.0.113.7", "vpn1", "rootpw"), created)
    }

    @Test
    fun destroyReportsOverageThenConfirms() = runBlocking {
        handler = { req ->
            if (req.form["confirm_close"] == "1") ok("""{"message":"Virtual server destroyed"}""")
            else ok("""{"message":"Bandwidth overage fees will be charged ... include the parameter confirm_close","usageHours":"42","bandwidthOverusage":"420","bandwidthOverusageCost":"21","additionalHoursForCharge":"18"}""")
        }
        val first = client().destroyVm("123", confirmClose = false)
        assertFalse(first.destroyed)
        assertEquals("21", first.overageCost)
        assertEquals("420", first.overageGb)
        val second = client().destroyVm("123", confirmClose = true)
        assertTrue(second.destroyed)
        assertEquals("123", seen.last().form["vm_id"])
    }

    @Test
    fun errorCode8xxIsReported() {
        handler = { Resp(801, """{"result":"error","error":{"message":"Invalid API key","code":801}}""") }
        val e = runCatching { runBlocking { client().sshKeys() } }.exceptionOrNull()
        assertTrue(e is OneProviderException)
        assertTrue(e!!.message!!.contains("Invalid API key"))
        assertFalse(e.message!!.contains("SECRET"))
    }

    @Test
    fun preferredTemplateIsNewestUbuntu() {
        val list = listOf(
            OpTemplate("1", "CentOS 7"), OpTemplate("2", "Ubuntu 20.04 64bits"),
            OpTemplate("3", "Ubuntu 24.04 64bits"), OpTemplate("4", "Ubuntu 24.04 cPanel"), OpTemplate("5", "Debian 12"),
        )
        assertEquals("3", OneProviderClient.preferredTemplate(list)!!.id)
    }

    @Test
    fun addAndDeleteSshKey() = runBlocking {
        handler = { req ->
            if (req.path == "/vm/sshkey/new") ok("""{"keys":[{"uuid":"F0ur","name":"${req.form["key_name"]}","value":"${req.form["key_value"]}"}]}""")
            else ok("""{"message":"SSH Key successfully deleted"}""")
        }
        val key = client().addSshKey("phone", "ssh-ed25519 AAAAC3Nza me@phone")
        val r = seen.first()
        assertEquals("POST", r.method)
        assertEquals("/vm/sshkey/new", r.path)
        assertEquals("phone", r.form["key_name"])
        assertEquals("ssh-ed25519 AAAAC3Nza me@phone", r.form["key_value"])
        assertEquals(OpSshKey("F0ur", "phone", "ssh-ed25519 AAAAC3Nza me@phone"), key)
        client().deleteSshKey("F0ur")
        assertEquals("/vm/sshkey/delete", seen.last().path)
        assertEquals("F0ur", seen.last().form["ssh_key"])
    }

    @Test
    fun publicKeyValidation() {
        assertEquals("ssh-ed25519 AAAAC3Nz+/= me@phone", OneProviderClient.normalizePublicKey("  ssh-ed25519   AAAAC3Nz+/=  me@phone \n"))
        assertEquals("ssh-rsa AAAAB3", OneProviderClient.normalizePublicKey("ssh-rsa AAAAB3"))
        assertEquals("ecdsa-sha2-nistp256 AAAAE2 x", OneProviderClient.normalizePublicKey("ecdsa-sha2-nistp256 AAAAE2 x"))
        assertEquals(null, OneProviderClient.normalizePublicKey("-----BEGIN OPENSSH PRIVATE KEY-----\nb3Blbn\n-----END OPENSSH PRIVATE KEY-----"))
        assertEquals(null, OneProviderClient.normalizePublicKey("hello"))
        assertEquals(null, OneProviderClient.normalizePublicKey(""))
    }
}
