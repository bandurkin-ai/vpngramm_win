package app.hy2setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OpVm(
    val id: String,
    val ip: String,
    val hostname: String,
    val status: String,
    val memoryMb: String,
    val storageGb: String,
    val cores: String,
    val bandwidthGb: String,
    val location: String,
    val hourlyPrice: String,
    val cycleCost: String,
    val os: String,
)

data class OpLocation(
    val id: String,
    val region: String,
    val country: String,
    val city: String,
    val sizeIds: Set<String>,
    /** В локации доступны IPv4-адреса (available_ips.ipv4 == "1"). */
    val hasIpv4: Boolean = true,
    /** Сама локация открыта для заказа (нет признаков «нет мест», «отключена» и т.п.). */
    val available: Boolean = true,
) {
    val title: String get() = listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ") + if (region.isNotEmpty()) " ($region)" else ""

    /** Город и страна без региона — для выбора внутри региона. */
    val place: String get() = listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "Локация $id" }

    /** Регион для группировки; если провайдер его не указал — страна. */
    val group: String get() = region.ifEmpty { country }.ifEmpty { "Другие" }
}

data class OpSize(
    val id: String,
    val name: String,
    val type: String,
    val cores: String,
    val ramMb: String,
    val hddGb: String,
    val bandwidthGb: String,
    val speedMbps: String,
    val monthlyPrice: String,
    val hourlyPrice: String,
    val creationAllowed: Boolean,
    /** "included", "addon" или "none". */
    val ipv4: String = "included",
) {
    val monthly: Double? get() = monthlyPrice.toDoubleOrNull()

    /** «1 vCPU · 1 ГБ RAM · 20 ГБ диск · трафик 750 ГБ» */
    val summary: String
        get() {
            val ram = ramMb.toIntOrNull()?.let { if (it >= 1024 && it % 1024 == 0) "${it / 1024} ГБ" else "$it МБ" } ?: "$ramMb МБ"
            return "$cores vCPU · $ram RAM · $hddGb ГБ диск · трафик $bandwidthGb ГБ"
        }
}

data class OpTemplate(val id: String, val name: String)

data class OpSshKey(val uuid: String, val name: String, val value: String)

data class OpCreated(val id: String, val ip: String, val hostname: String, val password: String)

/** Результат удаления: либо удалено, либо провайдер требует подтвердить оплату перерасхода трафика. */
data class OpDestroyResult(
    val destroyed: Boolean,
    val message: String,
    val overageGb: String = "",
    val overageCost: String = "",
    val hoursToWait: String = "",
)

class OneProviderException(message: String, val httpCode: Int = 0) : Exception(message)

/**
 * Клиент OneProvider API (https://api.oneprovider.com).
 * Авторизация — заголовки Api-Key и Client-Key; POST-параметры передаются как форма (application/x-www-form-urlencoded).
 * Ключи нигде не логируются.
 */
