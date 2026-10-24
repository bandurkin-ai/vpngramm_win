package app.hy2setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** DNS-запись зоны name.com. */
data class DnsRecord(
    val id: Int,
    val type: String,
    /** Относительное имя: "vpn" для vpn.example.com, пусто для корня зоны. */
    val host: String,
    /** Полное имя (в ответе API заканчивается точкой). */
    val fqdn: String,
    val answer: String,
    val ttl: Int,
) {
    val displayName: String get() = fqdn.trimEnd('.')
}

class NameComException(message: String, val httpCode: Int = 0) : Exception(message)

/**
 * Клиент name.com Core API (https://docs.name.com).
 * Авторизация — HTTP Basic: логин аккаунта + API-токен. Токен нигде не логируется.
 */
class NameComClient(
    username: String,
    token: String,
    private val baseUrl: String = "https://api.name.com/core/v1",
) {
    private val authHeader =
        "Basic " + Base64.getEncoder().encodeToString("$username:$token".toByteArray(Charsets.UTF_8))

    /** Проверка логина и токена. */
    suspend fun hello() {
        call("GET", "/hello")
    }

    /** Все записи зоны (с учётом постраничной выдачи). */
    suspend fun listRecords(domain: String): List<DnsRecord> {
        val all = mutableListOf<DnsRecord>()
        var page = 1
        while (page <= MAX_PAGES) {
            val json = JSONObject(call("GET", "/domains/$domain/records?perPage=1000&page=$page"))
            all += parseRecords(json)
            val next = json.optInt("nextPage", 0)
            if (next > page) page = next else break
        }
        return all
    }

    suspend fun createRecord(
        domain: String,
        host: String,
        type: String,
        answer: String,
        ttl: Int,
        priority: Int? = null,
    ): DnsRecord {
        val body = JSONObject().apply {
            if (host.isNotEmpty()) put("host", host)
            put("type", type)
            put("answer", answer)
            put("ttl", ttl)
            if (priority != null) put("priority", priority)
        }
        return parseRecord(JSONObject(call("POST", "/domains/$domain/records", body.toString())))
    }

    suspend fun deleteRecord(domain: String, id: Int) {
        call("DELETE", "/domains/$domain/records/$id")
    }

    // --- HTTP ---

    private suspend fun call(method: String, path: String, body: String? = null): String {
        var attempt = 0
        while (true) {
            val result = withContext(Dispatchers.IO) { execute(method, path, body) }
            if (result.code == 429 && attempt < 3) {
                attempt++
                delay((result.retryAfterSec.coerceIn(1, 5)) * 1000L)
                continue
            }
            if (result.code in 200..299) return result.body
            throw NameComException(describeError(result.code, result.body), result.code)
        }
    }

    private class Result(val code: Int, val body: String, val retryAfterSec: Int)

    private fun execute(method: String, path: String, body: String?): Result {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", authHeader)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val retryAfter = conn.getHeaderField("Retry-After")?.toIntOrNull() ?: 1
            return Result(code, text, retryAfter)
        } catch (e: IOException) {
            throw NameComException("Нет связи с name.com: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_PAGES = 50

        fun parseRecords(json: JSONObject): List<DnsRecord> {
            val arr = json.optJSONArray("records") ?: JSONArray()
            return (0 until arr.length()).map { parseRecord(arr.getJSONObject(it)) }
        }

        fun parseRecord(o: JSONObject) = DnsRecord(
            id = o.optInt("id"),
            type = o.optString("type"),
            host = o.optString("host"),
            fqdn = o.optString("fqdn"),
            answer = o.optString("answer"),
            ttl = o.optInt("ttl"),
        )

        /** Понятное объяснение ошибки API. Тело ответа — JSON вида {"message":..,"details":..}. */
        fun describeError(code: Int, body: String): String {
            val json = try { JSONObject(body) } catch (e: Exception) { null }
            val message = json?.optString("message").orEmpty()
            val details = json?.optString("details").orEmpty()
            val extra = listOf(message, details).filter { it.isNotBlank() }.joinToString(": ")
            val lower = extra.lowercase()
            val head = when {
                code == 401 -> "Не удалось войти: проверьте логин name.com и API-токен."
                code == 403 && ("two-step" in lower || "2fa" in lower || "two-factor" in lower) ->
                    "На аккаунте включена двухфакторная защита. Включите доступ к API: name.com → Account Settings → Security."
                code == 403 ->
                    "Доступ запрещён: проверьте логин и токен (для боевого сервера логин без «-test») и права токена."
                code == 404 -> "Не найдено: такой домен отсутствует в вашем аккаунте name.com (или записи уже нет)."
                code == 400 -> "name.com отклонил запрос как некорректный."
                code == 429 -> "Превышен лимит запросов name.com, повторите через минуту."
                code >= 500 -> "Сервис name.com временно недоступен, повторите позже."
                else -> "Ошибка name.com (HTTP $code)."
            }
            return if (extra.isNotEmpty() && code != 401) "$head ($extra)" else head
        }
    }
}
