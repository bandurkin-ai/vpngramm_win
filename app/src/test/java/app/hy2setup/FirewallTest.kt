package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Защита сервера: файрвол vpngramm, fail2ban и строгие настройки SSH. Ничего не применяется к этой машине. */
class FirewallTest {
    /** Скрипты сервера — для Linux; на Windows («bash» там — другой) их проверяют только на Linux. */
    private fun linuxOnly() = assumeTrue("скрипты сервера проверяются только на Linux", System.getProperty("os.name").orEmpty().startsWith("Linux"))

    private fun sh(vararg cmd: String, env: Map<String, String> = emptyMap(), input: String? = null): Pair<Int, String> {
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        input?.let { p.outputStream.use { o -> o.write(it.toByteArray()) } } ?: p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun script(): File = Files.createTempFile("vpngramm-fw", ".sh").toFile().apply { writeText(Scripts.fwScript); deleteOnExit() }

    private fun fakeRoot(): File {
        val r = Files.createTempDirectory("fwroot").toFile()
        File(r, "etc/hysteria").mkdirs(); File(r, "etc/hysteria/config.yaml").writeText("listen: :443\nacme:\n  domains:\n    - vpn.example.org\n")
        File(r, "usr/local/etc/xray").mkdirs()
        File(r, "usr/local/etc/xray/hy2setup.env").writeText("PORT=8443\n")
        File(r, "usr/local/etc/xray/config.json").writeText("{\"inbounds\":[{\"port\": 8443}]}")
        File(r, "etc/mtg.toml").writeText("bind-to = \"0.0.0.0:993\"\n")
        File(r, "etc/amnezia/amneziawg").mkdirs(); File(r, "etc/amnezia/amneziawg/awg0.conf").writeText("[Interface]\nListenPort = 51820\n")
        return r
    }

    @Test
    fun rulesOpenOnlySshAndVpnPorts() {
        linuxOnly()
        val (code, out) = sh("bash", script().path, "print", env = mapOf("FW_ROOT" to fakeRoot().path, "FW_SSH_PORTS" to "22"))
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("policy drop;"))
        assertTrue(out, out.contains("tcp dport { 22, 80, 993, 8443 } accept"))
        assertTrue(out, out.contains("udp dport { 443, 51820 } accept"))
        assertTrue(out, out.contains("ct state established,related accept"))
    }

    @Test
    fun noVpnMeansOnlySsh() {
        linuxOnly()
        val empty = Files.createTempDirectory("fwempty").toFile()
        val (code, out) = sh("bash", script().path, "print", env = mapOf("FW_ROOT" to empty.path, "FW_SSH_PORTS" to "2222"))
        assertEquals(0, code)
        assertTrue(out, out.contains("tcp dport { 2222 } accept"))
        assertFalse(out, out.contains("udp dport {"))
    }

    /** Сайт домена с подпиской Happ: когда Hysteria2 отдаёт его сама на :443, порт должен быть открыт; локальный адрес — нет. */
    @Test
    fun subscriptionSitePortIsOpen() {
        linuxOnly()
        fun tcp(listen: String): String {
            val r = Files.createTempDirectory("fwsite").toFile()
            File(r, "etc/hysteria").mkdirs()
            File(r, "etc/hysteria/config.yaml").writeText("listen: :443\nacme:\n  domains:\n    - vpn.example.org\nmasquerade:\n  type: file\n  listenHTTPS: $listen\n")
            val (code, out) = sh("bash", script().path, "print", env = mapOf("FW_ROOT" to r.path, "FW_SSH_PORTS" to "22"))
            assertEquals(out, 0, code)
            return out.lines().first { "tcp dport" in it }.trim()
        }
        assertEquals("tcp dport { 22, 80, 443 } accept", tcp(":443"))
        assertEquals("tcp dport { 22, 80 } accept", tcp("127.0.0.1:8443"))
        assertEquals("tcp dport { 22, 80, 8444 } accept", tcp("0.0.0.0:8444"))
    }

    /** Синтаксис правил проверяет сам nftables (режим -c: только проверка, без применения). */
    @Test
    fun nftAcceptsRules() {
        linuxOnly()
        assumeTrue("нет nft", File("/usr/sbin/nft").canExecute())
        val (_, rules) = sh("bash", script().path, "print", env = mapOf("FW_ROOT" to fakeRoot().path, "FW_SSH_PORTS" to "22"))
        val f = Files.createTempFile("rules", ".nft").toFile().apply { writeText(rules) }
        val (code, out) = sh("/usr/sbin/nft", "-c", "-f", f.path)
        assumeTrue("nft -c здесь недоступен: $out", !out.contains("Operation not permitted"))
        assertEquals(out, 0, code)
    }

    @Test
    fun scriptsAreValidBash() {
        linuxOnly()
        for (s in listOf(Scripts.firewallApply(), Scripts.firewallConfirm(), Scripts.fail2banInstall(), Scripts.disablePasswordAuth(), Scripts.fwScript)) {
            val (code, out) = sh("bash", "-n", input = s)
            assertEquals(out, 0, code)
        }
        // heredoc с правилом fail2ban и службой — без отступов, иначе bash не найдёт конец
        assertTrue(Scripts.fail2banInstall().contains("\n[sshd]\nenabled = true\nport = \${SSHP:-22}\nbackend = systemd\nmaxretry = 5\n"))
        assertTrue(Scripts.fail2banInstall().contains("\nJAIL\n"))
        assertTrue(Scripts.firewallApply().contains("\nUNIT\n"))
        assertTrue(Scripts.firewallApply().contains("--on-active=180"))
    }

    @Test
    fun sshIsHardened() {
        val s = Scripts.disablePasswordAuth()
        for (d in listOf("LoginGraceTime 30", "MaxAuthTries 3", "X11Forwarding no", "PasswordAuthentication no")) assertTrue(d, s.contains(d))
        assertTrue(s.contains("LoginGraceTime|MaxAuthTries|X11Forwarding"))   // старые строки этих директив убираются
    }

    /** Новые директивы понимает настоящий OpenSSH (sshd -t на временном конфиге). */
    @Test
    fun sshdAcceptsHardenedConfig() {
        linuxOnly()
        val dir = System.getenv("SSH_TEST_DIR")
        assumeTrue("SSH_TEST_DIR не задан", dir != null && File("/usr/sbin/sshd").canExecute())
        val cfg = Files.createTempFile("sshd", ".conf").toFile()
        cfg.writeText(File(dir, "sshd_config").readText() + "\nLoginGraceTime 30\nMaxAuthTries 3\nX11Forwarding no\n")
        val (code, out) = sh("/usr/sbin/sshd", "-t", "-f", cfg.path)
        assertEquals(out, 0, code)
    }

    @Test
    fun installersRefreshFirewall() {
        val hook = "/usr/local/sbin/vpngramm-fw apply"
        assertTrue(Scripts.remove(Protocol.HY2).contains(hook))
        assertTrue(Scripts.remove(Protocol.VLESS).contains(hook))
        assertTrue(Scripts.removeTelegram().contains(hook))
    }
}
