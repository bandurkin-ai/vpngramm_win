package app.hy2setup

import app.hy2setup.platform.DesktopPlatform
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Откат незавершённого переезда: A-запись и профиль возвращаются на прежний сервер, новый VPS удаляется или остаётся
 * в «Свободных серверах». name.com и OneProvider — локальные поддельные серверы.
 */
class RollbackDesktopTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Req(val method: String, val path: String, val body: String)

    /** Минимальный HTTP-сервер: записывает запросы и отвечает тем, что вернёт [handler] (код, тело). */
    private class Fake(var handler: (Req) -> Pair<Int, String>) : AutoCloseable {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val seen = java.util.Collections.synchronizedList(mutableListOf<Req>())
        val url get() = "http://127.0.0.1:${socket.localPort}"
        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val c = runCatching { socket.accept() }.getOrNull() ?: break
                    c.use {
                        val input = it.getInputStream()
                        val first = line(input) ?: return@use
                        val (method, path) = first.split(" ").let { p -> p[0] to p[1] }
                        var len = 0
                        while (true) {
                            val l = line(input) ?: break
                            if (l.isEmpty()) break
                            if (l.lowercase().startsWith("content-length:")) len = l.substringAfter(':').trim().toInt()
                        }
                        val body = if (len > 0) String(input.readNBytes(len), Charsets.UTF_8) else ""
                        val req = Req(method, path, body)
                        seen += req
                        val (code, text) = handler(req)
                        val bytes = text.toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $code X\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            }
        }
        private fun line(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
                if (b == '\n'.code) return buf.toString(Charsets.UTF_8).trimEnd('\r')
                buf.write(b)
            }
        }
        override fun close() = socket.close()
    }

    private val nameCom = Fake { req ->
        when {
            req.path.endsWith("/hello") -> 200 to """{"serverName":"x"}"""
            req.method == "GET" && req.path.contains("/domains/example.org/records") ->
                200 to """{"records":[{"id":7,"type":"A","host":"vpn","fqdn":"vpn.example.org.","answer":"198.51.100.9","ttl":300}]}"""
            req.method == "DELETE" -> 200 to "{}"
            req.method == "POST" -> 200 to """{"id":8,"type":"A","host":"vpn","fqdn":"vpn.example.org.","answer":"203.0.113.10","ttl":300}"""
            else -> 404 to "{}"
        }
    }
    private val oneProvider = Fake { 200 to """{"result":"success","response":{"message":"Virtual server destroyed"}}""" }

    @After
    fun stop() { nameCom.close(); oneProvider.close() }

    private val oldVps = ProfileVps("OLD1", "203.0.113.10", "old-host")
    private val newVps = ProfileVps("NEW1", "198.51.100.9", "new-host")

    /** Сервер Главной посреди неудачного переезда: профиль уже на новом VPS, A-запись переведена. */
    private fun vmMidMove(dir: File, bought: Boolean = true, dnsChanged: Boolean = true): MainViewModel {
        val vm = MainViewModel(DesktopPlatform(dir))
        val before = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/").copy(vps = oldVps)
        ui {
            vm.plusProfile = before.copy(vps = newVps)
            vm.ncUser = "alice"; vm.ncToken = "token"; vm.opApiKey = "API"; vm.opClientKey = "CLIENT"
            vm.persist()
            vm.testNcUrl = nameCom.url + "/core/v1"; vm.testOpUrl = oneProvider.url
            vm.setRollbackForTest(
                MoveRollback(
                    fqdn = "vpn.example.org", before = before.toJson(), oldIp = oldVps.ip,
                    newId = newVps.id, newIp = newVps.ip, newHost = newVps.hostname, bought = bought, dnsChanged = dnsChanged,
                ),
            )
        }
        return vm
    }

    private fun ui(block: () -> Unit) = javax.swing.SwingUtilities.invokeAndWait(block)

    private fun waitIdle(vm: MainViewModel) {
        val end = System.currentTimeMillis() + 30_000
        Thread.sleep(200)
        while (vm.running && System.currentTimeMillis() < end) Thread.sleep(50)
        assertFalse("задача не закончилась", vm.running)
    }

    @Test
    fun rollbackRestoresDnsAndProfileAndDeletesNewVps() {
        val dir = tmp.newFolder()
        val vm = vmMidMove(dir)
        assertEquals(2, vm.plusRollback!!.summary().size)
        ui { vm.plusRollbackRun(deleteNew = true) }
        waitIdle(vm)
        val log = vm.plusLog.joinToString("\n")
        // A-запись: старая (на новый IP) удалена, создана на прежний IP
        assertTrue(log, nameCom.seen.any { it.method == "DELETE" && it.path.endsWith("/records/7") })
        assertTrue(log, nameCom.seen.any { it.method == "POST" && "203.0.113.10" in it.body && "\"A\"" in it.body })
        // новый VPS удалён
        assertTrue(log, oneProvider.seen.any { it.path == "/vm/destroy" && "vm_id=NEW1" in it.body })
        assertFalse(oneProvider.seen.any { "confirm_close" in it.body })   // перерасход без спроса не оплачиваем
        // профиль снова на прежнем VPS, откат снят и после перезапуска не возвращается
        assertEquals(oldVps, vm.plusProfile!!.vps)
        assertNull(vm.plusRollback)
        assertTrue(vm.combineServers.none { it.ip == newVps.ip })
        assertTrue(log, log.contains("✓ Откат выполнен"))
        val again = MainViewModel(DesktopPlatform(dir))
        assertNull(again.plusRollback)
        assertEquals(oldVps, again.plusProfile!!.vps)
    }

    @Test
    fun rollbackKeepingNewVpsMakesItFreeServer() {
        val vm = vmMidMove(tmp.newFolder())
        ui { vm.plusRollbackRun(deleteNew = false) }
        waitIdle(vm)
        assertTrue(oneProvider.seen.isEmpty())
        assertEquals(oldVps, vm.plusProfile!!.vps)
        val free = vm.plusTargets.single()
        assertEquals("NEW1", free.key)
        assertEquals("198.51.100.9", free.ip)
        assertNull(vm.plusRollback)
    }

    @Test
    fun dnsNotTouchedWhenItWasNotChanged() {
        val vm = vmMidMove(tmp.newFolder(), dnsChanged = false)
        ui { vm.plusRollbackRun(deleteNew = true) }
        waitIdle(vm)
        assertTrue(nameCom.seen.isEmpty())
        assertEquals(oldVps, vm.plusProfile!!.vps)
        assertNull(vm.plusRollback)
    }

    @Test
    fun nameComErrorKeepsRollbackForRetry() {
        val vm = vmMidMove(tmp.newFolder())
        val good = nameCom.handler
        nameCom.handler = { 401 to """{"message":"Unauthenticated"}""" }
        ui { vm.plusRollbackRun(deleteNew = true) }
        waitIdle(vm)
        // ничего не потеряно: профиль ещё на новом VPS, VPS не удалён, откат можно повторить
        assertNotNull(vm.plusRollback)
        assertEquals(newVps, vm.plusProfile!!.vps)
        assertTrue(oneProvider.seen.isEmpty())
        assertTrue(vm.plusLog.any { it.startsWith("ОШИБКА") })
        nameCom.handler = good
        ui { vm.plusRollbackRun(deleteNew = true) }
        waitIdle(vm)
        assertNull(vm.plusRollback)
        assertEquals(oldVps, vm.plusProfile!!.vps)
    }

    @Test
    fun rollbackSurvivesRestartAndDisappearsWithServer() {
        val dir = tmp.newFolder()
        vmMidMove(dir)
        val again = MainViewModel(DesktopPlatform(dir))
        val rb = again.plusRollback
        assertNotNull(rb)
        assertEquals("203.0.113.10", rb!!.oldIp)
        assertTrue(rb.bought && rb.dnsChanged)
        // без токена name.com откат не начинается и объясняет, что нужно
        ui { again.ncToken = ""; again.plusRollbackRun(deleteNew = false) }
        assertTrue(again.plusError.orEmpty().contains("name.com"))
        assertFalse(again.running)
        // «Скрыть» убирает предложение, сервер не трогается
        ui { again.plusRollbackDismiss() }
        assertNull(again.plusRollback)
        assertEquals(newVps, again.plusProfile!!.vps)
        assertNull(MainViewModel(DesktopPlatform(dir)).plusRollback)
    }

    @Test
    fun brokenStateIsIgnored() {
        assertNull(MoveRollback.fromJson(""))
        assertNull(MoveRollback.fromJson("{not json"))
        assertNull(MoveRollback.fromJson("""{"fqdn":"a.b","before":"{}","oldIp":"1.2.3.4"}"""))   // профиль не читается
    }
}
