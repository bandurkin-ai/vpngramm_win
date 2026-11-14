package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Проверка системы и ядра сервера (os-lib.sh): Debian 11/12/13+, Ubuntu 22/24/25/26+, Alpine; слишком старые системы отклоняются.
 * Скрипт запускается настоящим bash с подставным os-release; службы OpenRC — заглушки, записывающие вызовы.
 */
class OsLibTest {
    private fun linuxOnly() = assumeTrue("скрипты сервера проверяются только на Linux", System.getProperty("os.name").orEmpty().startsWith("Linux"))

    private class Res(val code: Int, val out: String)

    private fun bash(body: String, env: Map<String, String>): Res {
        val lib = Files.createTempFile("os-lib", ".sh").toFile().apply { writeText(Scripts.osLib); deleteOnExit() }
        val script = "step() { echo; echo \"==> \$1\"; }\n. ${lib.path}\n$body\n"
        val pb = ProcessBuilder("bash", "-c", script).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        return Res(p.waitFor(), out)
    }

    private fun osRelease(id: String, ver: String?, pretty: String, like: String = "", codename: String = ""): String {
        val f = Files.createTempFile("os-release", "").toFile().apply { deleteOnExit() }
        f.writeText(buildString {
            appendLine("ID=$id"); if (ver != null) appendLine("VERSION_ID=\"$ver\"")
            appendLine("PRETTY_NAME=\"$pretty\""); if (like.isNotEmpty()) appendLine("ID_LIKE=\"$like\"")
            if (codename.isNotEmpty()) appendLine("VERSION_CODENAME=$codename")
        })
        return f.path
    }

    private fun detect(id: String, ver: String?, pretty: String, kernel: String = "6.1.0-13-amd64", init: String = "systemd", like: String = ""): LinuxInfo {
        val r = bash("os_marker", mapOf("OSLIB_OSRELEASE" to osRelease(id, ver, pretty, like), "OSLIB_KERNEL" to kernel, "OSLIB_INIT" to init))
        assertEquals(r.out, 0, r.code)
        val line = r.out.lines().first { it.startsWith(Scripts.OS_MARKER) }
        return assertNotNull(LinuxInfo.parse(line)).let { LinuxInfo.parse(line)!! }
    }

    @Test
    fun supportedVersions() {
        linuxOnly()
        val ok = listOf(
            Triple("debian", "11", "Debian GNU/Linux 11 (bullseye)"), Triple("debian", "12", "Debian GNU/Linux 12 (bookworm)"),
            Triple("debian", "13", "Debian GNU/Linux 13 (trixie)"), Triple("debian", "14", "Debian GNU/Linux 14"),
            Triple("ubuntu", "22.04", "Ubuntu 22.04.5 LTS"), Triple("ubuntu", "24.04", "Ubuntu 24.04.2 LTS"),
            Triple("ubuntu", "25.10", "Ubuntu 25.10"), Triple("ubuntu", "26.04", "Ubuntu 26.04 LTS"), Triple("ubuntu", "28.04", "Ubuntu 28.04"),
            Triple("alpine", "3.18.9", "Alpine Linux v3.18"), Triple("alpine", "3.22.1", "Alpine Linux v3.22"),
        )
        for ((id, v, name) in ok) assertEquals("$name должна подходить", "ok", detect(id, v, name).support)
        // Debian testing/sid без номера версии — новейшая
        assertEquals("ok", detect("debian", null, "Debian GNU/Linux forky/sid").support)
    }

    @Test
    fun tooOldVersionsAreRejected() {
        linuxOnly()
        for ((id, v) in listOf("debian" to "10", "debian" to "9", "ubuntu" to "20.04", "ubuntu" to "18.04")) {
            val info = detect(id, v, "$id $v")
            assertEquals("$id $v", "old", info.support)
            assertTrue(info.problem()!!.contains("слишком старая"))
        }
        val r = bash("os_require", mapOf("OSLIB_OSRELEASE" to osRelease("debian", "10", "Debian GNU/Linux 10 (buster)"), "OSLIB_INIT" to "systemd"))
        assertEquals(1, r.code)
        assertTrue(r.out, r.out.contains("слишком старая"))
    }

    @Test
    fun derivativesAndOthers() {
        linuxOnly()
        assertEquals("ok", detect("linuxmint", "22", "Linux Mint 22", like = "ubuntu debian").support)
        assertEquals("other", detect("centos", "9", "CentOS Stream 9", like = "rhel fedora").support)
        val warn = bash("os_require", mapOf("OSLIB_OSRELEASE" to osRelease("fedora", "41", "Fedora 41"), "OSLIB_INIT" to "systemd"))
        assertTrue(warn.out, warn.out.contains("не из списка проверенных"))
    }

