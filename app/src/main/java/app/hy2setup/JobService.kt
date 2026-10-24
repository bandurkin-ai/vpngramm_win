package app.hy2setup

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Пока идёт долгая работа (создание сервера, переезд, обновление), приложение держит уведомление в шторке.
 * Тогда Android не усыпляет его и не обрывает сеть, если приложение свернуть.
 */
class JobService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Работа с сервером…"
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, Notifications.JOB_ID, Notifications.job(this, text), type)
        } catch (e: Exception) {
            // система не дала стать foreground-службой (ограничения Android 14+/оболочки) — работаем без уведомления
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            wake = runCatching {
                (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vpngramm:job").apply {
                    setReferenceCounted(false); acquire(2 * 60 * 60 * 1000L)
                }
            }.getOrNull()
            @Suppress("DEPRECATION")
            wifi = runCatching {
                (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vpngramm:job").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        active = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TEXT = "text"

        @Volatile private var active = false

        fun update(ctx: Context, text: String) {
            if (active) {
                // служба уже работает — только меняем текст (запуск из фона на Android 12+ запрещён)
                try {
                    androidx.core.app.NotificationManagerCompat.from(ctx).notify(Notifications.JOB_ID, Notifications.job(ctx, text))
                } catch (e: SecurityException) {
                }
                return
            }
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, JobService::class.java).putExtra(EXTRA_TEXT, text))
            } catch (e: Exception) {
                // запуск из фона запрещён (Android 12+) — работа продолжится, просто без уведомления
            }
        }

        fun stop(ctx: Context) {
            if (!active) return
            runCatching { ctx.stopService(Intent(ctx, JobService::class.java)) }
        }
    }
}
