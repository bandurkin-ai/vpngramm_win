package app.hy2setup.desktop

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Падения приложения: отчёт (только стек и версия Windows, без ключей и паролей) сохраняется в crash.txt
 * и показывается при следующем запуске, чтобы его можно было скопировать и прислать.
 */
object CrashLog {
    private fun file(dir: File) = File(dir, "crash.txt")

    fun install(dir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { save(dir, thread.name, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    fun save(dir: File, thread: String, e: Throwable) {
        val version = CrashLog::class.java.getResourceAsStream("/vpngramm-version.txt")?.use { String(it.readBytes()).trim() } ?: "?"
        val text = buildString {
            append("vpngramm ").append(version).append(" для Windows\n")
            append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append("), Java ").append(System.getProperty("java.version")).append('\n')
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append(", поток ").append(thread).append("\n\n")
            append(e.stackTraceToString().take(12_000))
        }
        dir.mkdirs()
        file(dir).writeText(text)
    }

    /** Отчёт о прошлом падении (и удаление файла) или null. */
    fun take(dir: File): String? {
        val f = file(dir)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
