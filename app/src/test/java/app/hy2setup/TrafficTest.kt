package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTest {
    private val at = java.util.Calendar.getInstance().apply { set(2026, 9, 26, 12, 0) }.timeInMillis

    @Test
    fun usageWithDateAndLimit() {
        assertEquals("Трафик на 26.10.2026: 312.4 из 1000 ГБ (31%)", Traffic.usage(312.4, 1000.0, at))
        assertEquals("Трафик за месяц: 5.0 ГБ", Traffic.usage(5.0, null, 0))
    }

    @Test
    fun limitStates() {
        assertEquals(Traffic.Level.OK to "Лимит не превышен: осталось 687.6 ГБ.", Traffic.limitState(312.4, 1000.0))
        assertEquals(Traffic.Level.WARN, Traffic.limitState(900.0, 1000.0).first)
        val (lvl, text) = Traffic.limitState(1050.5, 1000.0)
        assertEquals(Traffic.Level.OVER, lvl)
        assertTrue(text, text.startsWith("Лимит превышен на 50.5 ГБ"))
        assertEquals(Traffic.Level.UNKNOWN, Traffic.limitState(10.0, null).first)
    }
}
