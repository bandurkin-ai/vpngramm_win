package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {

    @Test
    fun hy2SelfSigned() {
        val u = Links.hy2("203.0.113.10", 443, "aa", "bb", "", "www.bing.com", "abcd")
        assertEquals(
            "hysteria2://aa@203.0.113.10:443/?obfs=salamander&obfs-password=bb&sni=www.bing.com&insecure=1&pinSHA256=abcd#Hy2-203.0.113.10",
            u,
        )
    }

    @Test
    fun hy2WithDomainUsesDomainAndNoInsecure() {
        val u = Links.hy2("203.0.113.10", 8443, "aa", "bb", "vpn.example.com", "", null)
        assertEquals("hysteria2://aa@vpn.example.com:8443/?obfs=salamander&obfs-password=bb&sni=vpn.example.com#Hy2-vpn.example.com", u)
    }

    @Test
    fun vlessRealityLink() {
        val u = Links.vless("vpn.example.com", 443, "11111111-2222-3333-4444-555555555555", "www.microsoft.com", "PBK_x-y", "0123abcd")
        assertEquals(
            "vless://11111111-2222-3333-4444-555555555555@vpn.example.com:443?encryption=none&flow=xtls-rprx-vision" +
                "&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PBK_x-y&sid=0123abcd&type=tcp#VLESS-vpn.example.com",
            u,
        )
    }

    @Test
    fun ipv6HostIsBracketed() {
        assertTrue(Links.vless("2001:db8::1", 443, "u", "s", "p", "i").contains("@[2001:db8::1]:443"))
    }

    @Test
    fun parsesExportOutput() {
        val lines = listOf(
            "==> что-то",
            "##HY2|443|authpw|obfspw||www.bing.com|deadbeef",
            "Hysteria2: найдена конфигурация (порт 443/udp).",
            "##VLESS|443|uuid-1|www.microsoft.com|PBK|SID|vpn.example.com",
        )
        val links = Links.fromExport(lines, "203.0.113.10")
        assertEquals(2, links.size)
        assertEquals(Protocol.HY2.title, links[0].title)
        assertTrue(links[0].uri.startsWith("hysteria2://authpw@203.0.113.10:443/"))
        assertTrue(links[0].uri.contains("pinSHA256=deadbeef"))
        assertTrue(links[1].uri.startsWith("vless://uuid-1@vpn.example.com:443?"))
    }

    @Test
    fun vlessWithoutSavedAddressFallsBackToSshHost() {
        val links = Links.fromExport(listOf("##VLESS|443|u|sni|pbk|sid|"), "198.51.100.5")
        assertTrue(links.single().uri.startsWith("vless://u@198.51.100.5:443?"))
    }

    @Test
    fun brokenMarkersAreIgnored() {
        assertEquals(0, Links.fromExport(listOf("##HY2|443||", "##VLESS|443"), "h").size)
    }

    @Test
    fun telegramLinkFromMarker() {
        val secret = "ee" + "a".repeat(32) + "7777772e6d6963726f736f66742e636f6d"
        val link = Links.telegramFromMarkers(listOf("noise", "##TG|8443|$secret"), "203.0.113.5")!!
        assertEquals("https://t.me/proxy?server=203.0.113.5&port=8443&secret=$secret", link.uri)
    }

    @Test
    fun telegramIncludedInServerExport() {
        val secret = "ee" + "b".repeat(32) + "6f6b"
        val links = Links.fromExport(listOf("##TG|443|$secret"), "198.51.100.1")
        assertTrue(links.single().uri.startsWith("https://t.me/proxy?server=198.51.100.1&port=443&secret=ee"))
    }

    @Test
    fun brokenTelegramSecretIgnored() {
        assertEquals(null, Links.telegramFromMarkers(listOf("##TG|443|notasecret"), "h"))
    }
}
