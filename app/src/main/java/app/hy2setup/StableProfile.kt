package app.hy2setup

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID

/** Копия сертификата Let's Encrypt из хранилища certmagic на сервере (PEM и метаданные). */
data class CertBackup(val crt: String, val key: String, val meta: String, val notAfter: Long) {
    /** Сколько полных дней сертификат ещё действует (может быть отрицательным). */
    fun daysLeft(now: Long = System.currentTimeMillis()): Long = (notAfter - now) / 86_400_000L

    companion object {
        /** Срок действия первого сертификата в PEM (мс), или null, если это не сертификат. */
        fun notAfterOf(pem: String): Long? = try {
            val cf = CertificateFactory.getInstance("X.509")
            (cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate).notAfter.time
        } catch (e: Exception) {
            null
        }
    }
}

/** Telegram-прокси профиля: секрет создаётся один раз, поэтому ссылка не меняется при переезде. */
data class TgParams(val port: Int, val secret: String, val front: String) {
    fun link(host: String) = ShareLink(TITLE, Links.telegram(host, port, secret))

    companion object {
        const val TITLE = "Telegram-прокси (MTProto)"
        private val rnd = SecureRandom()

        /** Секрет FakeTLS в hex-виде, как у `mtg generate-secret --hex`: «ee» + 16 случайных байт + домен. */
        fun generate(port: Int, front: String): TgParams {
            val key = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return TgParams(port, "ee" + key + front.toByteArray().joinToString("") { "%02x".format(it) }, front)
        }

        /** Домен маскировки из секрета ee…: после 32 hex-символов ключа идёт hex имени. */
        fun frontOf(secret: String): String? {
            if (!Regex("^ee[0-9a-f]{34,}$").matches(secret) || secret.length % 2 != 0) return null
            val hex = secret.substring(34)
            return runCatching { String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }.getOrNull()
                ?.takeIf { Regex("^[A-Za-z0-9.-]+$").matches(it) }
        }
    }
}

/** VPS, на котором сейчас работает профиль. */
/** VPS профиля; [trafficLimitGb] — лимит трафика тарифа у OneProvider (ГБ в месяц), если известен. */
data class ProfileVps(val id: String, val ip: String, val hostname: String, val trafficLimitGb: Double? = null)

/**
 * Постоянный профиль «Комбайна+»: всё, что входит в ссылки. Пока профиль один и тот же,
 * ссылки не меняются — на новый VPS ставятся те же пароли и ключи, а A-запись переводится на новый IP.
 */
