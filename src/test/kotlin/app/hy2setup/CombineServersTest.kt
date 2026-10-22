package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Test

class CombineServersTest {
    private val a = CombineServer("101", "203.0.113.1", "vpn1.example.org", "vpn1", "Amsterdam, Netherlands", 1_700_000_000_000,
        listOf("VLESS + Reality", "Hysteria2"), secured = true)
    private val b = CombineServer("102", "203.0.113.2", "vpn2.example.org", origin = CombineServer.ORIGIN_PROFILE)

    @Test
    fun roundTrip() {
        assertEquals(listOf(a, b), CombineServers.decode(CombineServers.encode(listOf(a, b))))
    }

    @Test
    fun garbageGivesEmpty() {
        assertEquals(emptyList<CombineServer>(), CombineServers.decode(null))
        assertEquals(emptyList<CombineServer>(), CombineServers.decode("{oops"))
    }

    @Test
    fun upsertReplacesSameVpsOrIpAndPutsFirst() {
        val again = a.copy(fqdn = "vpn9.example.org")
        assertEquals(listOf(again, b), CombineServers.upsert(listOf(a, b), again))
        val sameIp = b.copy(vpsId = "999")
        assertEquals(listOf(sameIp, a), CombineServers.upsert(listOf(a, b), sameIp))
    }

    @Test
    fun titleFallsBackToHostnameAndIp() {
        assertEquals("vpn1.example.org", a.title)
        assertEquals("203.0.113.5", CombineServer("1", "203.0.113.5", "").title)
    }
}
