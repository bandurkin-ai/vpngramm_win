package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderFilterTest {
    private fun size(id: String, price: String, ipv4: String = "included", allowed: Boolean = true, traffic: String = "1000") =
        OpSize(id, "", "General", "1", "1024", "20", traffic, "1000", price, "0.01", allowed, ipv4)

    private val paris = OpLocation("1", "Europe", "FR", "Paris", setOf("a", "b", "c", "d", "e", "f"))
    private val noIpv4 = OpLocation("2", "Asia", "SG", "Singapore", emptySet(), hasIpv4 = false)

    @Test
    fun filtersByAvailabilityAndIpv4WithoutPriceLimit() {
        val sizes = listOf(
            size("a", "4.00"),
            size("b", "10.00"),
            size("c", "45.00"),            // дороже $10 — теперь тоже показывается
            size("d", "5.00", ipv4 = "addon"),
            size("e", "5.00", ipv4 = "none"),
            size("f", "3.00", allowed = false),
        )
        assertEquals(listOf("a", "b", "c"), OneProviderClient.orderableSizes(sizes, paris).map { it.id })
    }

    @Test
    fun filterByPriceAndTraffic() {
        val sizes = listOf(
            size("a", "4.00", traffic = "1000"),
            size("b", "10.00", traffic = "2000"),
            size("c", "10.01", traffic = "5 TB"),
            size("d", "30.00", traffic = "Unlimited"),
            size("e", "6.00", traffic = ""),          // трафик неизвестен — при фильтре по трафику скрыт
        )
        fun ids(f: OpFilter) = OneProviderClient.orderableSizes(sizes, paris.copy(sizeIds = setOf("a", "b", "c", "d", "e")), f).map { it.id }
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(OpFilter()))
        assertEquals(listOf("a", "b", "e"), ids(OpFilter(maxPrice = 10)))           // ровно $10 подходит
        assertEquals(listOf("b", "c", "d"), ids(OpFilter(minTrafficGb = 2000)))
        assertEquals(listOf("b"), ids(OpFilter(maxPrice = 10, minTrafficGb = 2000)))
        assertEquals(listOf("d"), ids(OpFilter(minTrafficGb = 10000)))            // безлимит проходит любой порог
        // локация видна, только если в ней есть тариф под фильтр
        assertEquals(emptyList<String>(), OneProviderClient.orderableLocations(listOf(paris), sizes, OpFilter(maxPrice = 5, minTrafficGb = 5000)).map { it.id })
    }

    @Test
    fun parsesTraffic() {
        assertEquals(1000.0, OneProviderClient.parseTrafficGb("1000")!!, 0.0)
        assertEquals(2000.0, OneProviderClient.parseTrafficGb("2 TB")!!, 0.0)
        assertEquals(1500.0, OneProviderClient.parseTrafficGb("1,5 ТБ")!!, 0.0)
        assertTrue(OneProviderClient.parseTrafficGb("Unlimited")!!.isInfinite())
        assertEquals(null, OneProviderClient.parseTrafficGb(""))
        assertEquals(null, OneProviderClient.parseTrafficGb("0"))
        assertEquals("до $10/мес, трафик от 2 ТБ", OpFilter(10, 2000).title)
        assertEquals("без фильтра", OpFilter().title)
    }

    @Test
    fun sizeNotOfferedInLocationIsExcluded() {
        assertFalse(OneProviderClient.isOrderable(size("zzz", "4.00"), paris))
    }

    @Test
    fun locationWithoutIpv4IsHidden() {
        val sizes = listOf(size("a", "4.00"))
        assertFalse(OneProviderClient.isOrderable(sizes[0], noIpv4))
        assertEquals(listOf("1"), OneProviderClient.orderableLocations(listOf(paris, noIpv4), sizes).map { it.id })
    }

    @Test
    fun unparseablePriceIsExcluded() {
        assertFalse(OneProviderClient.isOrderable(size("a", ""), paris))
    }

    @Test
    fun parsesIpv4FieldsFromApi() {
        val loc = OneProviderClient.parseLocations(
            org.json.JSONObject("""{"Asia":[{"id":"7","city":"X","available_sizes":["1"],"available_ips":{"ipv4":"0","ipv6":"1"}}]}"""),
        ).single()
        assertFalse(loc.hasIpv4)
        val sz = OneProviderClient.parseSizes(org.json.JSONArray("""[{"id":"1","monthly_price":"4.00","ipv4":"addon"}]""")).single()
        assertEquals("addon", sz.ipv4)
    }

    @Test
    fun dohAnswerParsing() {
        val json = """{"Status":0,"Answer":[{"name":"vpn.example.com.","type":5,"data":"x.example.com."},
            {"name":"x.example.com.","type":1,"TTL":300,"data":"203.0.113.10"}]}"""
        assertEquals(setOf("203.0.113.10"), DnsCheck.parseAnswers(json))
        assertTrue(DnsCheck.parseAnswers("""{"Status":3}""").isEmpty())
        assertTrue(DnsCheck.parseAnswers("garbage").isEmpty())
    }

    private fun locs(json: String) = OneProviderClient.parseLocations(org.json.JSONObject(json))

    @Test
    fun locationWithoutSizeListIsHidden() {
        // раньше пустой available_sizes означал «можно всё» — и провайдер отказывал при создании
        val empty = locs("""{"Europe":[{"id":"9","region":"Europe","city":"Oslo","available_sizes":[]}]}""").single()
        assertFalse(OneProviderClient.isOrderable(size("a", "4.00"), empty))
        val missing = locs("""{"Europe":[{"id":"9","region":"Europe","city":"Oslo"}]}""").single()
        assertTrue(missing.sizeIds.isEmpty())
        assertFalse(OneProviderClient.isOrderable(size("a", "4.00"), missing))
    }

    @Test
    fun sizeMapWithFlagsAndStock() {
        assertEquals(setOf("44"), OneProviderClient.sizeIdsOf(org.json.JSONObject("""{"44":"1","45":"0","46":false}""")))
        assertEquals(setOf("44", "47"), OneProviderClient.sizeIdsOf(
            org.json.JSONObject("""{"44":{"available":2},"45":{"available":0},"46":{"stock":"0"},"47":{"name":"x"}}"""),
        ))
        // объект-список с ключами 0..n-1 — это id
        assertEquals(setOf("44", "45"), OneProviderClient.sizeIdsOf(org.json.JSONObject("""{"0":"44","1":"45"}""")))
        assertEquals(setOf("44"), OneProviderClient.sizeIdsOf(org.json.JSONArray("""["44",""]""")))
    }

    @Test
    fun closedLocationIsHidden() {
        val list = locs("""{"Europe":[
            {"id":"1","region":"Europe","city":"A","available_sizes":["a"],"available":"0"},
            {"id":"2","region":"Europe","city":"B","available_sizes":["a"],"status":"soldout"},
            {"id":"3","region":"Europe","city":"C","available_sizes":["a"],"status":"active"},
            {"id":"4","region":"Europe","city":"D","available_sizes":["a"],"stock":0}]}""")
        assertEquals(listOf("3"), OneProviderClient.orderableLocations(list, listOf(size("a", "4.00"))).map { it.id })
    }

    @Test
    fun recognizesUnavailableErrors() {
        for (m in listOf(
            "Ошибка OneProvider: This location is not available at the moment",
            "Ошибка OneProvider (HTTP 400): Location unavailable",
            "Ошибка OneProvider: Instance size is out of stock",
            "Ошибка OneProvider: No capacity left in this region",
        )) assertTrue(m, OneProviderClient.isUnavailableError(m))
        for (m in listOf("OneProvider отклонил ключи: проверьте Api-Key", "Ошибка OneProvider: Invalid hostname", "Нет связи с api.oneprovider.com")) {
            assertFalse(m, OneProviderClient.isUnavailableError(m))
        }
    }

    @Test
    fun formatSummaryHasNoData() {
        val s = OneProviderClient.describeLocationsFormat(org.json.JSONObject(
            """{"Europe":[{"id":"1","city":"Paris","available_sizes":{"44":"1"},"available_ips":{"ipv4":"1"}}]}""",
        ))
        assertTrue(s, s.contains("available_sizes: объект (значение: строка)") && s.contains("city") && !s.contains("Paris"))
    }
}