data class StableProfile(
    val zone: String,
    val subdomain: String,
    val hy2Port: Int = 443,
    val hy2Auth: String,
    val hy2Obfs: String,
    val masquerade: String = "https://www.bing.com/",
    val email: String = "",
    val vlessPort: Int = 443,
    val vlessUuid: String,
    val realityPrivate: String,
    val realityPublic: String,
    val realityShortId: String,
    val realitySni: String = "www.microsoft.com",
    val cert: CertBackup? = null,
    val vps: ProfileVps? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** AmneziaWG 3.1: ключи и параметры (создаются один раз), [awgOn] — ставить ли его на сервер. */
    val awg: AwgParams? = null,
    val awgOn: Boolean = false,
    /** Какие протоколы стоят на сервере профиля (у комбайна часть могла быть выключена). */
    val hy2On: Boolean = true,
    val vlessOn: Boolean = true,
    val tg: TgParams? = null,
    val tgOn: Boolean = false,
    /** Остальные люди (владелец — ключи самого профиля). */
    val users: List<ProfileUser> = emptyList(),
    /** Токен адреса подписки владельца: https://домен/s/<токен>. */
    val subToken: String = ProfileUser.token(),
    /** Последняя проверка: когда и что работает (ключи — названия протоколов). */
    val checkAt: Long = 0,
    val status: Map<String, Boolean> = emptyMap(),
    /** Трафик VPS за месяц (ГБ) по vnstat и когда считан. */
    val trafficGb: Double? = null,
    /** Когда сервер последний раз переехал на другой VPS. */
    val movedAt: Long = 0,
    /** VLESS + Reality + XHTTP на том же порту и с теми же ключами, что VLESS. */
    val xhttpOn: Boolean = false,
    /** Путь XHTTP; пусто — постоянный путь, выведенный из UUID (см. [xhttpPathEff]). */
    val xhttpPath: String = "",
) {
    /** Путь XHTTP: заданный или постоянный (из UUID владельца), чтобы ссылка не менялась. */
    val xhttpPathEff: String
        get() = xhttpPath.ifEmpty {
            "/" + java.security.MessageDigest.getInstance("SHA-256").digest("xhttp:$vlessUuid".toByteArray())
                .take(5).joinToString("") { "%02x".format(it) }
        }

    /** XHTTP работает только поверх VLESS. */
    val xhttpActive: Boolean get() = vlessOn && xhttpOn

    val fqdn: String get() = if (subdomain.isEmpty()) zone else "$subdomain.$zone"

    /**
     * Reality «под себя»: VLESS маскируется под свой же домен (он указывает на этот сервер), а чужие подключения
     * получают настоящий сайт с нашим сертификатом от Hysteria2 на 127.0.0.1:8443. ТСПУ не к чему придраться:
     * имя и адрес совпадают. С чужим сайтом (www.microsoft.com) на не-Microsoft IP рукопожатие режут.
     */
    val realitySelf: Boolean get() = realitySni == fqdn

    /** Люди, у которых сейчас есть доступ (кроме владельца). */
    val activeUsers: List<ProfileUser> get() = users.filter { !it.disabled }

    /** Hysteria2 различает людей только в режиме userpass — он включается, когда кроме владельца есть ещё кто-то. */
    val hy2MultiUser: Boolean get() = activeUsers.isNotEmpty()

    /** Пары «имя → пароль» Hysteria2 (пусто — один пароль владельца). */
    fun hy2Users(): Map<String, String> =
        if (!hy2MultiUser) emptyMap() else linkedMapOf(OWNER_HY2 to hy2Auth) + activeUsers.associate { it.hy2Name to it.hy2Pass }

    /** AmneziaWG со всеми включёнными людьми. */
    fun awgEffective(): AwgParams? = awg?.copy(extraPeers = activeUsers.map { AwgPeer(it.awgPriv, it.awgPsk, it.awgIp) })

    /**
     * Где Hysteria2 отдаёт сайт домена (с подпиской) по TCP: для VLESS «под себя» — 127.0.0.1:8443 (туда Reality
     * отправляет чужие подключения), иначе — прямо на :443, если этот порт не занят VLESS или Telegram.
     */
    val siteListen: String?
        get() = when {
            !hy2On -> null
            vlessOn && realitySelf -> LOCAL_HTTPS
            vlessOn && vlessPort == 443 -> null
            tgOn && tg?.port == 443 -> null
            else -> ":443"
        }

    /** Подписка доступна, когда сайт домена виден снаружи по HTTPS. */
    val subAvailable: Boolean get() = siteListen != null

    fun subUrl(user: ProfileUser? = null): String = "https://$fqdn/s/${user?.subToken ?: subToken}"

    /** Параметры установки Hysteria2 из профиля. */
    fun hy2Params(cert: CertBackup?) = InstallParams(
        port = hy2Port, authPassword = hy2Auth, obfsPassword = hy2Obfs,
        domain = fqdn, email = email, sni = fqdn, masqueradeUrl = masquerade,
        acmeHttp = true, cert = cert, localHttps = realitySelf,
        users = hy2Users(), siteDir = if (siteListen != null) SITE_DIR else "", siteListen = siteListen.orEmpty(),
    )

    /** Параметры установки VLESS + Reality из профиля. */
    fun vlessParams() = VlessParams(
        port = vlessPort, uuid = vlessUuid, sni = realitySni, shortId = realityShortId, address = fqdn,
        privateKey = realityPrivate, publicKey = realityPublic,
        dest = if (realitySelf) LOCAL_HTTPS else "",
        extraUuids = activeUsers.map { it.vlessUuid },
        xhttpPath = if (xhttpOn) xhttpPathEff else "",
    )

    /** Ссылки владельца; всегда собираются только из профиля, поэтому совпадают побайтно при любом VPS. */
    fun links(): List<ShareLink> = linksFor(null)

    /** Ссылки человека ([user] = null — владелец). */
    fun linksFor(user: ProfileUser?): List<ShareLink> {
        val uuid = user?.vlessUuid ?: vlessUuid
        val hy = if (!hy2MultiUser) Links.hy2(fqdn, hy2Port, hy2Auth, hy2Obfs, fqdn, fqdn, null)
        else Links.hy2(fqdn, hy2Port, user?.hy2Pass ?: hy2Auth, hy2Obfs, fqdn, fqdn, null, user?.hy2Name ?: OWNER_HY2)
        val peer = user?.let { AwgPeer(it.awgPriv, it.awgPsk, it.awgIp) }
        return listOfNotNull(
            // VLESS первым: работает по TCP, а UDP (Hysteria2) мобильные операторы часто режут
            ShareLink(Protocol.VLESS.title, Links.vless(fqdn, vlessPort, uuid, realitySni, realityPublic, realityShortId)).takeIf { vlessOn },
            ShareLink(Links.XHTTP_TITLE, Links.vlessXhttp(fqdn, vlessPort, uuid, realitySni, realityPublic, realityShortId, xhttpPathEff))
                .takeIf { xhttpActive },
            ShareLink(Protocol.HY2.title, hy).takeIf { hy2On },
            awg?.takeIf { awgOn }?.let { ShareLink(Awg.TITLE, it.vpnLink(fqdn + (user?.let { u -> " · ${u.name}" } ?: ""), peer)) },
            tg?.takeIf { tgOn }?.link(fqdn),
        )
    }

    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        put("zone", zone); put("subdomain", subdomain)
        put("hy2Port", hy2Port); put("hy2Auth", hy2Auth); put("hy2Obfs", hy2Obfs)
        put("masquerade", masquerade); put("email", email)
        put("vlessPort", vlessPort); put("vlessUuid", vlessUuid)
        put("realityPrivate", realityPrivate); put("realityPublic", realityPublic)
        put("realityShortId", realityShortId); put("realitySni", realitySni)
        put("createdAt", createdAt)
        cert?.let { put("cert", JSONObject().put("crt", it.crt).put("key", it.key).put("meta", it.meta).put("notAfter", it.notAfter)) }
        vps?.let { v ->
            put("vps", JSONObject().put("id", v.id).put("ip", v.ip).put("hostname", v.hostname).also { j -> v.trafficLimitGb?.let { j.put("limitGb", it) } })
        }
        awg?.let { put("awg", it.toJson()) }
        put("awgOn", awgOn)
        put("hy2On", hy2On); put("vlessOn", vlessOn)
        if (users.isNotEmpty()) put("users", JSONArray().apply { users.forEach { put(it.toJson()) } })
        put("subToken", subToken)
        put("checkAt", checkAt)
        if (status.isNotEmpty()) put("status", JSONObject().apply { status.forEach { (k, v) -> put(k, v) } })
        trafficGb?.let { put("trafficGb", it) }
        put("movedAt", movedAt)
        tg?.let { put("tg", JSONObject().put("port", it.port).put("secret", it.secret).put("front", it.front)) }
        put("tgOn", tgOn)
        // только если XHTTP включали: у старых профилей JSON (и отпечаток копии) не меняется
        if (xhttpOn || xhttpPath.isNotEmpty()) { put("xhttpOn", xhttpOn); put("xhttpPath", xhttpPath) }
    }.toString()

    companion object {
        private val rnd = SecureRandom()

        /** Где Hysteria2 отдаёт сайт-маскировку по TCP для Reality «под себя». */
        const val LOCAL_HTTPS = "127.0.0.1:8443"
        /** Каталог сайта домена (страница и подписки). */
        const val SITE_DIR = "/var/www/vpngramm"
        /** Имя владельца в Hysteria2 (режим userpass). */
        const val OWNER_HY2 = "u0"

        fun fromJson(s: String): StableProfile {
            val o = JSONObject(s)
            val c = o.optJSONObject("cert")
            val v = o.optJSONObject("vps")
            return StableProfile(
                zone = o.getString("zone"), subdomain = o.optString("subdomain"),
                hy2Port = o.optInt("hy2Port", 443), hy2Auth = o.getString("hy2Auth"), hy2Obfs = o.getString("hy2Obfs"),
                masquerade = o.optString("masquerade", "https://www.bing.com/"), email = o.optString("email"),
                vlessPort = o.optInt("vlessPort", 443), vlessUuid = o.getString("vlessUuid"),
                realityPrivate = o.getString("realityPrivate"), realityPublic = o.getString("realityPublic"),
                realityShortId = o.getString("realityShortId"), realitySni = o.optString("realitySni", "www.microsoft.com"),
                cert = c?.let { CertBackup(it.getString("crt"), it.getString("key"), it.optString("meta"), it.getLong("notAfter")) },
                vps = v?.let { ProfileVps(it.getString("id"), it.optString("ip"), it.optString("hostname"), if (it.has("limitGb")) it.getDouble("limitGb") else null) },
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                awg = o.optJSONObject("awg")?.let { AwgParams.fromJson(it) },
                awgOn = o.optBoolean("awgOn", false),
                hy2On = o.optBoolean("hy2On", true), vlessOn = o.optBoolean("vlessOn", true),
                tg = o.optJSONObject("tg")?.let { TgParams(it.getInt("port"), it.getString("secret"), it.optString("front")) },
                tgOn = o.optBoolean("tgOn", false),
                users = ProfileUser.listFrom(o.optJSONArray("users")),
                subToken = o.optString("subToken").ifEmpty { ProfileUser.token() },
                checkAt = o.optLong("checkAt", 0),
                status = o.optJSONObject("status")?.let { j -> j.keys().asSequence().associateWith { j.getBoolean(it) } }.orEmpty(),
                trafficGb = if (o.has("trafficGb")) o.getDouble("trafficGb") else null,
                movedAt = o.optLong("movedAt", 0),
                xhttpOn = o.optBoolean("xhttpOn", false), xhttpPath = o.optString("xhttpPath"),
            )
        }

        private fun hex(bytes: Int) = ByteArray(bytes).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Новый профиль: случайные пароли, UUID и ключи Reality. */
        fun create(zone: String, subdomain: String, email: String, masquerade: String): StableProfile {
            val (priv, pub) = Reality.generate()
            val fqdn = if (subdomain.isEmpty()) zone else "$subdomain.$zone"
            return StableProfile(
                zone = zone, subdomain = subdomain, email = email, masquerade = masquerade,
                hy2Auth = hex(18), hy2Obfs = hex(18),
                vlessUuid = UUID.randomUUID().toString(),
                realityPrivate = priv, realityPublic = pub, realityShortId = hex(8),
                realitySni = fqdn,
            )
        }
    }
}

