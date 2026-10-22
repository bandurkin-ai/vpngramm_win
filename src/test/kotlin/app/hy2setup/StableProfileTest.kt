package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class StableProfileTest {

    @Test
    fun jsonRoundTripKeepsLinksIdentical() {
        val p = StableProfile.create("example.com", "vpn", "me@example.com", "https://www.bing.com/")
            .copy(cert = CertBackup("CRT", "KEY", "{}", 1_900_000_000_000), vps = ProfileVps("42", "203.0.113.7", "vpn.example.com"))
        val back = StableProfile.fromJson(p.toJson())
        assertEquals(p, back)
        assertEquals(p.links(), back.links())
        // новый VPS не меняет ссылки
        assertEquals(p.links(), back.copy(vps = ProfileVps("99", "198.51.100.1", "x")).links())
    }

    @Test
    fun linksPointToNameNotIp() {
        val p = StableProfile.create("example.com", "vpn", "", "https://www.bing.com/")
        val (vl, hy) = p.links().map { it.uri }
        assertTrue(hy, hy.startsWith("hysteria2://${p.hy2Auth}@vpn.example.com:443/?"))
        assertTrue(hy.contains("sni=vpn.example.com"))
        assertFalse("без pin и insecure — сертификат Let's Encrypt", hy.contains("insecure") || hy.contains("pinSHA256"))
        assertTrue(vl, vl.startsWith("vless://${p.vlessUuid}@vpn.example.com:443?"))
        assertTrue(vl.contains("pbk=${p.realityPublic}") && vl.contains("sid=${p.realityShortId}"))
    }

    @Test
    fun realityKeysLikeXray() {
        val (priv, pub) = Reality.generate()
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(priv))
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(pub))
        assertEquals(pub, Reality.publicOf(priv))
        // контрольный вектор: ключи, выданные `xray x25519` (Xray 26.3.27)
        assertEquals("OnzLYEwWXpv--0raqKhr-8YYr88Ix_cADvHl0LGKSHw", Reality.publicOf("iO2HTWeqgbV5Q-YiChZzgD9WnMleLH9aO0wt-FPL0Vo"))
        assertNotEquals(Reality.generate().first, Reality.generate().first)
    }

    @Test
    fun newProfilesMaskAsOwnDomain() {
        val p = StableProfile.create("example.com", "vpn", "", "https://www.bing.com/")
        assertTrue(p.realitySelf)
        assertEquals(StableProfile.LOCAL_HTTPS, p.vlessParams().dest)
        assertTrue(p.hy2Params("203.0.113.7", null).localHttps)
        assertTrue(p.links()[0].uri.contains("sni=vpn.example.com"))
        // старый профиль с чужим сайтом: dest — сам сайт
        val old = p.copy(realitySni = "www.microsoft.com")
        assertFalse(old.realitySelf)
        assertEquals("", old.vlessParams().dest)
        assertFalse(old.hy2Params("203.0.113.7", null).localHttps)
        // переход «под себя» меняет в ссылке VLESS только sni
        val a = old.links()[0].uri; val b = old.copy(realitySni = old.fqdn).links()[0].uri
        assertEquals(a.replace("sni=www.microsoft.com", "sni=vpn.example.com"), b)
        assertEquals(old.links()[1], old.copy(realitySni = old.fqdn).links()[1])
    }
}
