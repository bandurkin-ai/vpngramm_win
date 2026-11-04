package app.hy2setup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Тексты о трафике VPS для карточки сервера на Главной: расход на дату замера и состояние лимита. */
object Traffic {
    enum class Level { OK, WARN, OVER, UNKNOWN }

    private fun gb(v: Double) = "%.1f".format(Locale.US, v)

    /** «Трафик на 26.10.2026: 312.4 из 1000 ГБ (31%)»; [at] — когда считан (0 — неизвестно). */
    fun usage(used: Double, limit: Double?, at: Long): String {
        val date = if (at > 0) " на " + SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(at)) else " за месяц"
        return "Трафик$date: " + if (limit != null) "${gb(used)} из ${limit.toInt()} ГБ (${(used / limit * 100).toInt()}%)" else "${gb(used)} ГБ"
    }

    /** Превышен ли лимит тарифа и сколько осталось (или на сколько превышен). */
    fun limitState(used: Double, limit: Double?): Pair<Level, String> = when {
        limit == null -> Level.UNKNOWN to "Лимит трафика неизвестен: введите ключи OneProvider и нажмите «Проверить»."
        used > limit -> Level.OVER to "Лимит превышен на ${gb(used - limit)} ГБ — OneProvider берёт доплату за перерасход."
        used > limit * 0.8 -> Level.WARN to "Лимит не превышен, но близко: осталось ${gb(limit - used)} ГБ."
        else -> Level.OK to "Лимит не превышен: осталось ${gb(limit - used)} ГБ."
    }
}
