package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Подготовка репозитория Amnezia перед установкой AmneziaWG (hy2setup/awg-repo.sh): повторы, обход сбоя DNS
 * через DNS-over-HTTPS и понятная ошибка. apt-get, apt-cache, getent и curl подменяются заглушками в PATH.
 */
class AwgRepoTest {
    private fun linuxOnly() = assumeTrue("скрипты сервера проверяются только на Linux", System.getProperty("os.name").orEmpty().startsWith("Linux"))

    private class Run(val code: Int, val out: String, val hosts: String, val hostsAfterExit: String)

    /**
     * [readyAfter] — с какой по счёту попытки `apt-get update` пакеты из PPA становятся видны (0 — никогда);
     * [readyWithHosts] — видны, только если имя PPA прописано в hosts; [dns] — разрешает ли DNS сервера имя PPA;
     * [doh] — ответ DNS-over-HTTPS (пусто — недоступен).
     */
    private fun run(readyAfter: Int, readyWithHosts: Boolean = false, dns: Boolean = true, doh: String = ""): Run {
        val d = Files.createTempDirectory("awgrepo").toFile()
        val bin = File(d, "bin").apply { mkdirs() }
        val hosts = File(d, "hosts").apply { writeText("127.0.0.1 localhost\n") }
        val state = File(d, "updates")
        fun stub(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/bash\n$body\n"); setExecutable(true) }
        stub("apt-get", """
            n=${'$'}(( ${'$'}(cat "${state.path}" 2>/dev/null || echo 0) + 1 )); echo ${'$'}n > "${state.path}"
            echo "Hit:1 http://deb.debian.org/debian bookworm InRelease"
            echo "Err:5 https://ppa.launchpadcontent.net/amnezia/ppa/ubuntu focal InRelease"
            echo "  Temporary failure resolving 'ppa.launchpadcontent.net'"
            exit 0
        """.trimIndent())
        stub("apt-cache", """
            n=${'$'}(cat "${state.path}" 2>/dev/null || echo 0)
            if [ $readyAfter -gt 0 ] && [ "${'$'}n" -ge $readyAfter ]; then echo "  Candidate: 1.0.20240711-1"; exit 0; fi
            if ${if (readyWithHosts) "true" else "false"} && grep -q 'ppa.launchpadcontent.net # vpngramm-tmp' "${hosts.path}"; then echo "  Candidate: 1.0.20240711-1"; exit 0; fi
            echo "  Candidate: (none)"
        """.trimIndent())
        stub("getent", if (dns) "echo '185.125.190.80 STREAM ppa.launchpadcontent.net'; exit 0" else "exit 2")
        stub("curl", if (doh.isEmpty()) "exit 6" else "echo '{\"Status\":0,\"Answer\":[{\"name\":\"ppa.launchpadcontent.net\",\"type\":1,\"data\":\"$doh\"}]}'")
        stub("sleep", "exit 0")
        val script = File(d, "t.sh")
        script.writeText(
            "#!/bin/bash\nset -e\n" + Scripts.awgRepoScript +
                "\ntrap awg_hosts_cleanup EXIT\nset +e\nawg_repo_prepare\nrc=\$?\ncp \"\$AWG_HOSTS\" \"${d.path}/hosts.during\"\nexit \$rc\n",
        )
        val pb = ProcessBuilder("bash", script.path).redirectErrorStream(true)
        pb.environment()["PATH"] = bin.path + File.pathSeparator + System.getenv("PATH")
        pb.environment()["AWG_HOSTS"] = hosts.path
        pb.environment()["AWG_TRIES"] = "3"
        pb.environment()["AWG_PAUSE"] = "0"
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return Run(code, out, File(d, "hosts.during").readText(), hosts.readText())
    }

    @Test
    fun readyAtOnce() {
        linuxOnly()
        val r = run(readyAfter = 1)
        assertEquals(r.out, 0, r.code)
        assertFalse(r.out, r.out.contains("повтор"))
        assertFalse(r.hosts.contains("vpngramm-tmp"))
    }

    @Test
    fun transientFailureIsRetried() {
        linuxOnly()
        val r = run(readyAfter = 2)
        assertEquals(r.out, 0, r.code)
        assertTrue(r.out, r.out.contains("Репозиторий Amnezia пока недоступен"))
        assertTrue(r.out, r.out.contains("Temporary failure resolving"))   // причина из вывода apt
        assertTrue(r.out, r.out.contains("попытка 2 из 3"))
        assertFalse(r.hosts.contains("vpngramm-tmp"))
    }

    @Test
    fun brokenServerDnsIsBypassedViaDoh() {
        linuxOnly()
        val r = run(readyAfter = 0, readyWithHosts = true, dns = false, doh = "185.125.190.80")
        assertEquals(r.out, 0, r.code)
        assertTrue(r.out, r.out.contains("DNS сервера не разрешает ppa.launchpadcontent.net"))
        // во время установки имя прописано, при выходе строка убрана, чужие строки hosts не тронуты
        assertTrue(r.hosts, r.hosts.contains("185.125.190.80 ppa.launchpadcontent.net # vpngramm-tmp"))
        assertFalse(r.hostsAfterExit, r.hostsAfterExit.contains("vpngramm-tmp"))
        assertTrue(r.hostsAfterExit.contains("127.0.0.1 localhost"))
    }

    @Test
    fun everythingDownGivesClearError() {
        linuxOnly()
        val r = run(readyAfter = 0, dns = false, doh = "")
        assertNotEquals(0, r.code)
        assertTrue(r.out, r.out.contains("ОШИБКА: сервер не может открыть репозиторий Amnezia"))
        assertTrue(r.out, r.out.contains("перезагрузки не будет"))
        assertFalse(r.hostsAfterExit.contains("vpngramm-tmp"))
    }

    @Test
    fun dnsWorksButRepoDownGivesClearError() {
        linuxOnly()
        val r = run(readyAfter = 0, dns = true)
        assertNotEquals(0, r.code)
        assertFalse(r.out, r.out.contains("DNS-over-HTTPS"))   // DNS в порядке — обход не нужен
        assertTrue(r.out, r.out.contains("ОШИБКА: сервер не может открыть репозиторий Amnezia"))
    }

    /** Полный скрипт установки AmneziaWG синтаксически верен и вызывает подготовку репозитория до сборки модуля. */
    @Test
    fun fullAwgScriptIsValid() {
        linuxOnly()
        val s = Scripts.awgJobBodyForTest(Awg.generate("vpn.example.org", 34127))
        val p = ProcessBuilder("bash", "-n").redirectErrorStream(true).start()
        p.outputStream.use { it.write(s.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        assertTrue(s.indexOf("awg_repo_prepare || exit 1") in 1 until s.indexOf("install dkms build-essential"))
        assertTrue(s.contains("trap awg_hosts_cleanup EXIT"))
    }
}
