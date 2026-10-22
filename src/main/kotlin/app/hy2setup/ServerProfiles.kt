package app.hy2setup

import org.json.JSONArray
import org.json.JSONObject

/** Сохранённый сервер: адрес, порт, пользователь и способ входа. Пароль/ключ хранятся отдельно в SecretStore. */
data class ServerProfile(val host: String, val port: Int, val user: String, val useKey: Boolean) {
    val id: String get() = "$user@$host:$port"
}

object ServerProfiles {
    fun encode(list: List<ServerProfile>): String = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("host", it.host).put("port", it.port).put("user", it.user).put("useKey", it.useKey))
        }
    }.toString()

    fun decode(json: String?): List<ServerProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ServerProfile(o.getString("host"), o.optInt("port", 22), o.getString("user"), o.optBoolean("useKey"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Добавляет (или поднимает наверх) профиль; самый свежий — первым. */
    fun upsert(list: List<ServerProfile>, p: ServerProfile): List<ServerProfile> =
        listOf(p) + list.filter { it.id != p.id }
}
