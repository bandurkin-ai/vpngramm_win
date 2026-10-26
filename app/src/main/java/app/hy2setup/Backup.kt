package app.hy2setup

import org.json.JSONArray
import org.json.JSONObject

/** Сохранённый сервер вкладки «Сервер» вместе с его паролем и ключом. */
data class SavedServer(val profile: ServerProfile, val password: String, val privateKey: String, val passphrase: String)

/**
 * Настройки доступа для копии: вкладка «Сервер», имя (домен), ключи OneProvider и name.com.
 * Всё это — секреты: без пароля копии они лежат в файле открытым текстом.
 */
data class BackupSettings(
    val host: String = "", val sshPort: String = "22", val user: String = "root", val useKey: Boolean = false,
    val password: String = "", val privateKey: String = "", val passphrase: String = "",
    val zone: String = "", val subdomain: String = "", val ttl: String = "300", val email: String = "", val masquerade: String = "",
    val ncUser: String = "", val ncToken: String = "",
    val opApiKey: String = "", val opClientKey: String = "",
    val servers: List<SavedServer> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("host", host); put("sshPort", sshPort); put("user", user); put("useKey", useKey)
        put("password", password); put("privateKey", privateKey); put("passphrase", passphrase)
        put("zone", zone); put("subdomain", subdomain); put("ttl", ttl); put("email", email); put("masquerade", masquerade)
        put("ncUser", ncUser); put("ncToken", ncToken); put("opApiKey", opApiKey); put("opClientKey", opClientKey)
        put("servers", JSONArray().apply {
            servers.forEach {
                put(JSONObject().put("host", it.profile.host).put("port", it.profile.port).put("user", it.profile.user)
                    .put("useKey", it.profile.useKey).put("password", it.password).put("privateKey", it.privateKey).put("passphrase", it.passphrase))
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject) = BackupSettings(
            host = o.optString("host"), sshPort = o.optString("sshPort", "22"), user = o.optString("user", "root"),
            useKey = o.optBoolean("useKey"), password = o.optString("password"), privateKey = o.optString("privateKey"),
            passphrase = o.optString("passphrase"), zone = o.optString("zone"), subdomain = o.optString("subdomain"),
            ttl = o.optString("ttl", "300"), email = o.optString("email"), masquerade = o.optString("masquerade"),
            ncUser = o.optString("ncUser"), ncToken = o.optString("ncToken"),
            opApiKey = o.optString("opApiKey"), opClientKey = o.optString("opClientKey"),
            servers = o.optJSONArray("servers")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val s = a.optJSONObject(i) ?: return@mapNotNull null
                    SavedServer(
                        ServerProfile(s.getString("host"), s.optInt("port", 22), s.optString("user", "root"), s.optBoolean("useKey")),
                        s.optString("password"), s.optString("privateKey"), s.optString("passphrase"),
                    )
                }
            }.orEmpty(),
        )
    }
}

/** Содержимое файла копии. [settings] = null — копия старого формата (только серверы). */
data class BackupData(val servers: List<StableProfile>, val settings: BackupSettings?)

/**
 * Файл копии: «VPNGRAMM-PLAIN:» + JSON (без пароля) или «HY2B1:…» (зашифрован паролем, [BackupCrypto]).
 * Внутри — {"v":2,"servers":[…],"settings":{…}}; понимает и старые форматы: один профиль и массив серверов.
 */
object Backup {
    const val PLAIN = "VPNGRAMM-PLAIN:"

    fun toJson(d: BackupData): String = JSONObject().apply {
        put("v", 2)
        put("servers", JSONArray(ProfileImport.encodeList(d.servers)))
        d.settings?.let { put("settings", it.toJson()) }
    }.toString()

    /** Текст файла: без пароля (пусто) — открытый JSON, иначе — шифр. */
    fun encode(d: BackupData, password: String): String =
        if (password.isEmpty()) PLAIN + toJson(d) else BackupCrypto.encrypt(toJson(d), password)

    fun isEncrypted(text: String): Boolean = !text.trimStart().startsWith(PLAIN)

    /** Разбор файла; для зашифрованного нужен [password] (иначе — [BackupCrypto.WrongPassword]). */
    fun decode(text: String, password: String = ""): BackupData {
        val t = text.trim()
        val plain = if (t.startsWith(PLAIN)) t.removePrefix(PLAIN) else BackupCrypto.decrypt(t, password)
        return parse(plain)
    }

    fun parse(plain: String): BackupData {
        val t = plain.trim()
        if (t.startsWith("[")) return BackupData(ProfileImport.decodeList(t), null)
        val o = JSONObject(t)
        if (!o.has("servers")) return BackupData(listOf(StableProfile.fromJson(t)), null)
        return BackupData(
            ProfileImport.decodeList(o.getJSONArray("servers").toString()),
            o.optJSONObject("settings")?.let { BackupSettings.fromJson(it) },
        )
    }

    /** Отпечаток копии без меняющихся сами по себе полей (проверки, трафик) — для напоминания «копия устарела». */
    fun fingerprint(d: BackupData): String {
        val stable = d.copy(servers = d.servers.map { it.copy(checkAt = 0, status = emptyMap(), trafficGb = null, vps = it.vps?.copy(trafficLimitGb = null)) })
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(toJson(stable).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Отпечаток одного сервера для напоминания «копия устарела»: ключи, пароли и люди — всё, без чего не
     * восстановить ссылки. Проверки, трафик, переезд на другой VPS и продление сертификата его не меняют.
     */
    fun serverFingerprint(p: StableProfile): String {
        val stable = p.copy(checkAt = 0, status = emptyMap(), trafficGb = null, vps = null, movedAt = 0, cert = null)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(stable.toJson().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Отпечатки серверов из последних сохранённых (или прочитанных) копий: домен → отпечаток. */
    fun decodeSaved(json: String?): Map<String, String> = runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun encodeSaved(m: Map<String, String>): String = JSONObject(m).toString()
}
