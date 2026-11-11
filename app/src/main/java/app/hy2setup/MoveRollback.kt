package app.hy2setup

import org.json.JSONObject

/**
 * Незавершённый переезд сервера («Заменить сервер» остановлен или упал): что нужно, чтобы вернуть всё как было.
 * Старый сервер при переезде не меняется, поэтому откат — это вернуть профиль и A-запись на него
 * и решить, что делать с новым VPS. Хранится в зашифрованном хранилище ([before] содержит ключи профиля).
 */
data class MoveRollback(
    /** Домен сервера Главной. */
    val fqdn: String,
    /** Профиль до переезда ([StableProfile.toJson]). */
    val before: String,
    /** IP прежнего (рабочего) сервера. */
    val oldIp: String,
    /** Новый сервер: ID в OneProvider (пусто — не из OneProvider), IP и имя. */
    val newId: String = "",
    val newIp: String = "",
    val newHost: String = "",
    /** Новый VPS куплен в этом запуске (иначе переезжали на свободный сервер). */
    val bought: Boolean = false,
    /** A-запись домена уже могла быть переведена на новый IP. */
    val dnsChanged: Boolean = false,
    val at: Long = System.currentTimeMillis(),
) {
    /** Что уже сделано — строки для карточки «Переезд не завершён». */
    fun summary(): List<String> = listOfNotNull(
        when {
            bought && newIp.isNotEmpty() -> "Куплен новый VPS $newIp${newHost.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()} — он оплачивается."
            bought -> "Куплен новый VPS${newHost.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()} — он оплачивается."
            newIp.isNotEmpty() -> "Переезд шёл на свободный сервер $newIp."
            else -> null
        },
        if (dnsChanged) "A-запись $fqdn уже могла быть переведена на новый адрес — клиенты могут попадать на недоделанный сервер."
        else "A-запись $fqdn не менялась — клиенты по-прежнему подключаются к $oldIp.",
    )

    fun toJson(): String = JSONObject()
        .put("fqdn", fqdn).put("before", before).put("oldIp", oldIp)
        .put("newId", newId).put("newIp", newIp).put("newHost", newHost)
        .put("bought", bought).put("dnsChanged", dnsChanged).put("at", at)
        .toString()

    companion object {
        fun fromJson(s: String?): MoveRollback? {
            if (s.isNullOrBlank()) return null
            return try {
                val o = JSONObject(s)
                MoveRollback(
                    fqdn = o.getString("fqdn"), before = o.getString("before"), oldIp = o.getString("oldIp"),
                    newId = o.optString("newId"), newIp = o.optString("newIp"), newHost = o.optString("newHost"),
                    bought = o.optBoolean("bought"), dnsChanged = o.optBoolean("dnsChanged"), at = o.optLong("at", 0L),
                ).takeIf { it.fqdn.isNotEmpty() && it.oldIp.isNotEmpty() && runCatching { StableProfile.fromJson(it.before) }.isSuccess }
            } catch (e: Exception) {
                null
            }
        }
    }
}
