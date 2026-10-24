package app.hy2setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Проверка DNS через публичные резолверы по HTTPS (DNS-over-HTTPS), минуя кэш телефона и оператора.
 */
object DnsCheck {

    private val resolvers = listOf(
        "https://dns.google/resolve?type=%s&name=",
        "https://cloudflare-dns.com/dns-query?type=%s&name=",
    )

    /** IPv4-адреса имени по данным публичных резолверов (объединение ответов). */
    suspend fun resolveA(name: String): Set<String> = resolve(name, "A", 1)

    /** IPv6-адреса имени (AAAA). Если они есть, телефон может подключаться по ним, а не к нашему IPv4. */
    suspend fun resolveAAAA(name: String): Set<String> = resolve(name, "AAAA", 28)

    private suspend fun resolve(name: String, type: String, code: Int): Set<String> = withContext(Dispatchers.IO) {
        val out = mutableSetOf<String>()
        for (base in resolvers) {
            try {
                val conn = URL(base.format(type) + URLEncoder.encode(name, "UTF-8")).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Accept", "application/dns-json")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                out += parseAnswers(body, code)
            } catch (e: Exception) {
                // один из резолверов недоступен — пробуем следующий
            }
        }
        out
    }

    /** Достаёт записи типа [type] (1 — A, 28 — AAAA) из JSON-ответа DoH. */
    fun parseAnswers(json: String, type: Int = 1): Set<String> {
        val arr = try { JSONObject(json).optJSONArray("Answer") } catch (e: Exception) { null } ?: return emptySet()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.takeIf { it.optInt("type") == type }?.optString("data")?.takeIf { it.isNotEmpty() }
        }.toSet()
    }
}
