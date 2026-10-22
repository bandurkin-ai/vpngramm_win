package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProfileImportTest {
    private val awg = Awg.generate("203.0.113.5", 30000)
    private val reality = Reality.generate()
    private val tg = TgParams.generate(993, "www.microsoft.com")

    private fun markers(withPriv: Boolean = true, withAwg: Boolean = true, withTg: Boolean = true) = listOfNotNull(
        "${Scripts.LINK_MARKER_HY2}443|AUTHPASS|OBFSPASS|vpn2.example.org||",
        "${Scripts.LINK_MARKER_VLESS}443|11111111-2222-3333-4444-555555555555|vpn2.example.org|${reality.second}|abcd1234|vpn2.example.org",
        "${Scripts.XPRIV_MARKER}${reality.first}".takeIf { withPriv },
        "${Scripts.LINK_MARKER_AWG}${Base64.getEncoder().encodeToString(awg.toJson().toString().toByteArray())}".takeIf { withAwg },
        "${Scripts.TGFULL_MARKER}${tg.port}|${tg.secret}".takeIf { withTg },
    )

    @Test
    fun splitUsesKnownZoneOrLastTwoLabels() {
        assertEquals("example.org" to "vpn2", ProfileImport.split("vpn2.example.org", "example.org"))
        assertEquals("example.org" to "a.b", ProfileImport.split("a.b.example.org.", ""))
        assertEquals("example.com" to "vpn", ProfileImport.split("vpn.example.com", "example.org"))
        assertEquals("example.org" to "", ProfileImport.split("example.org", ""))
    }

    @Test
    fun fullServerBecomesProfileWithSameKeys() {
        val r = ProfileImport.fromServer(markers(), "vpn2.example.org", "example.org", ProfileVps("77", "203.0.113.5", "f2"))
        val p = r.profile
        assertEquals(4, r.found)
        assertEquals("vpn2.example.org", p.fqdn)
        assertTrue(p.hy2On && p.vlessOn && p.awgOn && p.tgOn)
        assertEquals("AUTHPASS", p.hy2Auth)
        assertEquals(reality.first, p.realityPrivate)
        assertTrue(p.realitySelf)
        assertEquals(awg.serverPriv, p.awg!!.serverPriv)
        assertEquals("vpn2.example.org", p.awg!!.host)
        assertEquals(tg, p.tg)
        assertEquals("77", p.vps!!.id)
        // все ссылки — на домен, поэтому переживут смену IP
        val links = p.links()
        assertEquals(4, links.size)
        assertTrue(links.all { "vpn2.example.org" in it.uri || it.title == Awg.TITLE })
        assertTrue(links.last().uri.contains("server=vpn2.example.org&port=993&secret=${tg.secret}"))
        // AmneziaWG был на IP — об этом честно сказано
        assertTrue(r.notes.any { it.startsWith("AmneziaWG") })
        // после сохранения и чтения профиль тот же
        assertEquals(p, StableProfile.fromJson(p.toJson()))
    }

    @Test
    fun vlessWithoutPrivateKeyIsNotImported() {
        val r = ProfileImport.fromServer(markers(withPriv = false, withAwg = false, withTg = false), "vpn2.example.org", "", null)
        assertEquals(1, r.found)
        assertTrue(r.profile.hy2On)
        assertFalse(r.profile.vlessOn)
        assertFalse(r.profile.awgOn)
        assertEquals(listOf(Protocol.HY2.title), r.profile.links().map { it.title })
        assertTrue(r.notes.any { "приватный ключ" in it })
    }

    @Test
    fun nothingFound() {
        assertEquals(0, ProfileImport.fromServer(listOf("echo"), "a.example.org", "", null).found)
    }

    @Test
    fun telegramSecretKeepsFrontDomain() {
        assertTrue(tg.secret.startsWith("ee"))
        assertEquals("www.microsoft.com", TgParams.frontOf(tg.secret))
        assertNull(TgParams.frontOf("dd1234"))
    }

    @Test
    fun oldProfileJsonStillReads() {
        val old = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/")
        val json = org.json.JSONObject(old.toJson()).apply { remove("hy2On"); remove("vlessOn"); remove("tgOn") }.toString()
        val p = StableProfile.fromJson(json)
        assertTrue(p.hy2On && p.vlessOn && !p.tgOn)
        assertEquals(2, p.links().size)
    }

    @Test
    fun listRoundTripAndUpsertByName() {
        val a = StableProfile.create("example.org", "a", "", "https://www.bing.com/")
        val b = StableProfile.create("example.org", "b", "", "https://www.bing.com/")
        val list = ProfileImport.decodeList(ProfileImport.encodeList(listOf(a, b)))
        assertEquals(listOf(a, b), list)
        val a2 = a.copy(vps = ProfileVps("1", "203.0.113.1", "a"))
        assertEquals(listOf(a2, b), ProfileImport.upsert(list, a2))
        assertEquals(emptyList<StableProfile>(), ProfileImport.decodeList("[oops"))
    }

    @Test
    fun checkAddsProtocolsInstalledFromServerTab() {
        // профиль без AmneziaWG и Telegram; на сервере их поставили вкладкой «Сервер»
        val base = ProfileImport.fromServer(markers(withAwg = false, withTg = false), "vpn2.example.org", "example.org", null).profile
        assertEquals(2, base.links().size)
        val (p, notes) = ProfileImport.syncWithServer(base, markers())
        assertTrue(p.awgOn && p.tgOn)
        assertEquals("vpn2.example.org", p.awg!!.host)
        assertEquals(awg.serverPriv, p.awg!!.serverPriv)
        assertEquals(listOf(Protocol.VLESS.title, Protocol.HY2.title, Awg.TITLE, TgParams.TITLE), p.links().map { it.title })
        assertTrue(notes.any { it.startsWith("AmneziaWG: найден") } && notes.any { it.startsWith("Telegram-прокси: найден") })
        // VLESS и Hysteria2 не изменились — их ссылки прежние
        assertEquals(base.links().take(2), p.links().take(2))
        // повторная проверка ничего не меняет
        val again = ProfileImport.syncWithServer(p, markers())
        assertEquals(p, again.first)
        assertTrue(again.second.isEmpty())
    }

    @Test
    fun checkKeepsProtocolMissingOnServer() {
        val full = ProfileImport.fromServer(markers(), "vpn2.example.org", "example.org", null).profile
        val (p, notes) = ProfileImport.syncWithServer(full, markers(withAwg = false))
        assertEquals(full, p)
        assertTrue(notes.any { it.startsWith("AmneziaWG: в профиле есть") })
    }

    @Test
    fun deletesOnlyAddressRecordsOfThisName() {
        val recs = listOf(
            DnsRecord(1, "A", "vpn2", "vpn2.example.org.", "203.0.113.5", 300),
            DnsRecord(2, "AAAA", "vpn2", "vpn2.example.org.", "2001:db8::1", 300),
            DnsRecord(3, "TXT", "vpn2", "vpn2.example.org.", "x", 300),
            DnsRecord(4, "A", "vpn1", "vpn1.example.org.", "203.0.113.6", 300),
            DnsRecord(5, "A", "", "example.org.", "203.0.113.7", 300),
            DnsRecord(6, "A", "x.vpn2", "x.vpn2.example.org.", "203.0.113.8", 300),
        )
        assertEquals(listOf(1, 2), ProfileImport.recordsToDelete(recs, "vpn2.example.org").map { it.id })
        // корень зоны не удаляется никогда
        assertEquals(emptyList<Int>(), ProfileImport.recordsToDelete(recs, "example.org").map { it.id })
    }

    @Test
    fun oldSingleProfileMigratesIntoList() {
        val old = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/")
        assertEquals(listOf(old), ProfileImport.load("", old.toJson()))
        val b = StableProfile.create("example.org", "b", "", "https://www.bing.com/")
        // список уже есть — старый ключ не мешает
        assertEquals(listOf(b), ProfileImport.load(ProfileImport.encodeList(listOf(b)), old.toJson()))
        assertEquals(emptyList<StableProfile>(), ProfileImport.load("", ""))
    }

    @Test
    fun telegramScriptKeepsGivenSecret() {
        val s = Scripts.installTelegram(993, "www.microsoft.com", tg.secret)
        assertTrue(s.contains("SECRET='${tg.secret}'"))
        assertFalse(s.contains("generate-secret"))
        assertTrue(Scripts.installTelegram(443, "www.microsoft.com").contains("generate-secret --hex"))
    }
}
