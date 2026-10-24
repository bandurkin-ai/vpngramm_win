package app.hy2setup

import android.app.Application
import android.content.Context
import android.os.Build
import app.hy2setup.platform.AndroidPlatform
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Приложение: перехватывает падения и сохраняет отчёт (только стек и модель телефона, без ключей и паролей).
 * При следующем запуске отчёт показывается, чтобы его можно было скопировать и прислать.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                CrashReport.save(this, thread.name, e)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }
}

/** Модель экрана для Android. */
fun MainViewModel(app: Application) = MainViewModel(AndroidPlatform(app))

object CrashReport {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun save(ctx: Context, thread: String, e: Throwable) {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val text = buildString {
            append("vpngramm ").append(version).append('\n')
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append(", поток ").append(thread).append("\n\n")
            append(e.stackTraceToString().take(12_000))
        }
        file(ctx).writeText(text)
    }

    /** Отчёт о прошлом падении (и удаление его с телефона) или null. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