class OneProviderClient(
    private val apiKey: String,
    private val clientKey: String,
    private val baseUrl: String = "https://api.oneprovider.com",
) {

    // ---------------- Чтение ----------------

    suspend fun projects(): List<Pair<String, String>> {
        val arr = response(call("GET", "/vm/project/list")).optJSONArray("projects") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.optString("uuid") to o.optString("name") } }
    }

    /** Все VPS из всех проектов. */
    suspend fun listVms(): List<OpVm> {
        val projects = try {
            projects()
        } catch (e: OneProviderException) {
            emptyList()
        }
        val paths = if (projects.isEmpty()) listOf("/vm/listing") else projects.map { "/vm/listing/${enc(it.first)}" }
        val seen = LinkedHashMap<String, OpVm>()
        for (path in paths) {
            parseVms(response(call("GET", path))).forEach { seen.putIfAbsent(it.id, it) }
        }
        return seen.values.toList()
    }

    suspend fun locations(): List<OpLocation> = locationsWithFormat().first

    /** Локации и краткое описание формата ответа (для журнала). */
    suspend fun locationsWithFormat(): Pair<List<OpLocation>, String> {
        val raw = rawResponse(call("GET", "/vm/locations"))
        return parseLocations(raw) to describeLocationsFormat(raw)
    }

    /**
     * Баланс аккаунта. Описание API закрыто входом в кабинет, поэтому пробуем несколько адресов и ищем в ответе
     * число в поле с «balance»/«credit». Возвращает (баланс или null, краткое описание ответа для журнала).
     */
    suspend fun balance(): Pair<String?, String> {
        val tried = mutableListOf<String>()
        for (path in listOf("/account/balance", "/account/info", "/account", "/client/details")) {
            val r = try { rawResponse(call("GET", path)) } catch (e: Exception) { tried += "$path: ${e.message?.take(60)}"; continue }
            val found = findBalance(r)
            if (found != null) return found to "$path"
            tried += "$path: " + describeLocationsFormat(r).take(120)
        }
        return null to tried.joinToString("; ")
    }

    suspend fun sizes(): List<OpSize> = parseSizes(rawResponse(call("GET", "/vm/sizes")))

    suspend fun templates(): List<OpTemplate> = parseTemplates(rawResponse(call("GET", "/vm/templates")))

    suspend fun sshKeys(): List<OpSshKey> = parseSshKeys(response(call("GET", "/vm/sshkeys/list")))

    // ---------------- Изменение ----------------

    suspend fun createVm(locationId: String, sizeId: String, templateId: String, hostname: String, sshKeys: List<String>): OpCreated {
        val params = mutableListOf(
            "location_id" to locationId,
            "instance_size" to sizeId,
            "template" to templateId,
            "hostname" to hostname,
        )
        sshKeys.forEachIndexed { i, k -> params += "ssh_keys[$i]" to k }
        val r = response(call("POST", "/vm/create", params))
        return OpCreated(
            id = r.optString("id"),
            ip = r.optString("ip_address"),
            hostname = r.optString("hostname"),
            password = r.optString("password"),
        )
    }

    suspend fun destroyVm(vmId: String, confirmClose: Boolean): OpDestroyResult {
        val params = mutableListOf("vm_id" to vmId)
        if (confirmClose) params += "confirm_close" to "1"
        return parseDestroy(response(call("POST", "/vm/destroy", params)))
    }

    /** Добавляет публичный SSH-ключ в кабинет. Возвращает созданный ключ (с UUID). */
    suspend fun addSshKey(name: String, publicKey: String): OpSshKey? {
        val r = response(call("POST", "/vm/sshkey/new", listOf("key_name" to name, "key_value" to publicKey)))
        return parseSshKeys(r).firstOrNull()
    }

    /** Удаляет SSH-ключ из кабинета (на уже созданных VPS он остаётся). */
    suspend fun deleteSshKey(uuid: String) {
        response(call("POST", "/vm/sshkey/delete", listOf("ssh_key" to uuid)))
    }

    // ---------------- HTTP ----------------

    private class Raw(val code: Int, val body: String)

    private suspend fun call(method: String, path: String, form: List<Pair<String, String>> = emptyList()): Raw =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("Api-Key", apiKey)
                conn.setRequestProperty("Client-Key", clientKey)
                conn.setRequestProperty("User-Agent", "OneApi/1.0")
                conn.setRequestProperty("Accept", "application/json")
                if (method == "POST") {
                    val body = form.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Raw(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } catch (e: IOException) {
                throw OneProviderException("Нет связи с api.oneprovider.com: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                conn.disconnect()
            }
        }

    /** Проверяет ответ и возвращает поле "response" как объект. */
    private fun response(raw: Raw): JSONObject = rawResponse(raw) as? JSONObject ?: JSONObject()

    /** Проверяет ответ и возвращает поле "response" как есть (объект или массив). */
    private fun rawResponse(raw: Raw): Any {
        val json = try {
            JSONObject(raw.body)
        } catch (e: Exception) {
            throw OneProviderException(describeError(raw.code, raw.body), raw.code)
        }
        if (raw.code !in 200..299 || json.optString("result") != "success") {
            throw OneProviderException(describeError(raw.code, raw.body), raw.code)
        }
        return json.opt("response") ?: JSONObject()
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun strings(any: Any?): List<String> = when (any) {
            is JSONArray -> (0 until any.length()).map { any.optString(it) }
            is JSONObject -> any.keys().asSequence().map { any.optString(it) }.toList()
            else -> emptyList()
        }

        fun parseVms(r: JSONObject): List<OpVm> {
            val arr = r.optJSONArray("instances") ?: return emptyList()
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                OpVm(
                    id = o.optString("id"),
                    ip = o.optString("ip"),
                    hostname = o.optString("hostname"),
                    status = o.optString("billing_status"),
                    memoryMb = o.optString("memory"),
                    storageGb = o.optString("storage"),
                    cores = o.optString("cores"),
                    bandwidthGb = o.optString("bandwidth_limit"),
                    location = o.optString("location"),
                    hourlyPrice = o.optString("hourly_price"),
                    cycleCost = o.optString("cycle_cost"),
                    os = o.optString("operating_system"),
                )
            }
        }

        fun parseLocations(any: Any): List<OpLocation> {
            val items = mutableListOf<JSONObject>()
            when (any) {
                is JSONObject -> any.keys().forEach { region ->
                    val v = any.opt(region)
                    if (v is JSONArray) (0 until v.length()).forEach { i -> v.optJSONObject(i)?.let(items::add) }
                }
                is JSONArray -> (0 until any.length()).forEach { i -> any.optJSONObject(i)?.let(items::add) }
            }
            return items.map {
                OpLocation(
                    id = it.optString("id"),
                    region = it.optString("region"),
                    country = it.optString("country"),
                    city = it.optString("city"),
                    sizeIds = sizeIdsOf(it.opt("available_sizes")),
                    hasIpv4 = it.optJSONObject("available_ips")?.optString("ipv4", "1")?.let { v -> v != "0" } ?: true,
                    available = locationAvailable(it),
                )
            }.sortedWith(compareBy({ it.region }, { it.city }))
        }

        /** Ищет в ответе поле с «balance»/«credit» и числом (с валютой, если она рядом). */
        fun findBalance(any: Any?): String? {
            when (any) {
                is JSONObject -> {
                    for (k in any.keys()) {
                        val v = any.opt(k)
                        val key = k.lowercase()
                        if (("balance" in key || "credit" in key) && v !is JSONObject && v !is JSONArray) {
                            val num = v?.toString()?.trim()?.takeIf { Regex("^-?[0-9]+([.,][0-9]+)?$").matches(it) } ?: continue
                            val cur = any.optString("currency").ifEmpty { "" }
                            return if (cur.isNotEmpty()) "$num $cur" else num
                        }
                        findBalance(v)?.let { return it }
                    }
                }
                is JSONArray -> for (i in 0 until any.length()) findBalance(any.opt(i))?.let { return it }
            }
            return null
        }

        fun parseSizes(any: Any): List<OpSize> {
            val arr = any as? JSONArray ?: return emptyList()
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                OpSize(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    type = o.optString("type"),
                    cores = o.optString("cores"),
                    ramMb = o.optString("ram"),
                    hddGb = o.optString("hdd"),
                    bandwidthGb = o.optString("bandwidth"),
                    speedMbps = o.optString("bandwidth_speed"),
                    monthlyPrice = o.optString("monthly_price"),
                    hourlyPrice = o.optString("hourly_price"),
                    creationAllowed = o.optString("creation_allowed", "1") == "1",
                    ipv4 = o.optString("ipv4", "included").ifEmpty { "included" },
                )
            }.sortedWith(compareBy({ it.monthlyPrice.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.ramMb.toIntOrNull() ?: 0 }))
        }

        /** Максимальная цена тарифа для заказа из приложения, USD в месяц. */
        const val MAX_MONTHLY_PRICE = 10.0

        /** Значения, означающие «нет / недоступно» в полях-признаках API. */
        private val NEGATIVE = setOf(
            "", "0", "false", "no", "none", "unavailable", "not available", "disabled", "soldout", "sold out", "sold_out",
            "out_of_stock", "out of stock", "outofstock", "maintenance", "offline", "inactive", "closed", "full",
        )

        /** «Истинно» ли значение признака: строка/число/булево/объект со счётчиком. */
        private fun truthy(v: Any?): Boolean = when (v) {
            null, JSONObject.NULL -> false
            is Boolean -> v
            is Number -> v.toDouble() > 0
            is JSONObject -> listOf("available", "stock", "qty", "quantity", "count", "status")
                .filter { v.has(it) }.all { truthy(v.opt(it)) }
            is JSONArray -> v.length() > 0
            else -> v.toString().trim().lowercase() !in NEGATIVE
        }

        /**
         * Тарифы локации из available_sizes. Массив или объект с ключами 0..n-1 — это список id.
         * Объект с другими ключами — id тарифа → признак/остаток; берём только «истинные».
         */
        fun sizeIdsOf(any: Any?): Set<String> = when (any) {
            is JSONArray -> (0 until any.length()).map { any.optString(it) }.filter { it.isNotEmpty() }.toSet()
            is JSONObject -> {
                val keys = any.keys().asSequence().toList()
                val listLike = keys.sortedBy { it.toIntOrNull() ?: -1 } == keys.indices.map { "$it" }
                if (listLike) keys.map { any.optString(it) }.filter { it.isNotEmpty() && it != "0" }.toSet()
                else keys.filter { truthy(any.opt(it)) }.toSet()
            }
            else -> emptySet()
        }

        /** Признаки доступности самой локации, если API их отдаёт. Неизвестные значения не считаем отказом. */
        fun locationAvailable(o: JSONObject): Boolean =
            listOf("available", "is_available", "enabled", "creation_allowed", "stock", "status", "state")
                .filter { o.has(it) }
                .all { truthy(o.opt(it)) }

        /** Текст ошибки создания VPS означает «в локации нет мест / она закрыта». */
        fun isUnavailableError(message: String): Boolean {
            val m = message.lowercase()
            val about = listOf("location", "region", "datacenter", "data center", "zone", "node", "локац")
            val reason = listOf(
                "not available", "unavailable", "out of stock", "no stock", "sold out", "soldout", "capacity",
                "no resources", "not enough resources", "insufficient", "maintenance", "currently disabled", "недоступ",
            )
            return reason.any { it in m } && (about.any { it in m } || "out of stock" in m || "sold out" in m || "capacity" in m)
        }

        /**
         * Тариф можно заказать в локации: локация открыта и предлагает этот тариф, разрешено создание, IPv4 входит
         * в тариф, цена не выше лимита. Локация без списка тарифов не подходит: раньше она считалась «доступной для всех»
         * и давала отказ провайдера при создании.
         */
        fun isOrderable(size: OpSize, loc: OpLocation, maxPrice: Double = MAX_MONTHLY_PRICE): Boolean {
            val price = size.monthly ?: return false
            return size.creationAllowed &&
                size.ipv4.equals("included", ignoreCase = true) &&
                loc.available &&
                loc.hasIpv4 &&
                price <= maxPrice &&
                size.id in loc.sizeIds
        }

        /** Сводка по формату ответа /vm/locations для журнала: только имена полей и вид available_sizes, без данных. */
        fun describeLocationsFormat(any: Any): String {
            val first = when (any) {
                is JSONObject -> any.keys().asSequence().mapNotNull { (any.opt(it) as? JSONArray)?.optJSONObject(0) }.firstOrNull()
                is JSONArray -> any.optJSONObject(0)
                else -> null
            } ?: return "формат: неизвестен"
            val av = first.opt("available_sizes")
            val avKind = when (av) {
                is JSONArray -> "массив"
                is JSONObject -> "объект" + ((av.keys().asSequence().firstOrNull())?.let { k ->
                    " (значение: " + when (av.opt(k)) { is JSONObject -> "объект"; is Number -> "число"; is Boolean -> "да/нет"; else -> "строка" } + ")"
                } ?: "")
                null -> "нет"
                else -> "строка"
            }
            return "поля локации: " + first.keys().asSequence().sorted().joinToString(", ") + "; available_sizes: $avKind"
        }

        fun orderableSizes(sizes: List<OpSize>, loc: OpLocation?): List<OpSize> =
            if (loc == null) emptyList() else sizes.filter { isOrderable(it, loc) }

        /** Локации, где есть хотя бы один подходящий тариф. */
        fun orderableLocations(locs: List<OpLocation>, sizes: List<OpSize>): List<OpLocation> =
            locs.filter { loc -> sizes.any { isOrderable(it, loc) } }

        fun parseTemplates(any: Any): List<OpTemplate> {
            val arr = any as? JSONArray ?: return emptyList()
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val display = o.optJSONObject("display")?.optString("display").orEmpty()
                OpTemplate(o.optString("id"), display.ifEmpty { o.optString("name") })
            }.filter { it.id.isNotEmpty() }
        }

        /** Предпочтительная ОС по умолчанию: свежая Ubuntu, затем Debian. */
        fun preferredTemplate(list: List<OpTemplate>): OpTemplate? {
            fun ver(t: OpTemplate) = Regex("(\\d+)(?:\\.(\\d+))?").find(t.name)?.let {
                (it.groupValues[1].toIntOrNull() ?: 0) * 100 + (it.groupValues[2].toIntOrNull() ?: 0)
            } ?: 0
            val pure = list.filter { !it.name.contains("cpanel", true) && !it.name.contains("plesk", true) }
            return pure.filter { it.name.contains("ubuntu", true) }.maxByOrNull(::ver)
                ?: pure.filter { it.name.contains("debian", true) }.maxByOrNull(::ver)
                ?: list.firstOrNull()
        }

        fun parseSshKeys(r: JSONObject): List<OpSshKey> {
            val arr = r.optJSONArray("keys") ?: return emptyList()
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                OpSshKey(o.optString("uuid"), o.optString("name"), o.optString("value"))
            }
        }

        private val PUBLIC_KEY = Regex("^(ssh-(rsa|ed25519|ed448|dss)|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com) [A-Za-z0-9+/]+={0,3}( [^\\r\\n]*)?$")

        /** Приводит публичный ключ к одной строке; null — если это не публичный ключ OpenSSH. */
        fun normalizePublicKey(raw: String): String? {
            val k = raw.trim().replace(Regex("\\s+"), " ")
            return if (PUBLIC_KEY.matches(k)) k else null
        }

        fun parseDestroy(r: JSONObject): OpDestroyResult {
            val msg = r.optString("message")
            val cost = r.optString("bandwidthOverusageCost")
            return if (cost.isNotEmpty() || msg.contains("confirm_close")) {
                OpDestroyResult(
                    destroyed = false,
                    message = msg,
                    overageGb = r.optString("bandwidthOverusage"),
                    overageCost = cost,
                    hoursToWait = r.optString("additionalHoursForCharge"),
                )
            } else {
                OpDestroyResult(destroyed = true, message = msg)
            }
        }

        fun describeError(code: Int, body: String): String {
            val json = try { JSONObject(body) } catch (e: Exception) { null }
            val err = json?.optJSONObject("error")
            val msg = err?.optString("message").orEmpty()
                .ifEmpty { json?.optJSONObject("response")?.optString("message").orEmpty() }
                .ifEmpty { json?.optString("message").orEmpty() }
            val lower = msg.lowercase()
            val head = when {
                code == 401 || code == 403 || "key" in lower && ("invalid" in lower || "wrong" in lower) ->
                    "OneProvider отклонил ключи: проверьте Api-Key и Client-Key."
                "whitelist" in lower || ("ip" in lower && "allowed" in lower) ->
                    "Доступ к API ограничен списком IP (Whitelist). Добавьте IP телефона или отключите ограничение в кабинете."
                else -> "Ошибка OneProvider" + if (code != 200) " (HTTP $code)" else ""
            }
            return if (msg.isNotEmpty()) "$head: $msg" else head
        }
    }
}
