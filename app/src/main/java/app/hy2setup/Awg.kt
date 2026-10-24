package app.hy2setup

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater

/**
 * AmneziaWG 3.1: ключи и параметры маскировки сервера и одного клиента. Всё генерируется на телефоне
 * и хранится в профиле, поэтому ссылка vpn:// не меняется при переезде на новый VPS.
 * [host] — адрес в ссылке (домен профиля или IP сервера).
 */
data class AwgParams(
    val host: String,
    val port: Int,
    val serverPriv: String,
    val clientPriv: String,
    val psk: String,
    val headerKey: String,
    val jc: Int,
    val jmin: Int,
    val jmax: Int,
    val s1: Int,
    val s2: Int,
    val s3: Int,
    val s4: Int,
    /** RandomTrailers: дописывать пакеты до MTU случайными байтами. Обязан совпадать у сервера и клиента. */
    val trailers: Boolean = false,
    /** Остальные люди: свой ключ, PSK и адрес в туннеле. Первый клиент — [clientPriv]/[psk] на 10.66.66.2. */
    val extraPeers: List<AwgPeer> = emptyList(),
) {
    private val trailersValue: String get() = if (trailers) "on" else "off"

    val serverPub: String get() = Awg.publicOf(serverPriv)
    val clientPub: String get() = Awg.publicOf(clientPriv)

    /** Параметры маскировки, общие для сервера и клиента (S, H и ключ защиты заголовков обязаны совпадать). */
    private fun obfuscation(): String = """
        Jc = $jc
        Jmin = $jmin
        Jmax = $jmax
        S1 = $s1
        S2 = $s2
        S3 = $s3
        S4 = $s4
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4
        I1 = ${Awg.I1}
        HeaderProtectionKey = $headerKey
        ContentPaddingAddition = ${Awg.PADDING}
        RandomTrailers = $trailersValue
    """.trimIndent()

    /** Конфигурация сервера; NIC подставляет скрипт установки (__NIC__). */
    fun serverConf(): String = "[Interface]\n" + """
        PrivateKey = $serverPriv
        Address = ${Awg.SERVER_IP}/24
        ListenPort = $port
        MTU = ${Awg.MTU}
        PostUp = iptables -I FORWARD -i %i -j ACCEPT; iptables -I FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o __NIC__ -j MASQUERADE; iptables -t mangle -A FORWARD -o %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss ${Awg.MTU - 40}; iptables -t mangle -A FORWARD -i %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss ${Awg.MTU - 40}
        PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o __NIC__ -j MASQUERADE; iptables -t mangle -D FORWARD -o %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss ${Awg.MTU - 40}; iptables -t mangle -D FORWARD -i %i -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss ${Awg.MTU - 40}
    """.trimIndent() + "\n" + obfuscation() + "\n\n[Peer]\n" + """
        PublicKey = $clientPub
        PresharedKey = $psk
        AllowedIPs = ${Awg.CLIENT_IP}/32
    """.trimIndent() + "\n" + extraPeers.joinToString("") { "\n[Peer]\nPublicKey = ${it.pub}\nPresharedKey = ${it.psk}\nAllowedIPs = ${it.ip}/32\n" }

    /** Конфигурация клиента (.conf) — её же кладём в ссылку vpn://. */
    fun clientConf(peer: AwgPeer? = null): String = "[Interface]\n" + """
        PrivateKey = ${peer?.priv ?: clientPriv}
        Address = ${peer?.ip ?: Awg.CLIENT_IP}/32
        DNS = ${Awg.DNS1}, ${Awg.DNS2}
        MTU = ${Awg.MTU}
    """.trimIndent() + "\n" + obfuscation() + "\n\n[Peer]\n" + """
        PublicKey = $serverPub
        PresharedKey = ${peer?.psk ?: psk}
        Endpoint = $host:$port
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent() + "\n"

    /**
     * Ссылка для AmneziaVPN 5.x: «vpn://» + base64url(qCompress(JSON)), где qCompress — 4 байта длины (big-endian)
     * и поток zlib. Внутри — контейнер amnezia-awg со «сторонней» конфигурацией (приложение не управляет сервером).
     */
    fun vpnLink(description: String, peer: AwgPeer? = null): String {
        val last = JSONObject().apply {
            put("H1", "1"); put("H2", "2"); put("H3", "3"); put("H4", "4")
            put("Jc", "$jc"); put("Jmin", "$jmin"); put("Jmax", "$jmax")
            put("S1", "$s1"); put("S2", "$s2"); put("S3", "$s3"); put("S4", "$s4")
            put("I1", Awg.I1); put("I2", ""); put("I3", ""); put("I4", ""); put("I5", "")
            put("HeaderProtectionKey", headerKey)
            put("ContentPaddingAddition", Awg.PADDING)
            put("RandomTrailers", trailersValue)
            put("allowed_ips", JSONArray(listOf("0.0.0.0/0", "::/0")))
            put("client_ip", peer?.ip ?: Awg.CLIENT_IP)
            put("client_ipv6", "")
            put("client_priv_key", peer?.priv ?: clientPriv)
            put("client_pub_key", peer?.pub ?: clientPub)
            put("psk_key", peer?.psk ?: psk)
            put("config", clientConf(peer))
            put("hostName", host)
            put("mtu", "${Awg.MTU}")
            put("persistent_keep_alive", "25")
            put("port", port)
            put("server_pub_key", serverPub)
        }
        val outer = JSONObject().apply {
            put("containers", JSONArray().put(JSONObject().apply {
                put("container", "amnezia-awg")
                put("awg", JSONObject().apply {
                    put("isThirdPartyConfig", true)
                    put("port", "$port")
                    put("protocol_version", "2")
                    put("transport_proto", "udp")
                    put("last_config", last.toString())
                })
            }))
            put("defaultContainer", "amnezia-awg")
            put("description", description)
            put("dns1", Awg.DNS1)
            put("dns2", Awg.DNS2)
            put("hostName", host)
        }
        return "vpn://" + Awg.qCompressB64(outer.toString())
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("host", host); put("port", port)
        put("serverPriv", serverPriv); put("clientPriv", clientPriv); put("psk", psk); put("headerKey", headerKey)
        put("jc", jc); put("jmin", jmin); put("jmax", jmax)
        put("s1", s1); put("s2", s2); put("s3", s3); put("s4", s4)
        put("trailers", trailers)
        if (extraPeers.isNotEmpty()) put("peers", JSONArray().apply { extraPeers.forEach { put(JSONObject().put("priv", it.priv).put("psk", it.psk).put("ip", it.ip)) } })
    }

    companion object {
        fun fromJson(o: JSONObject) = AwgParams(
            host = o.getString("host"), port = o.getInt("port"),
            serverPriv = o.getString("serverPriv"), clientPriv = o.getString("clientPriv"),
            psk = o.getString("psk"), headerKey = o.getString("headerKey"),
            jc = o.getInt("jc"), jmin = o.getInt("jmin"), jmax = o.getInt("jmax"),
            s1 = o.getInt("s1"), s2 = o.getInt("s2"), s3 = o.getInt("s3"), s4 = o.getInt("s4"),
            trailers = o.optBoolean("trailers", false),
            extraPeers = o.optJSONArray("peers")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { j -> AwgPeer(j.getString("priv"), j.getString("psk"), j.getString("ip")) } }
            }.orEmpty(),
        )
    }
}

/** Ещё один человек в AmneziaWG: свой ключ, PSK и адрес в туннеле. */
data class AwgPeer(val priv: String, val psk: String, val ip: String) {
    val pub: String get() = Awg.publicOf(priv)
}

object Awg {
    const val TITLE = "AmneziaWG"
    const val MTU = 1280
    const val SERVER_IP = "10.66.66.1"
    const val CLIENT_IP = "10.66.66.2"
    const val DNS1 = "1.1.1.1"
    const val DNS2 = "1.0.0.1"
    const val PADDING = "10-100"

    /** Первый пакет имитирует ответ DNS (значение по умолчанию из AmneziaVPN). */
    const val I1 = "<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>"

    private val rnd = SecureRandom()
    private val b64 = Base64.getEncoder()

    /** Приватный ключ X25519 в формате WireGuard (base64 с «=»), как `awg genkey`. */
    fun genKey(): String {
        val raw = ByteArray(32).also { rnd.nextBytes(it) }
        raw[0] = (raw[0].toInt() and 248).toByte()
        raw[31] = ((raw[31].toInt() and 127) or 64).toByte()
        return b64.encodeToString(raw)
    }

    fun publicOf(priv: String): String =
        b64.encodeToString(X25519PrivateKeyParameters(Base64.getDecoder().decode(priv), 0).generatePublicKey().encoded)

    private fun rand(from: Int, to: Int) = from + rnd.nextInt(to - from + 1)

    /** Порт UDP: случайный из «тихого» диапазона, не 443 (там Hysteria2). */
    fun randomPort(): Int = rand(20000, 60000)

    /**
     * Новые параметры. Ограничения ядра и AmneziaWG: с ключом защиты заголовков все S ≥ 12;
     * S1 + 56 ≠ S2; S3 ≠ S2 + 28; S4 ≤ 32; Jmin ≤ Jmax ≤ 1280.
     */
    fun generate(host: String, port: Int = randomPort(), trailers: Boolean = false): AwgParams {
        var s1: Int; var s2: Int
        do { s1 = rand(15, 150); s2 = rand(15, 150) } while (s1 + 56 == s2)
        var s3: Int
        do { s3 = rand(12, 55) } while (s3 == s2 + 28)
        val jmin = rand(40, 89)
        return AwgParams(
            host = host, port = port,
            serverPriv = genKey(), clientPriv = genKey(), psk = genKey(), headerKey = genKey(),
            jc = rand(3, 6), jmin = jmin, jmax = jmin + rand(50, 250),
            s1 = s1, s2 = s2, s3 = s3, s4 = rand(12, 27), trailers = trailers,
        )
    }

    /** Qt qCompress (4 байта длины + zlib) → base64url без «=». */
    fun qCompressB64(text: String): String {
        val data = text.toByteArray(Charsets.UTF_8)
        val d = Deflater(9)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
        val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }
}