    @Test
    fun kernelAndInitAreParsed() {
        linuxOnly()
        val a = detect("alpine", "3.20.3", "Alpine Linux v3.20", kernel = "6.6.54-0-lts", init = "openrc")
        assertEquals("alpine", a.id); assertEquals("openrc", a.init); assertEquals(6 to 6, a.kernelVersion())
        assertTrue(a.notes().any { it.contains("OpenRC") && it.contains("amneziawg-go") })
        val d = detect("debian", "12", "Debian GNU/Linux 12 (bookworm)", kernel = "6.1.0-13-amd64")
        assertEquals(6 to 1, d.kernelVersion())
        assertTrue(d.notes().any { it.contains("поставит новое ядро") })
        val d11 = detect("debian", "11", "Debian GNU/Linux 11 (bullseye)", kernel = "5.10.0-28-amd64")
        assertEquals(5 to 10, d11.kernelVersion())
        assertTrue(d11.notes().any { it.contains("Debian 11") })
        assertTrue(detect("debian", "13", "Debian GNU/Linux 13 (trixie)", kernel = "6.12.9-amd64").notes().isEmpty())
        assertTrue(detect("ubuntu", "26.04", "Ubuntu 26.04 LTS", kernel = "6.20.0-3-generic").notes().isEmpty())
    }

    @Test
    fun probeLineParsing() {
        val i = LinuxInfo.parse("##OS|alpine|3.20.3|6.6.54-0-lts|openrc|apk|amd64|ok|Alpine Linux v3.20")!!
        assertTrue(i.isAlpine); assertNull(i.problem()); assertEquals("Alpine Linux v3.20", i.name)
        assertNull(LinuxInfo.parse("Система: Debian"))
        assertEquals(
            "На сервере нет ни systemd, ни OpenRC",
            LinuxInfo.parse("##OS|x|1|5.4|none|apt|amd64|other|X")!!.problem()!!.substringBefore(" —"),
        )
        assertTrue(Scripts.osProbe().contains("os_marker"))
    }

    /** На OpenRC systemctl и journalctl превращаются в rc-service / rc-update — остальные скрипты работают без правок. */
    @Test
    fun systemctlShimOnOpenRc() {
        linuxOnly()
        val dir = Files.createTempDirectory("openrc").toFile()
        val bin = File(dir, "bin").apply { mkdirs() }
        val initd = File(dir, "init.d").apply { mkdirs() }
        val calls = File(dir, "calls")
        for (n in listOf("xray", "awg-quick-awg0")) File(initd, n).writeText("#!/sbin/openrc-run\n")
        for (tool in listOf("rc-service", "rc-update")) {
            File(bin, tool).apply { writeText("#!/bin/sh\necho \"$tool \$*\" >> ${calls.path}\n[ \"\$2\" = status ] && [ \"\$1\" = xray ] && exit 0\n[ \"\$2\" = status ] && exit 3\nexit 0\n"); setExecutable(true) }
        }
        val env = mapOf("PATH" to bin.path + ":" + System.getenv("PATH"), "OSLIB_INIT" to "openrc", "OSLIB_INITD" to initd.path,
            "OSLIB_OSRELEASE" to osRelease("alpine", "3.20.3", "Alpine Linux v3.20"))
        val r = bash(
            """
            systemctl enable --now awg-quick@awg0.service
            systemctl restart xray.service
            systemctl is-active --quiet xray.service && echo "xray-active"
            systemctl is-active --quiet awg-quick@awg0.service || echo "awg-inactive"
            systemctl cat xray.service >/dev/null 2>&1 && echo "xray-exists"
            systemctl cat mtg.service >/dev/null 2>&1 || echo "mtg-missing"
            systemctl daemon-reload && systemctl reset-failed foo.service && echo "noop-ok"
            echo "show=${'$'}(systemctl show -p NRestarts --value xray.service)"
            """.trimIndent(), env,
        )
        assertEquals(r.out, 0, r.code)
        for (m in listOf("xray-active", "awg-inactive", "xray-exists", "mtg-missing", "noop-ok", "show=0")) assertTrue("$m: ${r.out}", r.out.contains(m))
        val log = calls.readText()
        assertTrue(log, log.contains("rc-update add awg-quick-awg0 default"))
        assertTrue(log, log.contains("rc-service awg-quick-awg0 start"))
        assertTrue(log, log.contains("rc-service xray restart"))
    }

