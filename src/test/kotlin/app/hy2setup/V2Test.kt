package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Версия 2.0: люди, подписка, проверка служб и трафика, копия всех серверов, контроль. */
class V2Test {
    private val base = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/")
        .copy(awg = Awg.generate("vpn.example.org", 30000), awgOn = true, tg = TgParams.generate(993, "www.microsoft.com"), tgOn = true)

    @Test
    fun oldProfileWithoutPeopleKeepsLinks() {
        val json = org.json.JSONObject(base.toJson()).apply { remove("users"); remove("subToken") }.toString()
        val p = StableProfile.fromJson(json)
        assertEquals(base.links(), p.links())
        assertTrue(p.users.isEmpty())
        assertEquals(24, p.subToken.length)
        // одна строка Hysteria2 — прежний режим password
        assertTrue(Scripts.hy2Auth(p.hy2Params("1.2.3.4", null)).contains("type: password"))
    }

    @Test
    fun secondPersonGetsOwnKeysAndOwnerVlessAwgStay() {
        val u = ProfileUser.create("Мама", emptyList())
        val p = base.copy(users = listOf(u))
        val before = base.links().associateBy { it.title }
        val owner = p.links().associateBy { it.title }
        assertEquals(before[Protocol.VLESS.title], owner[Protocol.VLESS.title])
        assertEquals(before[Awg.TITLE], owner[Awg.TITLE])
        assertEquals(before[TgParams.TITLE], owner[TgParams.TITLE])
        // Hysteria2 переходит на имя:пароль — ссылка владельца меняется (об этом предупреждаем)
        assertNotEquals(before[Protocol.HY2.title], owner[Protocol.HY2.title])
        assertTrue(owner[Protocol.HY2.title]!!.uri.startsWith("hysteria2://u0:${base.hy2Auth}@"))
        val mama = p.linksFor(u).associateBy { it.title }
        assertTrue(mama[Protocol.VLESS.title]!!.uri.startsWith("vless://${u.vlessUuid}@"))
        assertTrue(mama[Protocol.HY2.title]!!.uri.startsWith("hysteria2://u1:${u.hy2Pass}@"))
        // конфигурации сервера знают обоих
        val auth = Scripts.hy2Auth(p.hy2Params("1.2.3.4", null))
        assertTrue(auth.contains("type: userpass") && auth.contains("u0: ${base.hy2Auth}") && auth.contains("u1: ${u.hy2Pass}"))
        val vl = Scripts.installVless(p.vlessParams())
        assertTrue(vl.contains("\"id\": \"${u.vlessUuid}\"") && vl.contains("\"id\": \"\${UUID}\""))
        val conf = p.awgEffective()!!.serverConf()
        assertEquals(2, Regex("\\[Peer]").findAll(conf).count())
        assertTrue(conf.contains("AllowedIPs = 10.66.66.3/32"))
        // отключённый не попадает никуда
        val off = p.copy(users = listOf(u.copy(disabled = true)))
        assertEquals(1, Regex("\\[Peer]").findAll(off.awgEffective()!!.serverConf()).count())
        assertFalse(Scripts.installVless(off.vlessParams()).contains(u.vlessUuid))
        assertEquals(base.links(), off.links())
        // сохранение и чтение
        assertEquals(p, StableProfile.fromJson(p.toJson()))
    }

    @Test
    fun peopleGetFreeIdsAndAddresses() {
        val a = ProfileUser.create("A", emptyList())
        val b = ProfileUser.create("B", listOf(a))
        val c = ProfileUser.create("", listOf(a, b))
        assertEquals(listOf("1", "2", "3"), listOf(a.id, b.id, c.id))
        assertEquals(listOf("10.66.66.3", "10.66.66.4", "10.66.66.5"), listOf(a.awgIp, b.awgIp, c.awgIp))
        assertEquals("Человек 3", c.name)
        assertNotEquals(a.subToken, b.subToken)
    }

