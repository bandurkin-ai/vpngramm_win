package app.hy2setup

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Итог чтения работающего сервера: профиль и пояснения для журнала. */
data class ImportResult(val profile: StableProfile, val notes: List<String>, val found: Int)

/** Превращение уже работающего сервера в постоянный профиль и хранение списка профилей. */
object ProfileImport {

    /** Делит fqdn на зону и поддомен: по зоне из «Аккаунтов», если подходит, иначе — последние две метки. */
    fun split(fqdn: String, knownZone: String): Pair<String, String> {
        val f = fqdn.trim().trimEnd('.').lowercase()
        val z = knownZone.trim().trimEnd('.').lowercase()
        if (z.isNotEmpty()) {
            if (f == z) return z to ""
            if (f.endsWith(".$z")) return z to f.removeSuffix(".$z")
        }
        val labels = f.split('.')
        if (labels.size <= 2) return f to ""
        return labels.takeLast(2).joinToString(".") to labels.dropLast(2).joinToString(".")
    }

    /**
     * Собирает профиль из маркеров [Scripts.exportProfile]. Ссылки в профиле всегда указывают на [fqdn];
     * протокол, который не удалось прочитать целиком, выключается (и об этом пишется в [ImportResult.notes]).
     */
    fun fromServer(lines: List<String>, fqdn: String, knownZone: String, vps: ProfileVps?): ImportResult {
        val (zone, sub) = split(fqdn, knownZone)
        val base = StableProfile.create(zone, sub, "", "https://www.bing.com/")
        val name = base.fqdn
        val notes = mutableListOf<String>()
        var found = 0
        fun marker(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()

        // Hysteria2
        var p = base.copy(hy2On = false, vlessOn = false, vps = vps)
        marker(Scripts.LINK_MARKER_HY2)?.split("|")?.let { f ->
            val auth = f.getOrNull(1).orEmpty()
            val obfs = f.getOrNull(2).orEmpty()
            if (auth.isEmpty() || obfs.isEmpty()) {
                notes += "Hysteria2: пароль или obfs не найдены — протокол не перенесён."
                return@let
            }
            found++
            p = p.copy(hy2On = true, hy2Port = f.getOrNull(0)?.toIntOrNull() ?: 443, hy2Auth = auth, hy2Obfs = obfs)
            val domain = f.getOrNull(3).orEmpty()
            if (domain != name) {
                notes += "Hysteria2: ссылка указывала на " + (domain.ifEmpty { "IP-адрес" }) + ", теперь будет на $name — " +
                    "её нужно один раз заново раздать (пароли прежние). Сертификат на $name выпустится при переезде."
            }
        }

        // VLESS + Reality: нужен приватный ключ, иначе при переезде ключ не восстановить
        marker(Scripts.LINK_MARKER_VLESS)?.split("|")?.let { f ->
            val uuid = f.getOrNull(1).orEmpty()
            val sni = f.getOrNull(2).orEmpty()
            val pbk = f.getOrNull(3).orEmpty()
            val priv = marker(Scripts.XPRIV_MARKER).orEmpty()
            val pub = runCatching { Reality.publicOf(priv) }.getOrNull()
            when {
                uuid.isEmpty() || pbk.isEmpty() -> notes += "VLESS: настройки неполные — протокол не перенесён."
                pub == null || pub != pbk -> notes += "VLESS: не найден приватный ключ Reality — протокол не перенесён."
                else -> {
                    found++
                    p = p.copy(
                        vlessOn = true, vlessPort = f.getOrNull(0)?.toIntOrNull() ?: 443, vlessUuid = uuid,
                        realityPrivate = priv, realityPublic = pbk, realityShortId = f.getOrNull(4).orEmpty(),
                        realitySni = sni.ifEmpty { name },
                    )
                    val addr = f.getOrNull(5).orEmpty()
                    if (addr.isNotEmpty() && addr != name) notes += "VLESS: адрес в ссылке был $addr, теперь $name — ссылку нужно один раз заново раздать."
                    marker(Scripts.LINK_MARKER_XHTTP)?.takeIf { it.startsWith("/") }?.let { path ->
                        found++
                        p = p.copy(xhttpOn = true, xhttpPath = path)
                    }
                }
            }
        }

        // AmneziaWG: все ключи лежат на сервере в vpngramm.json
        marker(Scripts.LINK_MARKER_AWG)?.let { b64 ->
            val awg = runCatching { AwgParams.fromJson(JSONObject(String(Base64.getDecoder().decode(b64)))) }.getOrNull()
            if (awg == null) {
                notes += "AmneziaWG: настройки не прочитались — протокол не перенесён."
            } else {
                found++
                if (awg.host != name) notes += "AmneziaWG: адрес в ссылке был ${awg.host}, теперь $name — ссылку нужно один раз заново добавить в AmneziaVPN."
                if (awg.extraPeers.isNotEmpty()) notes += "AmneziaWG: на сервере ещё ${awg.extraPeers.size} чел. — перенесены только ключи владельца; остальных добавьте в «Людях» заново."
                p = p.copy(awg = awg.copy(host = name, extraPeers = emptyList()), awgOn = true)
            }
        }

        // Telegram-прокси: секрет и порт; домен маскировки — из самого секрета
        marker(Scripts.TGFULL_MARKER)?.split("|")?.let { f ->
            val port = f.getOrNull(0)?.toIntOrNull() ?: 443
            val secret = f.getOrNull(1).orEmpty()
            val front = TgParams.frontOf(secret)
            if (front == null) {
                notes += "Telegram-прокси: секрет не в формате FakeTLS (ee…) — протокол не перенесён."
            } else {
                found++
                p = p.copy(tg = TgParams(port, secret, front), tgOn = true)
                notes += "Telegram-прокси: ссылка теперь указывает на $name (а не на IP) — её нужно один раз заново раздать."
            }
        }
        if (!p.hy2On && p.vlessOn && p.realitySelf) {
            notes += "VLESS маскируется под $name, а Hysteria2 (она даёт для этого сайт и сертификат) не найдена — VLESS может не работать."
        }
        return ImportResult(p, notes, found)
    }

    /**
     * Сверяет профиль с тем, что реально стоит на его сервере (маркеры [Scripts.exportProfile]).
     * Протокол, найденный на сервере, попадает в профиль с серверными ключами (например, AmneziaWG, поставленный
     * в «Доступе к серверам» (Инструменты)), — его ссылка становится постоянной. Протокол, которого на сервере нет, остаётся в профиле:
     * при переносе он будет установлен. Возвращает новый профиль и строки для журнала.
     */
    fun syncWithServer(profile: StableProfile, lines: List<String>): Pair<StableProfile, List<String>> {
        val srv = fromServer(lines, profile.fqdn, profile.zone, profile.vps).profile
        val name = profile.fqdn
        val notes = mutableListOf<String>()
        var p = profile
        fun has(prefix: String) = lines.any { it.startsWith(prefix) }

        if (srv.hy2On) {
            if (!p.hy2On || p.hy2Auth != srv.hy2Auth || p.hy2Obfs != srv.hy2Obfs || p.hy2Port != srv.hy2Port) {
                notes += if (p.hy2On) "Hysteria2: на сервере другие пароли или порт — ссылка обновлена по серверу." else "Hysteria2: найдена на сервере — ссылка добавлена."
                p = p.copy(hy2On = true, hy2Auth = srv.hy2Auth, hy2Obfs = srv.hy2Obfs, hy2Port = srv.hy2Port)
            }
        } else if (p.hy2On && !has(Scripts.LINK_MARKER_HY2)) {
            notes += "Hysteria2: в профиле есть, а на сервере не найдена — ссылка сейчас не работает; при переносе она будет установлена."
        }

        if (srv.vlessOn) {
            val same = p.vlessOn && p.vlessUuid == srv.vlessUuid && p.realityPrivate == srv.realityPrivate &&
                p.vlessPort == srv.vlessPort && p.realityShortId == srv.realityShortId && p.realitySni == srv.realitySni
            if (!same) {
                notes += if (p.vlessOn) "VLESS: на сервере другие ключи — ссылка обновлена по серверу." else "VLESS: найден на сервере — ссылка добавлена."
                p = p.copy(
                    vlessOn = true, vlessPort = srv.vlessPort, vlessUuid = srv.vlessUuid, realityPrivate = srv.realityPrivate,
                    realityPublic = srv.realityPublic, realityShortId = srv.realityShortId, realitySni = srv.realitySni,
                )
            }
        } else if (p.vlessOn && !has(Scripts.LINK_MARKER_VLESS)) {
            notes += "VLESS: в профиле есть, а на сервере не найден — ссылка сейчас не работает; при переносе он будет установлен."
        } else if (has(Scripts.LINK_MARKER_VLESS) && !p.vlessOn) {
            notes += "VLESS: на сервере есть, но без приватного ключа Reality — добавить в постоянные ссылки нельзя."
        }
        if (srv.vlessOn && srv.xhttpOn) {
            if (!p.xhttpOn || p.xhttpPathEff != srv.xhttpPath) {
                notes += if (p.xhttpOn) "VLESS XHTTP: на сервере другой путь — ссылка обновлена по серверу." else "VLESS XHTTP: найден на сервере — ссылка добавлена."
                p = p.copy(xhttpOn = true, xhttpPath = srv.xhttpPath)
            }
        } else if (p.xhttpActive && srv.vlessOn) {
            notes += "VLESS XHTTP: в профиле есть, а на сервере не найден — ссылка сейчас не работает; «Обновить сервер» или перенос его поставят."
        }

        // на сервере AmneziaWG хранит и остальных людей — в профиле владельца только его ключи
        val sa = srv.awg?.copy(extraPeers = emptyList())
        if (srv.awgOn && sa != null) {
            val cur = p.awg
            val same = p.awgOn && cur != null && cur.copy(host = name) == sa.copy(host = name)
            if (!same) {
                notes += if (p.awgOn) "AmneziaWG: на сервере другие ключи — ссылка обновлена по серверу (добавьте её в AmneziaVPN заново)."
                else "AmneziaWG: найден на сервере — ссылка добавлена (адрес в ней — $name)."
                p = p.copy(awg = sa.copy(host = name), awgOn = true)
            }
        } else if (p.awgOn && !has(Scripts.LINK_MARKER_AWG)) {
            notes += "AmneziaWG: в профиле есть, а на сервере не найден — ссылка сейчас не работает; при переносе он будет установлен."
        }

        val st = srv.tg
        if (srv.tgOn && st != null) {
            if (!p.tgOn || p.tg != st) {
                notes += if (p.tgOn) "Telegram-прокси: на сервере другой секрет или порт — ссылка обновлена по серверу." else "Telegram-прокси: найден на сервере — ссылка добавлена."
                p = p.copy(tg = st, tgOn = true)
            }
        } else if (p.tgOn && !has(Scripts.TGFULL_MARKER)) {
            notes += "Telegram-прокси: в профиле есть, а на сервере не найден — ссылка сейчас не работает; при переносе он будет установлен."
        }
        return p to notes
    }

    /** Какие протоколы стоят на сервере — по маркерам [Scripts.exportProfile] (только поставленные этим приложением). */
    fun installedModes(lines: List<String>): Set<ServerMode> {
        fun has(prefix: String) = lines.any { it.startsWith(prefix) }
        val xhttp = lines.any { it.startsWith(Scripts.LINK_MARKER_XHTTP) && it.removePrefix(Scripts.LINK_MARKER_XHTTP).trim().startsWith("/") }
        return buildSet {
            if (has(Scripts.LINK_MARKER_HY2)) add(ServerMode.HY2)
            if (has(Scripts.LINK_MARKER_VLESS)) { add(ServerMode.VLESS); if (xhttp) add(ServerMode.XHTTP) }
            if (has(Scripts.LINK_MARKER_AWG)) add(ServerMode.AWG)
            if (has(Scripts.TGFULL_MARKER) || has(Scripts.LINK_MARKER_TG)) add(ServerMode.TG)
        }
    }

    /** Копия: массив серверов (2.0) или один профиль (до 2.0). */
    fun fromBackup(plain: String): List<StableProfile> {
        val t = plain.trim()
        return if (t.startsWith("[")) decodeList(t) else listOf(StableProfile.fromJson(t))
    }

    /** Отпечаток серверов без меняющихся сами по себе полей (проверки, трафик и его лимит). */
    fun backupFingerprint(list: List<StableProfile>): String {
        val stable = list.map { it.copy(checkAt = 0, status = emptyMap(), trafficGb = null, vps = it.vps?.copy(trafficLimitGb = null)) }
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(encodeList(stable).toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun encodeList(list: List<StableProfile>): String = JSONArray().apply { list.forEach { put(JSONObject(it.toJson())) } }.toString()

    fun decodeList(json: String?): List<StableProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { runCatching { StableProfile.fromJson(arr.getJSONObject(it).toString()) }.getOrNull() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Список профилей; если его ещё нет — единственный профиль из версии 1.6 и раньше ([old]). */
    fun load(listJson: String, old: String): List<StableProfile> {
        val list = decodeList(listJson)
        if (list.isNotEmpty() || old.isEmpty()) return list
        return listOfNotNull(runCatching { StableProfile.fromJson(old) }.getOrNull())
    }

    /** A/AAAA-записи ровно этого имени (не корень зоны и не соседние поддомены) — для удаления вместе с сервером. */
    fun recordsToDelete(records: List<DnsRecord>, fqdn: String): List<DnsRecord> {
        val name = fqdn.trimEnd('.').lowercase()
        return records.filter { (it.type == "A" || it.type == "AAAA") && it.host.isNotEmpty() && it.fqdn.trimEnd('.').lowercase() == name }
    }

    /** Добавляет профиль или заменяет профиль с тем же именем (порядок сохраняется, новый — в конец). */
    fun upsert(list: List<StableProfile>, p: StableProfile): List<StableProfile> {
        val i = list.indexOfFirst { it.fqdn == p.fqdn }
        return if (i < 0) list + p else list.toMutableList().also { it[i] = p }
    }
}
