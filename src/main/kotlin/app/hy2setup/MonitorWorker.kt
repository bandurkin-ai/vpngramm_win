package app.hy2setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Контроль серверов Главной: TLS-рукопожатие с сервером так же, как его видит клиент VLESS.
 * Две неудачи подряд — уведомление с предложением заменить сервер.
 * На Windows проверки идут, пока приложение запущено (при включённом контроле окно сворачивается в трей).
 */
object MonitorWorker {
    private val prefs by lazy { Prefs("monitor") }

    suspend fun checkAll(secrets: SecretStore) {
        val profiles = ProfileImport.decodeList(secrets.get("stableProfiles")).filter { it.vps != null }
        val vpn = vpnActive()
        for (p in profiles) {
            val (ok, reason) = withContext(Dispatchers.IO) { probe(p) }
            val fails = if (ok) 0 else prefs.getInt(failKey(p.fqdn), 0) + 1
            prefs.edit().putString(stateKey(p.fqdn), "${System.currentTimeMillis()}|$ok|$vpn|$reason").putInt(failKey(p.fqdn), fails).apply()
            if (decideAlert(ok, fails, vpn)) Notifications.serverDown(p.fqdn, reason)
        }
        prefs.edit().putString("lastRun", System.currentTimeMillis().toString()).apply()
    }

    /** Через сколько делать первую проверку после запуска: не раньше чем через 2 минуты и не позже интервала. */
    fun firstDelayMs(hours: Int): Long {
        val interval = hours * 3_600_000L
        val last = (prefs.getString("lastRun", null)?.toLongOrNull()) ?: 0L
        val left = interval - (System.currentTimeMillis() - last)
        return left.coerceIn(120_000L, interval)
    }

    fun failKey(fqdn: String) = "fail:$fqdn"
    fun stateKey(fqdn: String) = "state:$fqdn"

    /** Тревожим после двух неудач подряд (и потом раз в 6 проверок), но не через VPN — он сам может идти через этот сервер. */
    fun decideAlert(ok: Boolean, fails: Int, vpn: Boolean): Boolean = !ok && !vpn && (fails == 2 || (fails > 2 && (fails - 2) % 6 == 0))

    /** Последняя фоновая проверка: (время, успех) или null. */
    fun lastState(fqdn: String): Pair<Long, Boolean>? =
        prefs.getString(stateKey(fqdn), null)?.split("|")?.let {
            val t = it.getOrNull(0)?.toLongOrNull() ?: return null
            t to (it.getOrNull(1) == "true")
        }

    private val VPN_ADAPTER = Regex("wintun|wireguard|amnezia|tap-windows|openvpn|tun\\d|happ|nekoray|v2ray|hiddify|vpn", RegexOption.IGNORE_CASE)

    /** Включён ли VPN: в Windows это виден по поднятому виртуальному адаптеру (Wintun, TAP, WireGuard и т.п.). */
    fun vpnActive(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { ni ->
            ni.isUp && !ni.isLoopback && ni.inetAddresses.hasMoreElements() &&
                (VPN_ADAPTER.containsMatchIn(ni.displayName.orEmpty()) || VPN_ADAPTER.containsMatchIn(ni.name.orEmpty()))
        }
    }.getOrDefault(false)

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
}
