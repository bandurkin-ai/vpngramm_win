package app.hy2setup

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * Ещё один человек на сервере с постоянными ссылками: свои ключи VLESS, Hysteria2 и AmneziaWG и своя подписка.
 * Владелец (первый) — это ключи самого профиля, поэтому старые ссылки не меняются.
 */
data class ProfileUser(
    val id: String,
    val name: String,
    val vlessUuid: String,
    val hy2Pass: String,
    val awgPriv: String,
    val awgPsk: String,
    val awgIp: String,
    val subToken: String,
    val disabled: Boolean = false,
) {
    /** Имя в Hysteria2 (режим userpass). */
    val hy2Name: String get() = "u$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("vlessUuid", vlessUuid).put("hy2Pass", hy2Pass)
        .put("awgPriv", awgPriv).put("awgPsk", awgPsk).put("awgIp", awgIp).put("subToken", subToken).put("disabled", disabled)

    companion object {
        private val rnd = SecureRandom()

        fun fromJson(o: JSONObject) = ProfileUser(
            o.getString("id"), o.getString("name"), o.getString("vlessUuid"), o.getString("hy2Pass"),
            o.getString("awgPriv"), o.getString("awgPsk"), o.getString("awgIp"), o.getString("subToken"), o.optBoolean("disabled"),
        )

        fun listFrom(a: JSONArray?): List<ProfileUser> = if (a == null) emptyList() else (0 until a.length()).map { fromJson(a.getJSONObject(it)) }

        /** Случайный токен адреса подписки (буквы и цифры). */
        fun token(len: Int = 24): String {
            val abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            return (1..len).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
        }

        /** Новый человек: свободный номер и свободный адрес в туннеле AmneziaWG (10.66.66.3 и дальше). */
        fun create(name: String, existing: List<ProfileUser>): ProfileUser {
            val id = ((existing.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
            val used = existing.map { it.awgIp }.toSet() + Awg.CLIENT_IP
            val ip = (3..254).map { "10.66.66.$it" }.first { it !in used }
            val hex = ByteArray(18).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return ProfileUser(
                id = id, name = name.trim().ifEmpty { "Человек $id" }, vlessUuid = UUID.randomUUID().toString(), hy2Pass = hex,
                awgPriv = Awg.genKey(), awgPsk = Awg.genKey(), awgIp = ip, subToken = token(),
            )
        }
    }
}
