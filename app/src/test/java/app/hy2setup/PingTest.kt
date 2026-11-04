package app.hy2setup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PingTest {
    private val moscow = 55.76 to 37.62

    @Test
    fun estimateGrowsWithDistance() {
        val ams = Ping.estimateMs(moscow, Ping.cityCoord("Amsterdam")!!)
        val ny = Ping.estimateMs(moscow, Ping.cityCoord("New York")!!)
        val sg = Ping.estimateMs(moscow, Ping.cityCoord("Singapore")!!)
        assertTrue("Амстердам $ams", ams in 30..50)          // ~2150 км
        assertTrue("Нью-Йорк $ny", ny in 105..130)            // ~7500 км
        assertTrue(sg > ny)
        assertEquals(5, Ping.estimateMs(moscow, moscow))
    }

    @Test
    fun cityNamesAreMatchedLoosely() {
        assertNotNull(Ping.cityCoord("São Paulo"))
        assertNotNull(Ping.cityCoord("frankfurt am main"))
        assertNotNull(Ping.cityCoord("Düsseldorf"))
        assertNotNull(Ping.cityCoord("Ho Chi Minh City"))
        assertNull(Ping.cityCoord("Nowhereville"))
    }

    @Test
    fun testHostFromLocationJson() {
        fun host(j: String) = OneProviderClient.testHostOf(org.json.JSONObject(j))
        assertEquals("203.0.113.5", host("""{"test_ip":"203.0.113.5"}"""))
        assertEquals("lg.ams.example.net", host("""{"looking_glass":"https://lg.ams.example.net/"}"""))
        assertEquals("", host("""{"city":"Paris"}"""))
        assertEquals("", host("""{"test_ip":"null"}"""))
    }

    @Test
    fun tcpRttOnLocalPort() = runBlocking {
        java.net.ServerSocket(0).use { s ->
            val ms = Ping.tcpRttMs("127.0.0.1", listOf(s.localPort), tries = 2)
            assertNotNull(ms)
            assertTrue("$ms", ms!! in 1..500)
        }
        // закрытый порт: отказ — тоже ответ
        val free = java.net.ServerSocket(0).use { it.localPort }
        assertNotNull(Ping.tcpRttMs("127.0.0.1", listOf(free), tries = 1))
        assertEquals("≈ 60 мс", PingResult(60, estimated = true).title)
        assertEquals("42 мс", PingResult(42, estimated = false).title)
    }
}
