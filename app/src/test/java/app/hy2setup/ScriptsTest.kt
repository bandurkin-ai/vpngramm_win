package app.hy2setup

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ScriptsTest {
    private val params = InstallParams(
        port = 443, authPassword = "aaaa", obfsPassword = "bbbb",
        domain = "", email = "", sni = "www.bing.com", masqueradeUrl = "https://www.bing.com/",
    )

    @Test
    fun installScriptHasKeyParts() {
        val s = Scripts.install(params)
        assertTrue(s.contains("get.hy2.sh"))
        assertTrue(s.contains("type: salamander"))
        assertTrue(s.contains(Scripts.PIN_MARKER))
        assertTrue(s.contains("AUTH_PW='aaaa'"))
    }

    /** DNS не ждём: Let's Encrypt спрашивает серверы имён домена напрямую; служба сама повторяет выпуск сертификата. */
    @Test
    fun domainInstallDoesNotWaitForDns() {
        val s = Scripts.install(params.copy(domain = "vpn.example.com"))
        assertTrue(s.contains("DOMAIN='vpn.example.com'"))
        assertTrue(s.contains("acme:"))
        assertTrue(!s.contains("Ожидание DNS") && !s.contains("EXPECTED_IP") && !s.contains("dns.google"))
        assertTrue(s.contains("Restart=on-failure"))
        assertTrue(s.contains("служба ждёт сертификат Let's Encrypt"))
    }

    /** Сохраняет скрипты в build/scripts, чтобы их можно было проверить `bash -n` / shellcheck. */
    @Test
    fun plusInstallRestoresCertAndUsesHttpAcme() {
        val s = Scripts.install(
            params.copy(domain = "vpn.example.com", acmeHttp = true, cert = CertBackup("CRT-PEM", "KEY-PEM", "{}", 0)),
        )
        assertTrue(s.contains("ACME_HTTP=1"))
        assertTrue(s.contains("echo \"  type: http\""))
        val dir = Scripts.acmeCertDir("vpn.example.com")
        assertTrue(s.contains("base64 -d > '$dir/vpn.example.com.crt'"))
        assertTrue(s.contains(java.util.Base64.getEncoder().encodeToString("KEY-PEM".toByteArray())))
        // без Комбайна+ ничего этого нет
        assertTrue(!Scripts.install(params.copy(domain = "vpn.example.com")).contains(dir))
        val v = Scripts.installVless(VlessParams(443, "u", "www.microsoft.com", "ab", "vpn.example.com", "PRIV", "PUB"))
        assertTrue(v.contains("PRIV='PRIV'") && v.contains("PBK='PUB'"))
    }

    @Test
    fun dumpScripts() {
        val dir = File("build/scripts").apply { mkdirs() }
        File(dir, "install.sh").writeText(Scripts.install(params))
        File(dir, "install_domain.sh").writeText(
            Scripts.install(params.copy(domain = "vpn.example.com", email = "me@example.com")),
        )
        File(dir, "status.sh").writeText(Scripts.status())
        File(dir, "restart.sh").writeText(Scripts.restart(Protocol.HY2))
        File(dir, "remove.sh").writeText(Scripts.remove(Protocol.HY2))
        File(dir, "remove_vless.sh").writeText(Scripts.remove(Protocol.VLESS))
        File(dir, "export.sh").writeText(Scripts.exportLinks())
        File(dir, "export_profile.sh").writeText(Scripts.exportProfile())
        File(dir, "tg_secret.sh").writeText(Scripts.installTelegram(993, "www.microsoft.com", TgParams.generate(993, "www.microsoft.com").secret))
        File(dir, "tg_new.sh").writeText(Scripts.installTelegram(443, "www.microsoft.com"))
        File(dir, "harden.sh").writeText(Scripts.harden())
        File(dir, "upd_start.sh").writeText(Scripts.updatesStart())
        File(dir, "upd_poll.sh").writeText(Scripts.updatesPoll(1234))
        File(dir, "disable_password.sh").writeText(Scripts.disablePasswordAuth())
        File(dir, "reboot.sh").writeText(Scripts.reboot())
        File(dir, "install_tg.sh").writeText(Scripts.installTelegram(18443, "www.microsoft.com"))
        File(dir, "export_tg.sh").writeText(Scripts.exportTelegram())
        File(dir, "status_tg.sh").writeText(Scripts.statusTelegram())
        File(dir, "remove_tg.sh").writeText(Scripts.removeTelegram())
        val pemCrt = "-----BEGIN CERTIFICATE-----\nTEST\n-----END CERTIFICATE-----\n"
        File(dir, "install_plus.sh").writeText(
            Scripts.install(
                params.copy(domain = "vpn.example.com", sni = "vpn.example.com", acmeHttp = true,
                    cert = CertBackup(pemCrt, "-----BEGIN EC PRIVATE KEY-----\nK\n-----END EC PRIVATE KEY-----\n", "{\"sans\":[]}", 0)),
            ),
        )
        File(dir, "install_vless_keys.sh").writeText(
            Scripts.installVless(
                VlessParams(443, "11111111-2222-3333-4444-555555555555", "www.microsoft.com", "0123456789abcdef", "vpn.example.com",
                    privateKey = "iO2HTWeqgbV5Q-YiChZzgD9WnMleLH9aO0wt-FPL0Vo", publicKey = "OnzLYEwWXpv--0raqKhr-8YYr88Ix_cADvHl0LGKSHw"),
            ),
        )
        File(dir, "export_cert.sh").writeText(Scripts.exportCert("vpn.example.com"))
        // вкладка «Сервер», VLESS под свой домен: те же вызовы SelfMask, что делает приложение
        File(dir, "server_self_hy2.sh").writeText(
            Scripts.install(SelfMask.hy2("vpn.example.com", 443, "aaaa", "bbbb", "", "https://www.bing.com/")),
        )
        File(dir, "server_self_vless.sh").writeText(
            Scripts.installVless(SelfMask.vless("vpn.example.com", 443, "11111111-2222-3333-4444-555555555555", "0123456789abcdef")),
        )
        // Reality «под себя»: Hysteria2 отдаёт сайт по TCP на 127.0.0.1:8443, VLESS маскируется под свой домен
        File(dir, "install_plus_self.sh").writeText(
            Scripts.install(
                params.copy(domain = "vpn.example.com", sni = "vpn.example.com", acmeHttp = true,
                    localHttps = true, cert = CertBackup(pemCrt, "K", "{}", 0)),
            ),
        )
        File(dir, "install_vless_self.sh").writeText(
            Scripts.installVless(
                VlessParams(443, "11111111-2222-3333-4444-555555555555", "vpn.example.com", "0123456789abcdef", "vpn.example.com",
                    privateKey = "iO2HTWeqgbV5Q-YiChZzgD9WnMleLH9aO0wt-FPL0Vo", publicKey = "OnzLYEwWXpv--0raqKhr-8YYr88Ix_cADvHl0LGKSHw",
                    dest = StableProfile.LOCAL_HTTPS),
            ),
        )
        File(dir, "links_plus_self.txt").writeText(
            Links.hy2("vpn.example.com", 443, "aaaa", "bbbb", "vpn.example.com", "vpn.example.com", null) + "\n" +
                Links.vless("vpn.example.com", 443, "11111111-2222-3333-4444-555555555555", "vpn.example.com",
                    "OnzLYEwWXpv--0raqKhr-8YYr88Ix_cADvHl0LGKSHw", "0123456789abcdef") + "\n",
        )
        File(dir, "diagnose.sh").writeText(Scripts.diagnose("vpn.example.com"))
        File(dir, "live_check.sh").writeText(Scripts.liveCheck(40, "172.17.0.3"))
        File(dir, "live_result.sh").writeText(Scripts.liveResult())
        // ссылки, которые приложение выдало бы для этих же параметров (для сквозного теста клиентами)
        File(dir, "links_plus.txt").writeText(
            Links.hy2("vpn.example.com", 443, "aaaa", "bbbb", "vpn.example.com", "vpn.example.com", null) + "\n" +
                Links.vless("vpn.example.com", 443, "11111111-2222-3333-4444-555555555555", "www.microsoft.com",
                    "OnzLYEwWXpv--0raqKhr-8YYr88Ix_cADvHl0LGKSHw", "0123456789abcdef") + "\n",
        )
        File(dir, "install_vless.sh").writeText(
            Scripts.installVless(VlessParams(443, "11111111-2222-3333-4444-555555555555", "www.microsoft.com", "0123456789abcdef", "vpn.example.com")),
        )
    }

    @Test
    fun liveCheckEmbedsCollector() {
        val s = Scripts.liveCheck(90, "203.0.113.5; rm -rf /")
        fun unpack(x: String) = java.util.zip.GZIPInputStream(java.util.Base64.getDecoder()
            .decode(Regex("echo '([A-Za-z0-9+/=]+)' \\| base64 -d \\| gunzip").find(x)!!.groupValues[1]).inputStream())
            .bufferedReader().readText()
        val collector = unpack(s)
        assertTrue("сжатая проверка должна быть маленькой: ${s.length}", s.length < 6_000)
        assertTrue(collector.contains("SECS=90"))
        assertTrue("неправильный IP не подставляется", collector.contains("PHONE=''"))
        assertTrue(collector.contains("trap restore EXIT"))
        assertTrue(unpack(Scripts.liveCheck(90, "203.0.113.5")).contains("PHONE='203.0.113.5'"))
    }

    @Test
    fun bigScriptsArePacked() {
        val small = Scripts.ping()
        assertTrue(SshRunner.packed(small) == small)
        val big = Scripts.diagnose("vpn.example.com")
        val p = SshRunner.packed(big)
        val bytes = big.toByteArray().size
        assertTrue("сжатие: $bytes → ${p.length}", p.length < bytes * 2 / 3 && p.contains("| gunzip >"))
    }

    @Test
    fun updatesPollPosition() {
        assertEquals(1234L to "run", Scripts.parseUpdPos("##UPD_POS 1234 run"))
        assertEquals(0L to "done:100", Scripts.parseUpdPos("##UPD_POS 0 done:100 "))
        assertEquals(null, Scripts.parseUpdPos("Get:1 http://deb.debian.org"))
        assertEquals(null, Scripts.parseUpdPos("##UPD_POS x run"))
        // обновления запускаются отдельной службой и доделывают прерванную установку пакетов
        val start = Scripts.updatesStart()
        assertTrue(start.contains("systemd-run --quiet --unit=hy2setup-upd"))
        assertTrue(!Scripts.harden(false).contains("full-upgrade"))
        assertTrue(Scripts.harden(true).contains("dpkg --configure -a"))
    }

    /** XHTTP на том же порту: fallback из обычного VLESS во второй обработчик на локальном сокете. */
    @Test
    fun vlessWithXhttpFallback() {
        val base = SelfMask.vless("vpn.example.com", 443, "11111111-2222-3333-4444-555555555555", "0123456789abcdef").copy(
            privateKey = "iO2HTWeqgbV5Q-YiChZzgD9WnMleLH9aO0wt-FPL0Vo", publicKey = "OnzLYEwWXpv--0raqKhr-8YYr88Ix_cADvHl0LGKSHw",
            extraUuids = listOf("99999999-2222-3333-4444-555555555555"),
        )
        val plain = Scripts.installVless(base)
        assertTrue(!plain.contains("fallbacks") && !plain.contains(Scripts.XHTTP_SOCKET) && plain.contains("XPATH=\n"))
        val s = Scripts.installVless(base.copy(xhttpPath = "/xh123"))
        assertTrue(s.contains("\"fallbacks\": [ { \"dest\": \"@vpngramm-xhttp\", \"xver\": 0 } ], ${Scripts.XHTTP_TAG}"))
        val inbound = s.lines().single { it.contains("\"listen\": \"@vpngramm-xhttp\"") }
        assertTrue(inbound.trimEnd().endsWith(Scripts.XHTTP_TAG))
        assertTrue(inbound.contains("\"network\": \"xhttp\"") && inbound.contains("\"path\": \"/xh123\""))
        // у XHTTP те же люди, но без flow (Vision работает только поверх TCP)
        assertTrue(inbound.contains("{ \"id\": \"${'$'}{UUID}\" }") && inbound.contains("99999999-2222-3333-4444-555555555555") && !inbound.contains("flow"))
        assertTrue(s.contains("XPATH=/xh123"))
        assertTrue(Scripts.exportLinks().contains(Scripts.LINK_MARKER_XHTTP))
        // стенд: tools/vpn-test (xray run -test и подключение клиента)
        val dir = File("build/scripts").apply { mkdirs() }
        File(dir, "install_vless_xhttp.sh").writeText(s)
        File(dir, "remove_xhttp.sh").writeText(Scripts.removeXhttp())
    }

    /** Версия Xray — по редиректу github.com (без api.github.com), с повтором и прежним способом в запасе. */
    @Test
    fun xrayInstallDoesNotDependOnGithubApi() {
        val s = Scripts.installVless(VlessParams(443, "u", "www.microsoft.com", "ab", "vpn.example.com"))
        assertTrue(s.contains("https://github.com/XTLS/Xray-core/releases/latest"))
        assertTrue(s.contains("@ install --version \"${'$'}{XTAG}\""))
        assertTrue(s.contains("bash -c \"${'$'}{XI}\" @ install && XOK=1"))
        assertTrue(s.contains("for TRY in 1 2 3"))
    }

    /** «Проверить»: порты берутся из конфигураций протоколов, а не всегда 443. */
    @Test
    fun diagnoseUsesRealPorts() {
        val d = Scripts.diagnose("vpn.example.com")
        assertTrue(!d.contains("listening u 443") && !d.contains("listening t 443"))
        assertTrue(d.contains("listening u \"${'$'}HP\"") && d.contains("listening t \"${'$'}XP\""))
        assertTrue(d.contains("/etc/hysteria/config.yaml") && d.contains("hy2setup.env") && d.contains("/etc/mtg.toml"))
        assertTrue(d.contains("for P in ${'$'}FWP"))
        File("build/scripts").mkdirs()
        File("build/scripts/diagnose_ports.sh").writeText(d)
    }
}
