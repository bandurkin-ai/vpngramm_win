package app.hy2setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.Normalizer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Пинг до локации: [ms] — время ответа; [estimated] — оценка по расстоянию, а не замер. */
data class PingResult(val ms: Int, val estimated: Boolean) {
    /** «42 мс» или «≈ 60 мс». */
    val title: String get() = (if (estimated) "≈ " else "") + "$ms мс"
}

/**
 * Пинг до локаций OneProvider. Настоящий замер — время установки TCP-соединения (ICMP-пинг приложениям Android
 * недоступен; ответ «порт закрыт» тоже годится — это полный путь туда и обратно). Где мерить не до чего —
 * оценка по расстоянию от пользователя до города локации.
 */
object Ping {
    /** Время TCP-соединения с [host] (лучшее из [tries] попыток) по первому ответившему порту; null — нет ответа. */
    suspend fun tcpRttMs(host: String, ports: List<Int> = listOf(443, 22, 80), tries: Int = 3, timeoutMs: Int = 2_000): Int? =
        withContext(Dispatchers.IO) {
            for (port in ports) {
                var best: Long? = null
                repeat(tries) {
                    val t0 = System.nanoTime()
                    val ok = try {
                        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                        true
                    } catch (e: java.net.ConnectException) {
                        true // RST от сервера — ответ пришёл
                    } catch (e: Exception) {
                        false
                    }
                    if (ok) {
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        best = best?.let { minOf(it, ms) } ?: ms
                    }
                }
                best?.let { return@withContext it.toInt().coerceAtLeast(1) }
            }
            null
        }

