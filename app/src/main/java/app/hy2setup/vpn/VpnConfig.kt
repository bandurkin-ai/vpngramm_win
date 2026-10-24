package app.hy2setup.vpn

import app.hy2setup.Awg
import app.hy2setup.AwgPeer
import app.hy2setup.ProfileUser
import app.hy2setup.StableProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Конфигурация sing-box для встроенного клиента. Адреса TUN задаёт приложение (VpnService.Builder) —
 * те же значения стоят в inbound «tun», чтобы sing-box знал свою сеть.
 */
/** Протокол встроенного клиента. */
enum class VpnProtocol(val title: String) { VLESS("VLESS"), XHTTP("VLESS XHTTP"), HY2("Hysteria2"), AWG("AmneziaWG") }

object VpnConfig {
    const val TUN_V4 = "172.19.0.1/30"
    const val TUN_V6 = "fdfe:dcba:9876::1/126"
    const val TUN_DNS = "172.19.0.2"
    const val MTU = 1500

    /** Российские сайты напрямую (по желанию): домены .ru/.рф/.su и российские IP — мимо VPN. */
    data class Options(
        val bypassRu: Boolean = false,
        val dns: String = "1.1.1.1",
        /** DNS через туннель обычным UDP вместо DoH (для стенда без выхода на 443). */
        val dnsUdp: Boolean = false,
        val logLevel: String = "warn",
    )