    @Test
    fun openRcServiceFileIsGenerated() {
        linuxOnly()
        val initd = Files.createTempDirectory("initd").toFile()
        val r = bash(
            "OR_DELAY=30 openrc_service hysteria-server 'Hysteria2 server' /usr/local/bin/hysteria server --config /etc/hysteria/config.yaml",
            mapOf("OSLIB_INITD" to initd.path, "OSLIB_INIT" to "systemd"),
        )
        assertEquals(r.out, 0, r.code)
        val f = File(initd, "hysteria-server").readText()
        assertTrue(f, f.startsWith("#!/sbin/openrc-run"))
        assertTrue(f, f.contains("command=\"/usr/local/bin/hysteria\"") && f.contains("command_args=\"server --config /etc/hysteria/config.yaml\""))
        assertTrue(f, f.contains("respawn_delay=30") && f.contains("supervisor=supervise-daemon"))
    }

    /** Все установочные скрипты — корректный bash и подстраиваются под систему. */
    @Test
    fun installScriptsAdaptToSystem() {
        linuxOnly()
        val hy = Scripts.install(InstallParams(443, "a", "b", "", "e@example.org", "www.example.org", "https://example.org"))
        val vl = Scripts.installVless(VlessParams(8443, "00000000-0000-0000-0000-000000000000", "www.example.org", "ab12", "203.0.113.10"))
        val tg = Scripts.installTelegram(993, "www.example.org")
        val awg = Scripts.awgJobBodyForTest(Awg.generate("203.0.113.10", 51820))
        val awgAlpine = Scripts.awgJobBodyForTest(Awg.generate("203.0.113.10", 51820), alpine = true)
        for ((name, s) in listOf("hy2" to hy, "vless" to vl, "tg" to tg, "awg" to awg, "awgAlpine" to awgAlpine, "harden" to Scripts.harden(true), "f2b" to Scripts.fail2banInstall(),
            "fw" to Scripts.firewallApply(), "facts" to Scripts.serverFacts(), "probe" to Scripts.osProbe(), "status" to Scripts.status(),
            "remove" to Scripts.remove(Protocol.AWG), "check" to ServerCheck.script())) {
            val f = Files.createTempFile("sc", ".sh").toFile().apply { writeText(s); deleteOnExit() }
            val r = ProcessBuilder("bash", "-n", f.path).redirectErrorStream(true).start().let { it.inputStream.bufferedReader().readText() to it.waitFor() }
            assertEquals("$name: ${r.first}", 0, r.second)
        }
        for (s in listOf(hy, vl, tg, awg)) assertTrue(s.contains("os_require"))
        assertTrue(hy.contains("hy2_openrc_install") && hy.contains("get.hy2.sh"))
        assertTrue(vl.contains("xray_openrc_install") && vl.contains("Xray-install"))
        assertTrue(tg.contains("openrc_service mtg") && tg.contains("mtg.service"))
        assertTrue(awg.contains("awg_alpine_install") && awg.contains("amneziawg-dkms") && awg.contains("debian:bullseye|debian:bookworm) C=focal"))
        assertTrue(awgAlpine.contains("awg_alpine_install()") && awgAlpine.contains("amneziawg-go") && !awgAlpine.contains("build-essential") && !awgAlpine.contains("#APT"))
        assertTrue(!awg.contains("awg_alpine_install()") && !awg.contains("#APT-"))
        assertTrue(Scripts.harden(true).contains("apk upgrade") && Scripts.harden(true).contains("apt-get"))
        assertTrue(Scripts.fail2banInstall().contains("fail2ban-openrc") && Scripts.fail2banInstall().contains("/var/log/messages"))
        assertTrue(Scripts.firewallApply().contains("/etc/init.d/vpngramm-fw"))
        assertFalse("перезагрузка не через systemctl (в sh -c его нет)", Scripts.reboot().contains("systemctl reboot"))
    }

    /** Общий слой не раздувает передачу: сжатые скрипты остаются меньше ~16 КБ (дальше мобильные сети «замораживают» SSH). */
    @Test
    fun packedScriptsStaySmall() {
        val sizes = mapOf(
            "hy2" to Scripts.install(InstallParams(443, "a", "b", "", "e@example.org", "www.example.org", "https://example.org")),
            "vless" to Scripts.installVless(VlessParams(8443, "00000000-0000-0000-0000-000000000000", "www.example.org", "ab12", "203.0.113.10")),
            "tg" to Scripts.installTelegram(993, "www.example.org"),
            "awg" to Scripts.installAwg(Awg.generate("203.0.113.10", 51820)),
            "awgAlpine" to Scripts.installAwg(Awg.generate("203.0.113.10", 51820), alpine = true),
            "fw" to Scripts.firewallApply(), "f2b" to Scripts.fail2banInstall(), "upd" to Scripts.updatesStart(),
        ).mapValues { SshRunner.packed(it.value).length }
        for ((n, len) in sizes) assertTrue("$n: $len", len < 16_000)
    }
}
