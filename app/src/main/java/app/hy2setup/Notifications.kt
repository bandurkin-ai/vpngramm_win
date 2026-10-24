package app.hy2setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Каналы и уведомления: ход долгой работы и тревоги контроля серверов. */
object Notifications {
    const val CH_JOBS = "jobs"
    const val CH_MONITOR = "monitor"
    const val EXTRA_FQDN = "fqdn"
    const val JOB_ID = 1

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_JOBS, "Работа с сервером", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Контроль серверов", NotificationManager.IMPORTANCE_HIGH))
    }

    /** Открывает приложение; с [fqdn] — Главную с этим сервером и шторкой «Заменить сервер». */
    fun openApp(ctx: Context, fqdn: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (fqdn != null) i.putExtra(EXTRA_FQDN, fqdn)
        return PendingIntent.getActivity(ctx, fqdn?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun job(ctx: Context, text: String) = NotificationCompat.Builder(ctx, CH_JOBS)
        .setSmallIcon(R.drawable.ic_stat_vpn)
        .setContentTitle("vpngramm работает")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(ctx))
        .build()

    fun serverDown(ctx: Context, fqdn: String, reason: String) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("$fqdn недоступен")
            .setContentText("$reason Заменить сервер?")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason Нажмите, чтобы перенести его на другой VPS — ссылки не изменятся."))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, fqdn))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(fqdn.hashCode(), n)
        } catch (e: SecurityException) {
            // нет разрешения на уведомления
        }
    }
}
