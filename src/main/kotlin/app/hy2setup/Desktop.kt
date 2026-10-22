package app.hy2setup

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.sun.jna.Library
import com.sun.jna.Native
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.LockSupport

const val APP_VERSION = "2.0.1"

/** Значок приложения (окно и трей) из ресурса icon.png. */
object AppIcon {
    val image: BufferedImage by lazy {
        AppIcon::class.java.getResourceAsStream("/icon.png")!!.use { javax.imageio.ImageIO.read(it) }
    }
    val painter by lazy { BitmapPainter(image.toComposeImageBitmap()) }
}

/** Уведомления Windows через значок в трее. Нажатие на значок открывает окно, а при тревоге — «Заменить сервер». */
object Notifications {
    @Volatile var tray: TrayState? = null
    @Volatile var pendingFqdn: String? = null

    fun serverDown(fqdn: String, reason: String) {
        pendingFqdn = fqdn
        tray?.sendNotification(Notification("$fqdn недоступен", "$reason Заменить сервер? Откройте vpngramm."))
    }

    /** Забирает сервер, по которому была тревога (один раз). */
    fun takePending(): String? = pendingFqdn.also { pendingFqdn = null }
}

private interface Kernel32 : Library {
    fun SetThreadExecutionState(flags: Int): Int
}

/** Пока идёт долгая работа (комбайн, переезд, обновление), не даём Windows уснуть и оборвать SSH. */
object KeepAwake {
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    @Volatile private var want = false
    private var thread: Thread? = null

    @Synchronized
    fun set(on: Boolean) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        want = on
        if (thread == null) {
            // SetThreadExecutionState действует на вызывающий поток, поэтому держим его отдельным потоком
            thread = Thread {
                val k32 = runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
                var held = false
                while (k32 != null) {
                    if (want != held) {
                        k32.SetThreadExecutionState(if (want) ES_CONTINUOUS or ES_SYSTEM_REQUIRED else ES_CONTINUOUS)
                        held = want
                    }
                    LockSupport.parkNanos(500_000_000L)
                }
            }.apply { isDaemon = true; name = "keep-awake"; start() }
        }
    }
}

/** Падение приложения: сохраняем отчёт (только стек и версия системы, без ключей и паролей), при следующем запуске показываем. */
object CrashReport {
    private fun file() = File(AppDirs.root, "crash.txt")

    fun save(thread: String, e: Throwable) {
        val text = buildString {
            append("vpngramm ").append(APP_VERSION).append(" (Windows)\n")
            append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(", Java ")
                .append(System.getProperty("java.version")).append('\n')
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append(", поток ").append(thread).append("\n\n")
            append(e.stackTraceToString().take(12_000))
        }
        file().writeText(text)
    }

    /** Отчёт о прошлом падении (и удаление его с диска) или null. */
    fun take(): String? {
        val f = file()
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
