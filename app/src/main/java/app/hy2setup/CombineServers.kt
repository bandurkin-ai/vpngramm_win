package app.hy2setup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Свободный сервер: прежний сервер профиля, оставленный при переезде, VPS, оставленный после отката, или любой сервер,
 * добавленный вручную по IP. На него можно перевести постоянные ссылки без покупки нового VPS. Пароль и ключ лежат
 * в SecretStore под записью «пользователь@ip:порт» списка сохранённых серверов («Доступ по SSH»).
 */
data class CombineServer(
    val vpsId: String,
    val ip: String,
    val fqdn: String,
    val hostname: String = "",
    val location: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** Что стоит на сервере (названия протоколов) — только для подписи в списке. */
    val protocols: List<String> = emptyList(),
    /** Включён вход только по ключу. */
    val secured: Boolean = false,
    /** «combine» — сделан комбайном, «profile» — прежний сервер постоянных ссылок, «manual» — добавлен вручную по IP. */
    val origin: String = ORIGIN_COMBINE,
) {
    val title: String get() = fqdn.ifEmpty { hostname.ifEmpty { ip } }

    /** Ключ записи в списке: ID VPS в OneProvider, а у серверов без него (добавлены вручную) — IP. */
    val key: String get() = vpsId.ifEmpty { "ip:$ip" }

    /** Сервер не из OneProvider (или его ID неизвестен): сверять с аккаунтом и удалять через API нельзя. */
    val manual: Boolean get() = origin == ORIGIN_MANUAL || vpsId.isEmpty()

    companion object {
        const val ORIGIN_COMBINE = "combine"
        const val ORIGIN_PROFILE = "profile"
        const val ORIGIN_MANUAL = "manual"
    }
}

object CombineServers {
    fun encode(list: List<CombineServer>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("vpsId", it.vpsId).put("ip", it.ip).put("fqdn", it.fqdn).put("hostname", it.hostname)
                    .put("location", it.location).put("createdAt", it.createdAt).put("protocols", JSONArray(it.protocols))
                    .put("secured", it.secured).put("origin", it.origin),
            )
        }
    }.toString()

    fun decode(json: String?): List<CombineServer> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val p = o.optJSONArray("protocols")
                CombineServer(
                    vpsId = o.optString("vpsId"), ip = o.getString("ip"), fqdn = o.optString("fqdn"),
                    hostname = o.optString("hostname"), location = o.optString("location"),
                    createdAt = o.optLong("createdAt", 0L),
                    protocols = if (p == null) emptyList() else (0 until p.length()).map { p.getString(it) },
                    secured = o.optBoolean("secured"), origin = o.optString("origin", CombineServer.ORIGIN_COMBINE),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Добавляет сервер (или заменяет запись с тем же VPS или IP); свежие — первыми. */
    fun upsert(list: List<CombineServer>, s: CombineServer): List<CombineServer> =
        listOf(s) + list.filter { it.key != s.key && it.ip != s.ip }
}
