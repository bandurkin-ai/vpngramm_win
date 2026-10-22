package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSummaryTest {

    @Test
    fun emptyAndRunning() {
        assertEquals(LogSummary.State.INFO, LogSummary.of(emptyList(), false).state)
        val s = LogSummary.of(listOf("==> Проверка системы", "OK", "==> Установка Xray"), true)
        assertEquals(LogSummary.State.RUNNING, s.state)
        assertEquals("Выполняется: Установка Xray", s.headline)
    }

    @Test
    fun successWithSecurity() {
        val s = LogSummary.of(
            listOf(
                "==> Запуск службы hysteria-server", "Служба работает.", "Готово! Ссылка для клиента — ниже.",
                "БЕЗОПАСНОСТЬ: …", "Автообновления безопасности включены.",
                "Вход по паролю отключён, вход по ключу оставлен (PasswordAuthentication=no).", "Сервер защищён: вход только по ключу.",
            ),
            false,
        )
        assertEquals(LogSummary.State.OK, s.state)
        assertEquals("Готово: протокол работает, сервер защищён", s.headline)
        assertTrue(s.points.any { "только по SSH-ключу" in it })
    }

    @Test
    fun errorExplained() {
        val s = LogSummary.of(listOf("==> Проверка системы", "ОШИБКА: TCP-порт 443 уже занят другой программой — выберите другой порт:"), false)
        assertEquals(LogSummary.State.ERROR, s.state)
        assertEquals("Не получилось на этапе «Проверка системы»", s.headline)
        assertTrue(s.points.single().contains("порт уже занят"))
        val auth = LogSummary.of(listOf("ОШИБКА: Неверный логин или пароль."), false)
        assertTrue(auth.points.single().contains("логин или пароль"))
    }

    @Test
    fun securityFailureKeepsProtocol() {
        val s = LogSummary.of(listOf("Готово! Ссылка для клиента — ниже.", "ОШИБКА БЕЗОПАСНОСТИ: Сервер не принял ключ. …"), false)
        assertEquals(LogSummary.State.WARN, s.state)
        assertEquals("Протокол работает, но защита не включилась", s.headline)
    }

    @Test
    fun diagnoseExplained() {
        val s = LogSummary.of(
            listOf(
                "==> Службы", "OK: служба xray работает",
                "ПРОБЛЕМА: сертификата Let's Encrypt нет — без него Hysteria2 не работает (нужны открытый 80/TCP и верный DNS)",
                "Проверка завершена: найдено проблем — 1.",
            ),
            false,
        )
        assertEquals(LogSummary.State.WARN, s.state)
        assertEquals("Найдено проблем — 1.", s.headline)
        assertTrue(s.points.any { "80/TCP" in it })
        val ok = LogSummary.of(listOf("OK: служба xray работает", "Проверка завершена: проблем не найдено."), false)
        assertEquals(LogSummary.State.OK, ok.state)
    }
}
