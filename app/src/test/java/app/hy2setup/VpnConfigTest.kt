package app.hy2setup

import app.hy2setup.vpn.VpnConfig
import app.hy2setup.vpn.VpnProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class VpnConfigTest {
    private val p = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/")
    private val engine = listOf("../engine/build/vpncore-linux", "engine/build/vpncore-linux").map { File(it) }.firstOrNull { it.canExecute() }

    /** Движок (Linux-сборка того же vpncore) сам проверяет конфигурацию: так ловятся ошибки формата sing-box. */
    private fun engineAccepts(json: String) {
        val e = engine ?: return
        val f = File.createTempFile("vpn", ".json").apply { writeText(json) }
        val proc = ProcessBuilder(e.path, "-check", "-config", f.path).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(30, TimeUnit.SECONDS)
        assertEquals(out, 0, proc.exitValue())
        f.delete()
    }

    @Test
    fun vlessRealityFromProfile() {
        val json = VpnConfig.vless(p, null, "203.0.113.10")
        val o = JSONObject(json)
        val out = o.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", out.getString("type"))
        assertEquals("203.0.113.10", out.getString("server"))
        assertEquals(p.vlessUuid, out.getString("uuid"))
        assertEquals("xtls-rprx-vision", out.getString("flow"))
        val reality = out.getJSONObject("tls").getJSONObject("reality")
        assertEquals(p.realityPublic, reality.getString("public_key"))
        assertEquals(p.realityShortId, reality.getString("short_id"))
        assertEquals(p.realitySni, out.getJSONObject("tls").getString("server_name"))
        assertEquals("stderr", o.getJSONObject("log").getString("output"))
        assertFalse(json.contains("geoip-ru"))
        engineAccepts(json)
    }

    @Test
    fun personKeysAndRussianBypass() {
        val u = ProfileUser.create("Мама", emptyList())
        val json = VpnConfig.vless(p.copy(users = listOf(u)), u, "203.0.113.10", VpnConfig.Options(bypassRu = true))
        assertTrue(json.contains(u.vlessUuid))
        assertTrue(json.contains("geoip-ru") && json.contains("xn--p1ai"))
        engineAccepts(json)
    }

    @Test
    fun hysteria2OwnerAndPeople() {
        val one = JSONObject(VpnConfig.hysteria2(p, null, "203.0.113.10")).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria2", one.getString("type"))
        assertEquals(p.hy2Auth, one.getString("password"))
        assertEquals(p.hy2Obfs, one.getJSONObject("obfs").getString("password"))
        assertEquals(p.fqdn, one.getJSONObject("tls").getString("server_name"))
        assertFalse(one.getJSONObject("tls").optBoolean("insecure"))
        // несколько людей — сервер в режиме userpass
        val u = ProfileUser.create("Мама", emptyList())
        val multi = p.copy(users = listOf(u))
        assertEquals("u0:${p.hy2Auth}", JSONObject(VpnConfig.hysteria2(multi, null, "203.0.113.10")).getJSONArray("outbounds").getJSONObject(0).getString("password"))
        val json = VpnConfig.hysteria2(multi, u, "203.0.113.10", VpnConfig.Options(bypassRu = true))
        assertTrue(json.contains("${u.hy2Name}:${u.hy2Pass}"))
        engineAccepts(json)
    }

    @Test
    fun amneziaWgKeysAndObfuscation() {
        val a = Awg.generate("vpn.example.org", 34127)
        val pa = p.copy(awg = a, awgOn = true)
        val out = JSONObject(VpnConfig.amneziaWg(pa, null, "203.0.113.10")).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("awg", out.getString("type"))
        assertEquals("203.0.113.10", out.getString("server"))
        assertEquals(34127, out.getInt("server_port"))
        assertEquals(a.clientPriv, out.getString("private_key"))
        assertEquals(a.serverPub, out.getString("peer_public_key"))
        assertEquals(a.headerKey, out.getString("header_protection_key"))
        assertEquals(a.s3, out.getInt("s3"))
        assertEquals("${Awg.CLIENT_IP}/32", out.getJSONArray("address").getString(0))
        engineAccepts(VpnConfig.amneziaWg(pa, null, "203.0.113.10"))
        // у человека свой ключ и адрес
        val u = ProfileUser.create("Мама", emptyList())
        val pu = JSONObject(VpnConfig.amneziaWg(pa.copy(users = listOf(u)), u, "203.0.113.10")).getJSONArray("outbounds").getJSONObject(0)
        assertEquals(u.awgPriv, pu.getString("private_key"))
        assertEquals("${u.awgIp}/32", pu.getJSONArray("address").getString(0))
        // RandomTrailers встроенный клиент не умеет — понятная ошибка вместо неработающего туннеля
        val e = runCatching { VpnConfig.amneziaWg(pa.copy(awg = a.copy(trailers = true)), null, "203.0.113.10") }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("RandomTrailers"))
    }

    @Test
    fun vlessXhttpThroughXray() {
        val px = p.copy(xhttpOn = true)
        val out = JSONObject(VpnConfig.vlessXhttp(px, null, "203.0.113.10")).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("xray", out.getString("type"))
        val x = out.getJSONObject("xray")
        val stream = x.getJSONObject("streamSettings")
        assertEquals("xhttp", stream.getString("network"))
        assertEquals(px.xhttpPathEff, stream.getJSONObject("xhttpSettings").getString("path"))
        assertEquals(p.realityPublic, stream.getJSONObject("realitySettings").getString("publicKey"))
        val user = x.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals(p.vlessUuid, user.getString("id"))
        assertFalse(user.has("flow"))
        engineAccepts(VpnConfig.vlessXhttp(px, null, "203.0.113.10", VpnConfig.Options(bypassRu = true)))
        assertEquals(listOf(VpnProtocol.VLESS, VpnProtocol.XHTTP, VpnProtocol.HY2), VpnConfig.available(px))
        assertFalse(VpnProtocol.XHTTP in VpnConfig.available(p))
    }

    /** Конфигурация приложения для сквозного стенда tools/vpn-test (адрес сервера подставляет скрипт). */
    @Test
    fun dumpStandConfig() {
        val stand = StableProfile.create("example.com", "vpn", "", "https://www.bing.com/").copy(
            realityPrivate = "iO2HTWeqgbV5Q-YiChZzgD9WnMleLH9aO0wt-FPL0Vo", realityPublic = "OnzLYEwWXpv--0raqKhr-8YYr88Ix_cADvHl0LGKSHw",
            realityShortId = "0123456789abcdef",
        )
        File("build/scripts").mkdirs()
        File("build/scripts/vpn_app_config.json").writeText(
            VpnConfig.vless(stand, null, "SERVER_IP", VpnConfig.Options(dns = "8.8.8.8", dnsUdp = true, logLevel = "info")),
        )
        File("build/scripts/vpn_app_uuid.txt").writeText(stand.vlessUuid)
        val opts = VpnConfig.Options(dns = "8.8.8.8", dnsUdp = true, logLevel = "info")
        // Hysteria2 в режиме userpass (как у сервера стенда) и AmneziaWG с новыми ключами
        val hy = stand.copy(hy2Auth = "aaaa", hy2Obfs = "bbbb", users = listOf(ProfileUser.create("Мама", emptyList())))
        File("build/scripts/vpn_app_hy2.json").writeText(VpnConfig.hysteria2(hy, null, "SERVER_IP", opts))
        val awg = Awg.generate("SERVER_IP", 51820)
        File("build/scripts/vpn_app_awg.json").writeText(VpnConfig.amneziaWg(stand.copy(awg = awg, awgOn = true), null, "SERVER_IP", opts))
        File("build/scripts/vpn_awg_server.conf").writeText(awg.serverConf())
        // XHTTP: ключи и путь — как у install_vless_xhttp.sh (ScriptsTest)
        val xh = stand.copy(vlessUuid = "11111111-2222-3333-4444-555555555555", xhttpOn = true, xhttpPath = "/xh123")
        File("build/scripts/vpn_app_xhttp.json").writeText(VpnConfig.vlessXhttp(xh, null, "SERVER_IP", opts))
    }
}