    @Test
    fun subscriptionHasOnlyHappProtocolsOfThatPerson() {
        val u = ProfileUser.create("Мама", emptyList())
        val p = base.copy(users = listOf(u))
        val text = Subscription.content(p, u)
        assertTrue(text.startsWith("#profile-title: base64:"))
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("vless://${u.vlessUuid}@") && lines[1].startsWith("hysteria2://u1:"))
        val files = Subscription.siteFiles(p)
        assertEquals(setOf("index.html", "s/index.html", "s/${p.subToken}", "s/${u.subToken}"), files.keys)
        assertEquals("https://vpn.example.org/s/${u.subToken}", p.subUrl(u))
        // VLESS «под себя»: сайт виден через Reality на 127.0.0.1:8443
        assertEquals(StableProfile.LOCAL_HTTPS, p.siteListen)
        val masq = Scripts.hy2Masquerade(p.hy2Params("1.2.3.4", null))
        assertTrue(masq.contains("type: file") && masq.contains("dir: ${StableProfile.SITE_DIR}") && masq.contains("listenHTTPS: 127.0.0.1:8443"))
        // без Hysteria2 подписку отдавать некому
        assertFalse(p.copy(hy2On = false).subAvailable)
        // TCP 443 занят Telegram и нет VLESS — сайту некуда
        assertNull(p.copy(vlessOn = false, tg = TgParams.generate(443, "www.microsoft.com")).siteListen)
        assertEquals(":443", p.copy(vlessOn = false).siteListen)
    }

    @Test
    fun servicesAndTraffic() {
        val v2 = """{"vnstatversion":"2.10","jsonversion":"2","interfaces":[
            {"name":"eth0","traffic":{"month":[
              {"date":{"year":2026,"month":9},"rx":5000000000,"tx":1000000000},
              {"date":{"year":2026,"month":10},"rx":100000000000,"tx":20000000000}]}},
            {"name":"lo","traffic":{"month":[{"date":{"year":2026,"month":10},"rx":999999999999,"tx":0}]}}]}"""
        assertEquals(120.0, ServerCheck.parseVnstat(v2)!!, 0.001)
        val v1 = """{"jsonversion":"1","interfaces":[{"id":"eth0","traffic":{"months":[{"date":{"year":2026,"month":10},"rx":1000000,"tx":0}]}}]}"""
        assertEquals(1.024, ServerCheck.parseVnstat(v1)!!, 0.001)
        val lines = listOf(
            "${ServerCheck.SVC_MARKER}xray|active", "${ServerCheck.SVC_MARKER}hysteria-server|failed",
            "${ServerCheck.VNSTAT_MARKER}" + Base64.getEncoder().encodeToString(v2.toByteArray()),
        )
        assertEquals(mapOf(Protocol.VLESS.title to true, Protocol.HY2.title to false), ServerCheck.services(lines))
        assertEquals(120.0, ServerCheck.monthTrafficGb(lines)!!, 0.001)
        assertNull(ServerCheck.monthTrafficGb(listOf("x")))
    }

    @Test
    fun backupOfAllServers() {
        val b = StableProfile.create("example.org", "b", "", "https://www.bing.com/")
        val all = ProfileImport.encodeList(listOf(base, b))
        assertEquals(listOf(base, b), ProfileImport.fromBackup(all))
        assertEquals(listOf(base), ProfileImport.fromBackup(base.toJson()))
        // проверки и трафик не делают копию «устаревшей», а люди — делают
        val f = ProfileImport.backupFingerprint(listOf(base))
        assertEquals(f, ProfileImport.backupFingerprint(listOf(base.copy(checkAt = 5, status = mapOf("x" to true), trafficGb = 3.0))))
        assertNotEquals(f, ProfileImport.backupFingerprint(listOf(base.copy(users = listOf(ProfileUser.create("A", emptyList()))))))
    }

    @Test
    fun monitorAlertsOnlyAfterTwoFailuresWithoutVpn() {
        assertFalse(MonitorWorker.decideAlert(ok = false, fails = 1, vpn = false))
        assertTrue(MonitorWorker.decideAlert(ok = false, fails = 2, vpn = false))
        assertFalse(MonitorWorker.decideAlert(ok = false, fails = 3, vpn = false))
        assertTrue(MonitorWorker.decideAlert(ok = false, fails = 8, vpn = false))
        assertFalse(MonitorWorker.decideAlert(ok = false, fails = 2, vpn = true))
        assertFalse(MonitorWorker.decideAlert(ok = true, fails = 0, vpn = false))
    }

    @Test
    fun shortLogShowsStepsAndResults() {
        val log = listOf("", "▶ Создание VPS", "apt output…", "✓ VPS создан", "▶ Установка", "ОШИБКА: порт занят", "▶ Ссылки")
        val e = LogSummary.events(log, running = true)
        assertEquals(
            listOf(LogSummary.State.OK, LogSummary.State.OK, LogSummary.State.ERROR, LogSummary.State.ERROR, LogSummary.State.RUNNING),
            e.map { it.state },
        )
        assertEquals("Создание VPS", e.first().text)
        assertEquals(LogSummary.State.INFO, LogSummary.events(log, running = false).last().state)
    }

    @Test
    fun balanceIsFoundAnywhereInResponse() {
        val r = org.json.JSONObject("""{"response":{"account":{"credit_balance":"12.50","currency":"USD"}}}""")
        assertEquals("12.50 USD", OneProviderClient.findBalance(r))
        assertNull(OneProviderClient.findBalance(org.json.JSONObject("""{"response":{"name":"x"}}""")))
    }

    /** Сценарий стенда tools/os-test/run_v2.sh: два человека, подписка через Reality → Hysteria2 → файл. */
    @Test
    fun dumpV2Stand() {
        val dir = File("build/scripts").apply { mkdirs() }
        val u = ProfileUser.create("Мама", emptyList())
        val p = StableProfile.create("example.com", "vpn", "", "https://www.bing.com/").copy(
            hy2Auth = "aaaa", hy2Obfs = "bbbb",
            realityPrivate = "iO2HTWeqgbV5Q-YiChZzgD9WnMleLH9aO0wt-FPL0Vo", realityPublic = "OnzLYEwWXpv--0raqKhr-8YYr88Ix_cADvHl0LGKSHw",
            realityShortId = "0123456789abcdef", users = listOf(u),
        )
        File(dir, "v2s_hy.sh").writeText(Scripts.install(p.hy2Params("203.0.113.10", CertBackup("CRT", "K", "{}", 0))))
        File(dir, "v2s_vl.sh").writeText(Scripts.installVless(p.vlessParams()))
        File(dir, "v2s_site.sh").writeText(Scripts.writeSite(StableProfile.SITE_DIR, Subscription.siteFiles(p)))
        File(dir, "v2s_check.sh").writeText(ServerCheck.script())
        fun links(user: ProfileUser?) = p.linksFor(user).let { l -> l.first { it.title == Protocol.HY2.title }.uri + "\n" + l.first { it.title == Protocol.VLESS.title }.uri + "\n" }
        File(dir, "v2s_links_owner.txt").writeText(links(null))
        File(dir, "v2s_links_user.txt").writeText(links(u))
        File(dir, "v2s_tokens.txt").writeText("${p.subToken}\n${u.subToken}\n")
        File(dir, "v2s_sub_user.txt").writeText(Subscription.content(p, u))
    }

    @Test
    fun dumpV2Scripts() {
        val dir = File("build/scripts").apply { mkdirs() }
        val u = ProfileUser.create("Мама", emptyList())
        val p = base.copy(users = listOf(u))
        File(dir, "v2_install_hy2.sh").writeText(Scripts.install(p.hy2Params("203.0.113.10", null)))
        File(dir, "v2_install_vless.sh").writeText(Scripts.installVless(p.vlessParams()))
        File(dir, "v2_site.sh").writeText(Scripts.writeSite(StableProfile.SITE_DIR, Subscription.siteFiles(p)))
        File(dir, "v2_check.sh").writeText(ServerCheck.script())
    }
}
