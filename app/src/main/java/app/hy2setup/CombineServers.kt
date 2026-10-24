package app.hy2setup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Готовый сервер, который сделал комбайн (или прежний сервер профиля, оставленный при переезде).
 * На него можно перевести постоянные ссылки без покупки нового VPS. Пароль root и ключ лежат в SecretStore
 * под записью «root@ip:22» списка сохранённых серверов.
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
    /** «combine» — сделан комбайном, «profile» — прежний сервер постоянных ссылок. */
    val origin: String = ORIGIN_COMBINE,
) {
    val title: String get() = fqdn.ifEmpty { hostname.ifEmpty { ip } }

    companion object {
        const val ORIGIN_COMBINE = "combine"
        const val ORIGIN_PROFILE = "profile"
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
                    vpsId = o.getString("vpsId"), ip = o.getString("ip"), fqdn = o.optString("fqdn"),
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

    /** Добавляет сервер (или заменяет запись с тем же VPS); свежие — первыми. */
    fun upsert(list: List<CombineServer>, s: CombineServer): List<CombineServer> =
        listOf(s) + list.filter { it.vpsId != s.vpsId && it.ip != s.ip }
}
