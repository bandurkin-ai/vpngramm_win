package app.hy2setup

import java.net.URLEncoder

/** Готовая ссылка для клиента. */
data class ShareLink(val title: String, val uri: String)

/** Сборка ссылок hysteria2:// и vless:// и разбор вывода скрипта exportLinks на сервере. */
object Links {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun hostPart(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    fun hy2(host: String, port: Int, auth: String, obfs: String, domain: String, sni: String, pin: String?, user: String? = null): String {
        val addr = domain.ifEmpty { host }
        val q = StringBuilder("obfs=salamander&obfs-password=${enc(obfs)}")
        if (domain.isNotEmpty()) {
            q.append("&sni=$domain")
        } else {
            q.append("&sni=${sni.ifEmpty { "www.bing.com" }}&insecure=1")
            if (!pin.isNullOrEmpty()) q.append("&pinSHA256=$pin")
        }
        // userpass (несколько людей): hysteria2://имя:пароль@…
        val cred = if (user != null) "${enc(user)}:${enc(auth)}" else enc(auth)
        return "hysteria2://$cred@${hostPart(addr)}:$port/?$q#${enc("Hy2-$addr")}"
    }

    fun vless(host: String, port: Int, uuid: String, sni: String, pbk: String, sid: String): String =
        "vless://$uuid@${hostPart(host)}:$port" +
            "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=$sni&fp=chrome" +
            "&pbk=$pbk&sid=$sid&type=tcp#${enc("VLESS-$host")}"

    /** Ссылка на MTProto-прокси: открывается в Telegram и из браузера/камеры. */
    fun telegram(host: String, port: Int, secret: String): String =
        "https://t.me/proxy?server=${host.removePrefix("[").removeSuffix("]")}&port=$port&secret=$secret"

    /** Разбирает маркер Telegram-прокси (##TG|port|secret). */
    fun telegramFromMarkers(lines: List<String>, host: String): ShareLink? {
        val f = lines.firstOrNull { it.startsWith(Scripts.LINK_MARKER_TG) }
            ?.removePrefix(Scripts.LINK_MARKER_TG)?.split("|") ?: return null
        val port = f.getOrNull(0)?.toIntOrNull() ?: return null
        val secret = f.getOrNull(1).orEmpty()
        if (!Regex("^ee[0-9a-f]{32,}$").matches(secret)) return null
        return ShareLink("Telegram-прокси (MTProto)", telegram(host, port, secret))
    }

    /** Разбирает строки-маркеры, напечатанные Scripts.exportLinks(). */
    fun fromExport(lines: List<String>, sshHost: String): List<ShareLink> {
        val out = mutableListOf<ShareLink>()
        for (line in lines) {
            when {
                line.startsWith(Scripts.LINK_MARKER_HY2) -> {
                    val f = line.removePrefix(Scripts.LINK_MARKER_HY2).split("|")
                    if (f.size < 6 || f[1].isEmpty() || f[2].isEmpty()) continue
                    val port = f[0].toIntOrNull() ?: 443
                    out += ShareLink(
                        Protocol.HY2.title,
                        hy2(sshHost, port, auth = f[1], obfs = f[2], domain = f[3], sni = f[4], pin = f[5]),
                    )
                }
                line.startsWith(Scripts.LINK_MARKER_VLESS) -> {
                    val f = line.removePrefix(Scripts.LINK_MARKER_VLESS).split("|")
                    if (f.size < 6 || f[1].isEmpty() || f[3].isEmpty()) continue
                    val port = f[0].toIntOrNull() ?: 443
                    val addr = f.getOrNull(5).orEmpty().ifEmpty { sshHost }
                    out += ShareLink(Protocol.VLESS.title, vless(addr, port, uuid = f[1], sni = f[2], pbk = f[3], sid = f[4]))
                }
                line.startsWith(Scripts.LINK_MARKER_AWG) -> {
                    val p = runCatching {
                        AwgParams.fromJson(org.json.JSONObject(String(java.util.Base64.getDecoder().decode(line.removePrefix(Scripts.LINK_MARKER_AWG).trim()))))
                    }.getOrNull() ?: continue
                    out += ShareLink(Awg.TITLE, p.vpnLink(p.host))
                }
            }
        }
        telegramFromMarkers(lines, sshHost)?.let { out += it }
        return out
    }

    /** Строки-маркеры не показываем в журнале. */
    fun isMarker(line: String) = line.startsWith("##")
}
