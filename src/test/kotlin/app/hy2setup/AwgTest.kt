package app.hy2setup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.Inflater

class AwgTest {

    /** Разбор vpn:// так же, как AmneziaVPN: base64url → qUncompress (4 байта длины + zlib). */
    private fun decode(link: String): JSONObject {
        val raw = Base64.getUrlDecoder().decode(link.removePrefix("vpn://"))
        val len = ((raw[0].toInt() and 0xff) shl 24) or ((raw[1].toInt() and 0xff) shl 16) or
            ((raw[2].toInt() and 0xff) shl 8) or (raw[3].toInt() and 0xff)
        val inf = Inflater().apply { setInput(raw, 4, raw.size - 4) }
        val out = ByteArray(len)
        val n = inf.inflate(out)
        assertEquals(len, n)
        return JSONObject(String(out, Charsets.UTF_8))
    }

    @Test
    fun publicKeyMatchesRfc7748() {
        // RFC 7748, 6.1: закрытый ключ Алисы → открытый
        val priv = Base64.getEncoder().encodeToString(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        assertEquals(
            Base64.getEncoder().encodeToString(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")),
            Awg.publicOf(priv),
        )
        assertEquals(44, Awg.genKey().length)
    }

    @Test
    fun generatedParamsRespectKernelLimits() {
        repeat(500) {
            val p = Awg.generate("vpn.example.org")
            assertTrue(listOf(p.s1, p.s2, p.s3, p.s4).all { it >= 12 })
            assertTrue(p.s1 + 56 != p.s2 && p.s3 != p.s2 + 28 && p.s4 <= 32)
            assertTrue(p.jc in 1..128 && p.jmin <= p.jmax && p.jmax <= 1280)
            assertTrue(p.port in 1..65535 && p.port != 443)
        }
    }

    @Test
    fun vpnLinkHasAmneziaStructure() {
        val p = Awg.generate("vpn.example.org", 34127)
        val o = decode(p.vpnLink("vpn.example.org"))
        assertEquals("amnezia-awg", o.getString("defaultContainer"))
        assertEquals("vpn.example.org", o.getString("hostName"))
        val awg = o.getJSONArray("containers").getJSONObject(0).getJSONObject("awg")
        assertEquals(true, awg.getBoolean("isThirdPartyConfig"))
        assertEquals("34127", awg.getString("port"))
        val last = JSONObject(awg.getString("last_config"))
        // поля маскировки — строки (AmneziaVPN читает их через toString), порт — число
        assertEquals("${p.s3}", last.get("S3"))
        assertEquals("1", last.get("H1"))
        assertEquals(34127, last.get("port"))
        assertEquals(p.serverPub, last.getString("server_pub_key"))
        assertEquals(p.clientPriv, last.getString("client_priv_key"))
        assertEquals(p.headerKey, last.getString("HeaderProtectionKey"))
        assertTrue(last.getString("config").contains("Endpoint = vpn.example.org:34127"))
        assertEquals("off", last.getString("RandomTrailers"))
        val on = Awg.generate("vpn.example.org", 34127, trailers = true)
        val lastOn = JSONObject(decode(on.vpnLink("x")).getJSONArray("containers").getJSONObject(0).getJSONObject("awg").getString("last_config"))
        assertEquals("on", lastOn.getString("RandomTrailers"))
        // режим обязан совпадать у сервера и клиента
        assertTrue(on.serverConf().contains("RandomTrailers = on") && on.clientConf().contains("RandomTrailers = on"))
        assertTrue(p.serverConf().contains("RandomTrailers = off") && p.clientConf().contains("RandomTrailers = off"))
        assertEquals(true, AwgParams.fromJson(on.toJson()).trailers)
        // ссылка должна помещаться в QR (версия 40-L — до 2953 байт)
        val len = p.vpnLink("vpn.example.org").length
        println("vpn:// length = $len")
        assertTrue("длина $len", len < 2400)
    }

    @Test
    fun serverAndClientShareObfuscation() {
        val p = Awg.generate("vpn.example.org", 34127)
        val srv = p.serverConf()
        val cli = p.clientConf()
        for (k in listOf("S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "HeaderProtectionKey", "Jc", "Jmin", "Jmax", "RandomTrailers")) {
            val line = srv.lines().first { it.startsWith("$k = ") }
            assertTrue("$k совпадает", cli.lines().contains(line))
        }
        assertTrue(srv.contains("PublicKey = ${p.clientPub}") && cli.contains("PublicKey = ${p.serverPub}"))
        assertTrue(srv.contains("ListenPort = 34127") && srv.contains("__NIC__"))
    }

    @Test
    fun profileKeepsSameAwgLink() {
        val base = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/")
        val prof = base.copy(awg = Awg.generate(base.fqdn, 34127), awgOn = true)
        val again = StableProfile.fromJson(prof.toJson())
        assertEquals(prof.links(), again.links())
        assertEquals(3, again.links().size)
        assertEquals(Awg.TITLE, again.links().last().title)
        assertEquals(2, again.copy(awgOn = false).links().size)
        // старый профиль без AmneziaWG читается
        assertEquals(null, StableProfile.fromJson(base.toJson()).awg)
    }

    @Test
    fun exportMarkerRebuildsLink() {
        val p = Awg.generate("203.0.113.10", 40000)
        val marker = Scripts.LINK_MARKER_AWG + Base64.getEncoder().encodeToString(p.toJson().toString().toByteArray())
        val links = Links.fromExport(listOf(marker), "203.0.113.10")
        assertEquals(listOf(ShareLink(Awg.TITLE, p.vpnLink("203.0.113.10"))), links)
    }

    @Test
    fun dumpAwgScripts() {
        val dir = File("build/scripts").apply { mkdirs() }
        val s = Scripts.installAwg(Awg.generate("vpn.example.org", 34127))
        File(dir, "awg_start.sh").writeText(s)
        // тело фоновой задачи тоже сохраняем для bash -n
        val b64 = Regex("echo '([A-Za-z0-9+/=]+)' \\| base64 -d \\| gunzip > /root/.hy2setup-awg-body.sh").find(s)!!.groupValues[1]
        val body = java.util.zip.GZIPInputStream(Base64.getDecoder().decode(b64).inputStream()).bufferedReader().readText()
        File(dir, "awg_body.sh").writeText(body)
        assertTrue(body.contains("amneziawg-dkms") && body.contains("75C9DD72C799870E310542E24166F2C257290828"))
        assertTrue(body.contains(Scripts.AWG_REBOOT))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
