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
 * Запасная установка AmneziaWG из исходников на GitHub (hy2setup/awg-github.sh), когда Launchpad недоступен.
 * «GitHub» — локальные git-репозитории с тегами (настоящий git), make — настоящий с подставными Makefile,
 * dkms и systemctl — заглушки, записывающие вызовы.
 */
class AwgGithubTest {
    private fun linuxOnly() = assumeTrue("скрипты сервера проверяются только на Linux", System.getProperty("os.name").orEmpty().startsWith("Linux"))

    private class Run(val code: Int, val out: String, val calls: String)

    private fun sh(dir: File, vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { cmd.joinToString(" ") + ": " + out }
        return out.trim()
    }

    /** Репозиторий с тегом и src/Makefile, который записывает цели и параметры make в [calls]. Возвращает коммит. */
    private fun repo(base: File, name: String, tag: String, calls: File): String {
        val d = File(base, "$name.git").apply { mkdirs() }
        File(d, "src").mkdirs()
        File(d, "src/Makefile").writeText(
            "all:\n\techo \"make $name all\" >> ${calls.path}\n" +
                "dkms-install:\n\techo \"make $name dkms-install DKMSDIR=\$(DKMSDIR)\" >> ${calls.path}\n" +
                "install:\n\techo \"make $name install PREFIX=\$(PREFIX) WITH_WGQUICK=\$(WITH_WGQUICK) WITH_SYSTEMDUNITS=\$(WITH_SYSTEMDUNITS) SYSTEMDUNITDIR=\$(SYSTEMDUNITDIR)\" >> ${calls.path}\n",
        )
        sh(d, "git", "init", "-q")
        sh(d, "git", "add", "-A")
        sh(d, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "src")
        sh(d, "git", "tag", tag)
        return sh(d, "git", "rev-parse", "HEAD")
    }

    /** [kmodSha]/[toolsSha] — какие коммиты скрипт ожидает (null — настоящие из репозитория); [dkmsBuildFails] — сборка модуля падает. */
    private fun run(kmodSha: String? = null, toolsSha: String? = null, dkmsBuildFails: Boolean = false, noRepos: Boolean = false): Run {
        val d = Files.createTempDirectory("awggh").toFile()
        val calls = File(d, "calls").apply { writeText("") }
        val gh = File(d, "gh").apply { mkdirs() }
        val script0 = Scripts.awgGithubScript
        val kTag = Regex("AWG_GH_KMOD_TAG=(\\S+)").find(script0)!!.groupValues[1]
        val tTag = Regex("AWG_GH_TOOLS_TAG=(\\S+)").find(script0)!!.groupValues[1]
        var script = script0
        if (!noRepos) {
            val k = repo(gh, "amneziawg-linux-kernel-module", kTag, calls)
            val t = repo(gh, "amneziawg-tools", tTag, calls)
            script = script
                .replace(Regex("AWG_GH_KMOD_SHA=\\S+"), "AWG_GH_KMOD_SHA=${kmodSha ?: k}")
                .replace(Regex("AWG_GH_TOOLS_SHA=\\S+"), "AWG_GH_TOOLS_SHA=${toolsSha ?: t}")
        }
        val bin = File(d, "bin").apply { mkdirs() }
        fun stub(name: String, body: String) = File(bin, name).apply { writeText("#!/bin/bash\n$body\n"); setExecutable(true) }
        stub("dkms", "echo \"dkms \$*\" >> ${calls.path}; " + if (dkmsBuildFails) "[ \"\$1\" = build ] && exit 10; exit 0" else "exit 0")
        stub("systemctl", "echo \"systemctl \$*\" >> ${calls.path}")
        stub("getent", "echo '140.82.121.3 STREAM github.com'")
        stub("sleep", "exit 0")
        val hosts = File(d, "hosts").apply { writeText("127.0.0.1 localhost\n") }
        val t = File(d, "t.sh")
        t.writeText("#!/bin/bash\nset -e\n" + Scripts.awgRepoScript + "\n" + script + "\ntrap awg_hosts_cleanup EXIT\nset +e\nawg_github_install 6.12.95+deb12-amd64\n")
        val pb = ProcessBuilder("bash", t.path).redirectErrorStream(true)
        pb.environment()["PATH"] = bin.path + File.pathSeparator + System.getenv("PATH")
        pb.environment()["AWG_GH_BASE"] = "file://" + gh.path
        pb.environment()["AWG_GH_DIR"] = File(d, "src").path
        pb.environment()["AWG_HOSTS"] = hosts.path
        pb.environment()["AWG_PAUSE"] = "0"
        pb.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return Run(p.waitFor(), out, calls.readText())
    }

    @Test
    fun buildsPinnedSourcesWithDkms() {
        linuxOnly()
        val r = run()
        assertEquals(r.out + r.calls, 0, r.code)
        val lines = r.calls.lines().filter { it.isNotBlank() }
        val order = listOf(
            "dkms remove -m amneziawg -v 1.0.0 --all",
            "make amneziawg-linux-kernel-module dkms-install DKMSDIR=/usr/src/amneziawg-1.0.0",
            "dkms add -m amneziawg -v 1.0.0",
            "dkms build -m amneziawg -v 1.0.0 -k 6.12.95+deb12-amd64",
            "dkms install -m amneziawg -v 1.0.0 -k 6.12.95+deb12-amd64 --force",
            "make amneziawg-tools all",
            "make amneziawg-tools install PREFIX=/usr WITH_WGQUICK=yes WITH_SYSTEMDUNITS=yes SYSTEMDUNITDIR=/lib/systemd/system",
            "systemctl daemon-reload",
        )
        assertEquals(r.calls, order, lines)
        assertTrue(r.out, r.out.contains("Скачан amneziawg-linux-kernel-module v3.1."))
        assertTrue(r.out, r.out.contains("Утилиты awg и awg-quick установлены"))
    }

    @Test
    fun wrongCommitIsNotBuilt() {
        linuxOnly()
        val r = run(kmodSha = "0".repeat(40))
        assertNotEquals(0, r.code)
        assertTrue(r.out, r.out.contains("Собирать не буду"))
        assertFalse(r.calls, r.calls.contains("dkms add") || r.calls.contains("make "))
    }

    @Test
    fun wrongToolsCommitIsNotBuilt() {
        linuxOnly()
        val r = run(toolsSha = "1".repeat(40))
        assertNotEquals(0, r.code)
        assertTrue(r.out, r.out.contains("amneziawg-tools"))
        assertFalse(r.calls, r.calls.contains("dkms add"))
    }

    @Test
    fun dkmsBuildFailureIsReported() {
        linuxOnly()
        val r = run(dkmsBuildFails = true)
        assertNotEquals(0, r.code)
        assertTrue(r.out, r.out.contains("модуль AmneziaWG не собрался под ядро 6.12.95+deb12-amd64"))
        assertFalse(r.calls, r.calls.contains("amneziawg-tools install"))
    }

    @Test
    fun unreachableGithubIsRetriedThenReported() {
        linuxOnly()
        val r = run(noRepos = true)
        assertNotEquals(0, r.code)
        assertTrue(r.out, r.out.contains("GitHub пока недоступен"))
        assertTrue(r.out, r.out.contains("ОШИБКА: не удалось скачать amneziawg-linux-kernel-module с GitHub"))
        assertTrue(r.calls, r.calls.isBlank())
    }
}
