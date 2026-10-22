package app.hy2setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Долгая работа на сервере (обновления, сборка модуля ядра) отдельной службой systemd: обрыв SSH её не прерывает.
 * Приложение запускает задачу ([Scripts.jobStart]) и короткими сессиями дочитывает журнал ([Scripts.jobPoll]),
 * переподключаясь при обрывах. [log] получает строки журнала.
 */
class BackgroundJob(private val runner: SshRunner, private val log: (String) -> Unit) {

    /**
     * Запускает задачу [name] скриптом [start] и ждёт её окончания. [what] — название работы для сообщений.
     * [onMarker] получает каждую строку журнала и возвращает true, если это служебная строка (её не показываем).
     * Возвращает код завершения задачи.
     */
    suspend fun run(
        cfg: SshConfig, name: String, start: String, what: String,
        limitMs: Long = 40 * 60_000L, onMarker: (String) -> Boolean = { false },
    ): Int {
        var failures = 0
        while (true) {
            val started = AtomicBoolean(false)
            try {
                runner.runScript(cfg, start) { line ->
                    if (line.trim() == Scripts.UPD_STARTED) started.set(true) else log(line)
                }
            } catch (e: SshException) {
                if (fatal(e)) throw e
            }
            if (started.get()) break
            if (++failures >= 10) throw IllegalStateException("$what: не удалось запустить — сервер не отвечает по SSH.")
            delay(10_000)
        }

        var offset = 0L
        var lost = 0
        var warned = false
        val deadline = System.currentTimeMillis() + limitMs
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("$what: идёт дольше ${limitMs / 60_000} минут — повторите позже.")
            }
            var pos: Pair<Long, String>? = null
            var printed = false
            try {
                runner.runScript(cfg, Scripts.jobPoll(name, offset)) { line ->
                    val p = Scripts.parseUpdPos(line)
                    when {
                        p != null -> pos = p
                        line.startsWith(Scripts.UPD_DONE) -> Unit
                        onMarker(line) -> Unit
                        else -> { log(line); printed = true }
                    }
                }
            } catch (e: SshException) {
                if (fatal(e)) throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // обрыв во время чтения — переподключимся
            }
            val p = pos
            if (p == null) {
                if (!warned) {
                    log("Связь с сервером прервалась — работа продолжается на сервере, переподключаюсь…")
                    warned = true
                }
                if (++lost >= 40) {
                    throw IllegalStateException(
                        "Связь с сервером не восстанавливается. $what, вероятно, продолжается на сервере — повторите через 10–15 минут.",
                    )
                }
                delay(10_000)
                continue
            }
            if (warned) { log("Связь восстановлена."); warned = false }
            lost = 0
            offset = p.first
            val state = p.second
            when {
                state == "run" -> delay(if (printed) 2_000 else 5_000)
                state.startsWith("done:") -> return state.removePrefix("done:").toIntOrNull() ?: -1
                else -> throw IllegalStateException("$what: служба на сервере остановилась без результата (например, сервер перезагрузился) — повторите.")
            }
        }
    }

    /** Ошибки, при которых повторять бессмысленно: ключ не подходит, сменился ключ сервера и т.п. */
    private fun fatal(e: SshException): Boolean {
        val m = e.message.orEmpty()
        return "Ключ сервера" in m || "Вставлен" in m || "пароль от ключа" in m || "прочитать приватный" in m ||
            "Неверный" in m || "не принял" in m
    }
}