    /** Примерные координаты пользователя по внешнему IP (ipwho.is); null — не удалось. */
    suspend fun myLocation(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://ipwho.is/").openConnection() as HttpURLConnection
            c.connectTimeout = 5_000; c.readTimeout = 5_000
            c.setRequestProperty("Accept", "application/json")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (!o.optBoolean("success", false)) null
            else o.optDouble("latitude").takeIf { !it.isNaN() }?.let { lat -> o.optDouble("longitude").takeIf { !it.isNaN() }?.let { lat to it } }
        }.getOrNull()
    }

    /** Расстояние по дуге большого круга, км. */
    fun distanceKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    /**
     * Оценка пинга по расстоянию: свет в оптоволокне — ~200 км/мс, маршрут в среднем в 1,5 раза длиннее прямой,
     * туда и обратно, плюс ~5 мс на оборудование.
     */
    fun estimateMs(from: Pair<Double, Double>, to: Pair<Double, Double>): Int =
        (distanceKm(from, to) * 2 * 1.5 / 200 + 5).roundToInt()

    /** Координаты города локации (по названию, без учёта регистра и диакритики); null — города нет в справочнике. */
    fun cityCoord(city: String): Pair<Double, Double>? = CITIES[norm(city)] ?: CITIES[norm(city.substringBefore(",").substringBefore("("))]

    private fun norm(s: String) = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-zа-я]"), "")

    /** Города дата-центров (широта, долгота) — для оценки пинга. */
    private val CITIES: Map<String, Pair<Double, Double>> = listOf(
        // Европа
        "Amsterdam" to (52.37 to 4.90), "Rotterdam" to (51.92 to 4.48), "Brussels" to (50.85 to 4.35),
        "Luxembourg" to (49.61 to 6.13), "Frankfurt" to (50.11 to 8.68), "Frankfurt am Main" to (50.11 to 8.68),
        "Berlin" to (52.52 to 13.40), "Hamburg" to (53.55 to 9.99), "Munich" to (48.14 to 11.58), "Dusseldorf" to (51.23 to 6.77),
        "Nuremberg" to (49.45 to 11.08), "Falkenstein" to (50.48 to 12.37), "Paris" to (48.86 to 2.35), "Marseille" to (43.30 to 5.37),
        "Lyon" to (45.76 to 4.84), "Strasbourg" to (48.57 to 7.75), "Roubaix" to (50.69 to 3.18), "Gravelines" to (50.99 to 2.13),
        "London" to (51.51 to -0.13), "Manchester" to (53.48 to -2.24), "Dublin" to (53.35 to -6.26), "Madrid" to (40.42 to -3.70),
        "Barcelona" to (41.39 to 2.17), "Valencia" to (39.47 to -0.38), "Lisbon" to (38.72 to -9.14), "Milan" to (45.46 to 9.19),
        "Rome" to (41.90 to 12.50), "Florence" to (43.77 to 11.26), "Palermo" to (38.12 to 13.36), "Vienna" to (48.21 to 16.37),
        "Zurich" to (47.38 to 8.54), "Geneva" to (46.20 to 6.14), "Prague" to (50.08 to 14.44), "Bratislava" to (48.15 to 17.11),
        "Budapest" to (47.50 to 19.04), "Warsaw" to (52.23 to 21.01), "Bucharest" to (44.43 to 26.10), "Sofia" to (42.70 to 23.32),
        "Belgrade" to (44.79 to 20.45), "Zagreb" to (45.81 to 15.98), "Ljubljana" to (46.06 to 14.51), "Athens" to (37.98 to 23.73),
        "Thessaloniki" to (40.64 to 22.94), "Istanbul" to (41.01 to 28.98), "Nicosia" to (35.19 to 33.38), "Limassol" to (34.71 to 33.02),
        "Valletta" to (35.90 to 14.51), "Stockholm" to (59.33 to 18.07), "Gothenburg" to (57.71 to 11.97), "Oslo" to (59.91 to 10.75),
        "Copenhagen" to (55.68 to 12.57), "Helsinki" to (60.17 to 24.94), "Tallinn" to (59.44 to 24.75), "Riga" to (56.95 to 24.11),
        "Vilnius" to (54.69 to 25.28), "Kyiv" to (50.45 to 30.52), "Kiev" to (50.45 to 30.52), "Chisinau" to (47.01 to 28.86),
        "Minsk" to (53.90 to 27.56), "Moscow" to (55.76 to 37.62), "Saint Petersburg" to (59.93 to 30.36), "St Petersburg" to (59.93 to 30.36),
        "Reykjavik" to (64.15 to -21.94), "Tbilisi" to (41.72 to 44.79), "Yerevan" to (40.18 to 44.51), "Baku" to (40.41 to 49.87),
        "Almaty" to (43.24 to 76.89), "Skopje" to (42.00 to 21.43), "Tirana" to (41.33 to 19.82), "Sarajevo" to (43.86 to 18.41),
        "Podgorica" to (42.44 to 19.26),
        // Северная Америка
        "New York" to (40.71 to -74.01), "Newark" to (40.74 to -74.17), "Secaucus" to (40.79 to -74.06), "Ashburn" to (39.04 to -77.49),
        "Washington" to (38.91 to -77.04), "Boston" to (42.36 to -71.06), "Philadelphia" to (39.95 to -75.17), "Miami" to (25.76 to -80.19),
        "Atlanta" to (33.75 to -84.39), "Chicago" to (41.88 to -87.63), "Dallas" to (32.78 to -96.80), "Houston" to (29.76 to -95.37),
        "Denver" to (39.74 to -104.99), "Phoenix" to (33.45 to -112.07), "Las Vegas" to (36.17 to -115.14), "Salt Lake City" to (40.76 to -111.89),
        "Kansas City" to (39.10 to -94.58), "Minneapolis" to (44.98 to -93.27), "Los Angeles" to (34.05 to -118.24), "San Jose" to (37.34 to -121.89),
        "San Francisco" to (37.77 to -122.42), "Seattle" to (47.61 to -122.33), "Portland" to (45.52 to -122.68), "Montreal" to (45.50 to -73.57),
        "Toronto" to (43.65 to -79.38), "Vancouver" to (49.28 to -123.12), "Mexico City" to (19.43 to -99.13), "Queretaro" to (20.59 to -100.39),
        // Южная Америка
        "Sao Paulo" to (-23.55 to -46.63), "Rio de Janeiro" to (-22.91 to -43.17), "Buenos Aires" to (-34.60 to -58.38),
        "Santiago" to (-33.45 to -70.67), "Bogota" to (4.71 to -74.07), "Lima" to (-12.05 to -77.04),
        // Африка и Ближний Восток
        "Johannesburg" to (-26.20 to 28.05), "Cape Town" to (-33.92 to 18.42), "Lagos" to (6.52 to 3.38), "Nairobi" to (-1.29 to 36.82),
        "Cairo" to (30.04 to 31.24), "Casablanca" to (33.57 to -7.59), "Tel Aviv" to (32.09 to 34.78), "Dubai" to (25.20 to 55.27),
        "Fujairah" to (25.13 to 56.33), "Riyadh" to (24.71 to 46.68),
        // Азия и Океания
        "Mumbai" to (19.08 to 72.88), "Delhi" to (28.70 to 77.10), "New Delhi" to (28.61 to 77.21), "Bangalore" to (12.97 to 77.59),
        "Chennai" to (13.08 to 80.27), "Singapore" to (1.35 to 103.82), "Hong Kong" to (22.32 to 114.17), "Tokyo" to (35.68 to 139.69),
        "Osaka" to (34.69 to 135.50), "Seoul" to (37.57 to 126.98), "Taipei" to (25.03 to 121.57), "Manila" to (14.60 to 120.98),
        "Jakarta" to (-6.21 to 106.85), "Kuala Lumpur" to (3.14 to 101.69), "Bangkok" to (13.76 to 100.50), "Hanoi" to (21.03 to 105.85),
        "Ho Chi Minh City" to (10.82 to 106.63), "Karachi" to (24.86 to 67.01), "Dhaka" to (23.81 to 90.41), "Sydney" to (-33.87 to 151.21),
        "Melbourne" to (-37.81 to 144.96), "Brisbane" to (-27.47 to 153.03), "Perth" to (-31.95 to 115.86), "Auckland" to (-36.85 to 174.76),
    ).associate { (name, c) -> norm(name) to c }
}
