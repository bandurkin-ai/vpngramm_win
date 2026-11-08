package app.hy2setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** Канал и уведомление о ходе долгой работы. */
object Notifications {
    const val CH_JOBS = "jobs"
    const val JOB_ID = 1

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_JOBS, "Работа с сервером", NotificationManager.IMPORTANCE_LOW))
    }

    /** Открывает приложение. */
    fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
}