/** Ключи Reality (X25519) в том же виде, что печатает `xray x25519`: base64url без «=». */
object Reality {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun generate(): Pair<String, String> {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // как в xray: «зажатие» ключа по RFC 7748
        raw[0] = (raw[0].toInt() and 248).toByte()
        raw[31] = ((raw[31].toInt() and 127) or 64).toByte()
        return enc.encodeToString(raw) to publicOf(enc.encodeToString(raw))
    }

    fun publicOf(privateKey: String): String {
        val priv = X25519PrivateKeyParameters(dec.decode(privateKey), 0)
        return enc.encodeToString(priv.generatePublicKey().encoded)
    }
}

/**
 * VLESS «под свой домен» в «Доступе к серверам» (Инструменты): Hysteria2 получает сертификат на [domain] и отдаёт сайт
 * на [StableProfile.LOCAL_HTTPS], а Reality маскируется под этот же домен и отправляет туда чужие подключения.
 */
object SelfMask {
    fun hy2(domain: String, port: Int, auth: String, obfs: String, email: String, masquerade: String) =
        InstallParams(
            port = port, authPassword = auth, obfsPassword = obfs, domain = domain, email = email, sni = domain,
            masqueradeUrl = masquerade, acmeHttp = true, localHttps = true,
        )

    fun vless(domain: String, port: Int, uuid: String, shortId: String) = VlessParams(
        port = port, uuid = uuid, sni = domain, shortId = shortId, address = domain, dest = StableProfile.LOCAL_HTTPS,
    )
}
