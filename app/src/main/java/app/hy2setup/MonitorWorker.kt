package app.hy2setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Фоновый контроль серверов Главной: TLS-рукопожатие с сервером так же, как его видит клиент VLESS.
 * Две неудачи подряд — уведомление с предложением заменить сервер.
 */
class MonitorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val profiles = ProfileImport.decodeList(SecretStore(ctx).get("stableProfiles")).filter { it.vps != null }
        val vpn = vpnActive(ctx)
        for (p in profiles) {
            val (ok, reason) = withContext(Dispatchers.IO) { probe(p) }
            val fails = if (ok) 0 else prefs.getInt(failKey(p.fqdn), 0) + 1
            prefs.edit().putString(stateKey(p.fqdn), "${System.currentTimeMillis()}|$ok|$vpn|$reason").putInt(failKey(p.fqdn), fails).apply()
            if (decideAlert(ok, fails, vpn)) Notifications.serverDown(ctx, p.fqdn, reason)
        }
        return Result.success()
    }

    companion object {
        const val PREFS = "monitor"
        private const val WORK = "server-monitor"

        fun failKey(fqdn: String) = "fail:$fqdn"
        fun stateKey(fqdn: String) = "state:$fqdn"

        /** Тревожим после двух неудач подряд (и потом раз в 6 проверок), но не через VPN — он сам может идти через этот сервер. */
        fun decideAlert(ok: Boolean, fails: Int, vpn: Boolean): Boolean = !ok && !vpn && (fails == 2 || (fails > 2 && (fails - 2) % 6 == 0))

        /** Последняя фоновая проверка: (время, успех) или null. */
        fun lastState(ctx: Context, fqdn: String): Pair<Long, Boolean>? =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(stateKey(fqdn), null)?.split("|")?.let {
                val t = it.getOrNull(0)?.toLongOrNull() ?: return null
                t to (it.getOrNull(1) == "true")
            }

        fun vpnActive(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

        /** Проверка, как её видит клиент: TCP + TLS на порт VLESS (или сайт на 443) с SNI маскировки. */
        fun probe(p: StableProfile): Pair<Boolean, String> {
            val ip = try { InetAddress.getAllByName(p.fqdn).firstOrNull { it.address.size == 4 } } catch (e: Exception) { null }
                ?: return false to "Имя ${p.fqdn} не находится в DNS."
            val port = when {
                p.vlessOn -> p.vlessPort
                p.siteListen == ":443" -> 443
                else -> return true to "нет TCP-протокола для проверки"
            }
            val sni = if (p.vlessOn) p.realitySni else p.fqdn
            return try {
                val sock = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                sock.use {
                    it.connect(InetSocketAddress(ip, port), 8_000)
                    it.soTimeout = 8_000
                    it.sslParameters = it.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                    it.startHandshake()
                }
                true to "OK"
            } catch (e: java.net.SocketTimeoutException) {
                false to "Сервер не отвечает на порту $port (похоже на блокировку или сервер выключен)."
            } catch (e: java.net.ConnectException) {
                false to "Порт $port закрыт — служба не работает."
            } catch (e: javax.net.ssl.SSLException) {
                false to "TLS-рукопожатие на порту $port не прошло (${e.message?.take(60)})."
            } catch (e: Exception) {
                false to "Ошибка проверки: ${e.message?.take(80)}"
            }
        }

        /** Включает (часы > 0) или выключает фоновый контроль. */
        fun schedule(ctx: Context, hours: Int) {
            val wm = WorkManager.getInstance(ctx)
            if (hours <= 0) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<MonitorWorker>(hours.toLong(), TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