    /**
     * VLESS + Reality к серверу [p] с ключами человека [user] (null — владелец).
     * [serverIp] — заранее найденный IPv4 сервера: так движку не нужен DNS до подключения.
     */
    fun vless(p: StableProfile, user: ProfileUser?, serverIp: String, o: Options = Options()): String {
        val proxy = JSONObject().apply {
            put("type", "vless"); put("tag", "proxy")
            put("server", serverIp); put("server_port", p.vlessPort)
            put("uuid", user?.vlessUuid ?: p.vlessUuid)
            put("flow", "xtls-rprx-vision")
            put("packet_encoding", "xudp")
            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", p.realitySni)
                put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
                put("reality", JSONObject().put("enabled", true).put("public_key", p.realityPublic).put("short_id", p.realityShortId))
            })
        }
        return build(proxy, o)
    }

    /**
     * VLESS + Reality + XHTTP к серверу [p]: в sing-box нет XHTTP, поэтому соединение строит встроенный в движок
     * Xray-core (свой тип «xray»; внутри — исходящее соединение в формате Xray). Ключи и порт — как у VLESS.
     */
    fun vlessXhttp(p: StableProfile, user: ProfileUser?, serverIp: String, o: Options = Options()): String {
        require(p.xhttpActive) { "На сервере нет VLESS XHTTP." }
        val xray = JSONObject().apply {
            put("protocol", "vless")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", serverIp); put("port", p.vlessPort)
                put("users", JSONArray().put(JSONObject().put("id", user?.vlessUuid ?: p.vlessUuid).put("encryption", "none")))
            })))
            put("streamSettings", JSONObject().apply {
                put("network", "xhttp")
                put("xhttpSettings", JSONObject().put("path", p.xhttpPathEff).put("mode", "auto"))
                put("security", "reality")
                put("realitySettings", JSONObject().apply {
                    put("serverName", p.realitySni); put("publicKey", p.realityPublic)
                    put("shortId", p.realityShortId); put("fingerprint", "chrome")
                })
            })
        }
        return build(JSONObject().put("type", "xray").put("tag", "proxy").put("xray", xray), o)
    }

    /**
     * Hysteria2 + Salamander к серверу [p]. Сертификат у сервера настоящий (Let's Encrypt на домен профиля),
     * поэтому проверка TLS включена. Если людей несколько, сервер в режиме userpass: пароль «имя:пароль».
     */
    fun hysteria2(p: StableProfile, user: ProfileUser?, serverIp: String, o: Options = Options()): String {
        val auth = when {
            !p.hy2MultiUser -> p.hy2Auth
            user != null -> "${user.hy2Name}:${user.hy2Pass}"
            else -> "${StableProfile.OWNER_HY2}:${p.hy2Auth}"
        }
        val proxy = JSONObject().apply {
            put("type", "hysteria2"); put("tag", "proxy")
            put("server", serverIp); put("server_port", p.hy2Port)
            put("password", auth)
            put("obfs", JSONObject().put("type", "salamander").put("password", p.hy2Obfs))
            put("tls", JSONObject().put("enabled", true).put("server_name", p.fqdn).put("alpn", JSONArray().put("h3")))
        }
        return build(proxy, o)
    }

    /**
     * AmneziaWG к серверу [p] (свой тип «awg» движка vpncore: amneziawg-go с маскировкой). Ключи человека [user]
     * или владельца; параметры маскировки — общие из профиля. Режим RandomTrailers встроенный клиент не умеет.
     */
    fun amneziaWg(p: StableProfile, user: ProfileUser?, serverIp: String, o: Options = Options()): String {
        val a = requireNotNull(p.awg) { "На сервере нет AmneziaWG." }
        require(!a.trailers) { "Встроенный клиент пока не умеет режим RandomTrailers — подключитесь через AmneziaVPN или выключите его." }
        val peer = user?.let { AwgPeer(it.awgPriv, it.awgPsk, it.awgIp) }
        val proxy = JSONObject().apply {
            put("type", "awg"); put("tag", "proxy")
            put("server", serverIp); put("server_port", a.port)
            put("private_key", peer?.priv ?: a.clientPriv)
            put("peer_public_key", a.serverPub)
            put("pre_shared_key", peer?.psk ?: a.psk)
            put("address", JSONArray().put("${peer?.ip ?: Awg.CLIENT_IP}/32"))
            put("dns", JSONArray().put(Awg.DNS1).put(Awg.DNS2))
            put("mtu", Awg.MTU)
            put("persistent_keepalive", 25)
            put("jc", a.jc); put("jmin", a.jmin); put("jmax", a.jmax)
            put("s1", a.s1); put("s2", a.s2); put("s3", a.s3); put("s4", a.s4)
            put("h1", "1"); put("h2", "2"); put("h3", "3"); put("h4", "4")
            put("i1", Awg.I1)
            put("header_protection_key", a.headerKey)
            put("content_padding_addition", Awg.PADDING)
        }
        return build(proxy, o)
    }

    /** Протоколы, которые стоят на сервере [p] и подходят встроенному клиенту. */
    fun available(p: StableProfile): List<VpnProtocol> = listOfNotNull(
        VpnProtocol.VLESS.takeIf { p.vlessOn },
        VpnProtocol.XHTTP.takeIf { p.xhttpActive },
        VpnProtocol.HY2.takeIf { p.hy2On },
        VpnProtocol.AWG.takeIf { p.awgOn && p.awg != null },
    )

    /** Конфигурация для [proto]; IllegalArgumentException — с понятным текстом, если протокол не подходит. */
    fun forProtocol(proto: VpnProtocol, p: StableProfile, user: ProfileUser?, serverIp: String, o: Options = Options()): String = when (proto) {
        VpnProtocol.VLESS -> vless(p, user, serverIp, o)
        VpnProtocol.XHTTP -> vlessXhttp(p, user, serverIp, o)
        VpnProtocol.HY2 -> hysteria2(p, user, serverIp, o)
        VpnProtocol.AWG -> amneziaWg(p, user, serverIp, o)
    }

    private fun build(proxy: JSONObject, o: Options): String {
        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (o.bypassRu) {
            rules.put(JSONObject().put("domain_suffix", JSONArray(listOf("ru", "xn--p1ai", "su"))).put("outbound", "direct"))
            rules.put(JSONObject().put("rule_set", JSONArray().put("geoip-ru")).put("outbound", "direct"))
        }
        val route = JSONObject().apply {
            put("rules", rules)
            put("final", "proxy")
            put("auto_detect_interface", true)
            if (o.bypassRu) {
                put("rule_set", JSONArray().put(JSONObject().apply {
                    put("type", "remote"); put("tag", "geoip-ru"); put("format", "binary")
                    put("url", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs")
                    put("download_detour", "proxy")
                }))
            }
        }
        val dns = JSONObject().apply {
            put("servers", JSONArray()
                .put(JSONObject().put("type", if (o.dnsUdp) "udp" else "https").put("tag", "remote").put("server", o.dns).put("detour", "proxy"))
                .put(JSONObject().put("type", "udp").put("tag", "direct-dns").put("server", "77.88.8.8")))
            if (o.bypassRu) {
                put("rules", JSONArray().put(JSONObject().put("domain_suffix", JSONArray(listOf("ru", "xn--p1ai", "su"))).put("server", "direct-dns")))
            }
            put("final", "remote")
            put("strategy", "ipv4_only")
        }
        return JSONObject().apply {
            // при работе через платформу sing-box по умолчанию выбрасывает журнал — направляем его в stderr движка
            put("log", JSONObject().put("level", o.logLevel).put("timestamp", false).put("output", "stderr"))
            put("dns", dns)
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("type", "tun"); put("tag", "tun")
                put("address", JSONArray(listOf(TUN_V4, TUN_V6)))
                put("mtu", MTU)
                put("auto_route", true)
                put("stack", "gvisor")
            }))
            put("outbounds", JSONArray().put(proxy).put(JSONObject().put("type", "direct").put("tag", "direct")))
            put("route", route)
        }.toString(2)
    }
}
