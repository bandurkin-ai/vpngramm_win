package app.hy2setup.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.hy2setup.MainActivity
import app.hy2setup.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Встроенный VPN-клиент. Служба создаёт TUN-интерфейс (VpnService) и запускает движок vpncore (sing-box) отдельным
 * процессом. Движок обращается к службе через локальный сокет:
 *  - «TUN» — получить дескриптор TUN;
 *  - «PROTECT» + дескриптор сокета — вывести сокет мимо туннеля (VpnService.protect), иначе трафик к серверу
 *    зациклится в туннель;
 *  - «EVENTS» — постоянное соединение: служба присылает сведения о сетях телефона («IFACES {json}») и «STOP».
 * Принимаются только соединения от процессов этого же приложения (проверка UID).
 */
class VpnTunnelService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private var server: LocalServerSocket? = null
    private var engine: java.lang.Process? = null
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var events: OutputStream? = null
    @Volatile private var stopping = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel(null); return START_NOT_STICKY }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                val title = intent.getStringExtra(EXTRA_SERVER).orEmpty()
                if (config == null) { stopSelf(); return START_NOT_STICKY }
                startTunnel(config, title, intent.getStringExtra(EXTRA_PROTOCOL).orEmpty())
            }
            else -> {
                // перезапуск системой («Постоянный VPN»): берём последнюю конфигурацию
                val last = lastConfig(this)
                if (last == null) { stopSelf(); return START_NOT_STICKY }
                startTunnel(last.config, last.server, last.protocol)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // VPN отключили в настройках Android или включили другой VPN
        stopTunnel("VPN отключён системой или другим приложением.")
    }

    override fun onDestroy() {
        stopTunnel(null)
        super.onDestroy()
    }

    private fun post(block: () -> Unit) = main.post(block)

    private fun startTunnel(config: String, title: String, protocol: String) {
        if (engine != null) stopEngine()
        stopping = false
        post {
            VpnState.status = VpnState.Status.CONNECTING
            VpnState.server = title
            VpnState.protocol = protocol
            VpnState.error = null
            VpnState.log.clear()
        }
        try {
            ensureChannel(this)
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, "Подключение к $title…"), type)
        } catch (e: Exception) {
            fail("Android не разрешил запустить VPN в фоне: ${e.message}")
            return
        }
        saveLastConfig(this, config, title, protocol)
        thread(name = "vpn-start") {
            try {
                val socketName = "vpngramm-" + ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val srv = LocalServerSocket(socketName)
                server = srv
                thread(name = "vpn-control", isDaemon = true) { acceptLoop(srv) }
                val cfgFile = File(noBackupFilesDir, "vpncore.json").apply { writeText(config) }
                val bin = File(applicationInfo.nativeLibraryDir, "libvpncore.so")
                if (!bin.exists()) throw IllegalStateException("Движок не найден в приложении (${bin.path}). Нужен телефон с arm64.")
                val p = ProcessBuilder(bin.path, "-socket", socketName, "-config", cfgFile.path)
                    .directory(noBackupFilesDir).redirectErrorStream(true).start()
                engine = p
                thread(name = "vpn-log", isDaemon = true) { readOutput(p) }
                registerNetworks()
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun readOutput(p: java.lang.Process) {
        var lastError: String? = null
        try {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = raw.replace(ANSI, "").trim()
                if (line.isEmpty()) return@forEachLine
                if (line.startsWith("ERROR") || line.contains("FATAL") || line.startsWith("ERROR[")) lastError = line
                post { VpnState.addLog(line) }
            }
        } catch (_: Exception) {
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        if (!stopping) fail(lastError?.let { explain(it) } ?: "Движок остановился (код $code).")
    }

    /** Понятное объяснение частых ошибок движка. */
    private fun explain(line: String): String = when {
        "reality verification failed" in line || "REALITY" in line && "verify" in line ->
            "Сервер не прошёл проверку Reality: ключ в профиле не совпадает с сервером. Нажмите «Проверить» на Главной."
        "connection refused" in line -> "Сервер отказал в соединении: VLESS на нём не запущен."
        "i/o timeout" in line || "timeout" in line -> "Сервер не отвечает (возможно, адрес заблокирован провайдером)."
        else -> line.removePrefix("ERROR").trim()
    }

    private fun acceptLoop(srv: LocalServerSocket) {
        while (!stopping) {
            val s = try { srv.accept() } catch (e: Exception) { return }
            thread(isDaemon = true) { handle(s) }
        }
    }

    private fun handle(s: LocalSocket) {
        try {
            // только наш же процесс-движок (тот же UID)
            if (s.peerCredentials.uid != Process.myUid()) { s.close(); return }
            val input = s.inputStream
            val line = readLine(input)
            when (line) {
                "EVENTS" -> {
                    val out = s.outputStream
                    events = out
                    sendIfaces()
                    while (true) {
                        val ev = readLine(input) ?: break
                        if (ev == "STARTED") post {
                            VpnState.status = VpnState.Status.ON
                            VpnState.since = System.currentTimeMillis()
                            updateNotification("Подключено к ${VpnState.server}")
                        }
                    }
                    events = null
                }
                "TUN" -> {
                    val fd = establish()
                    val out = s.outputStream
                    if (fd == null) {
                        out.write("ERR VPN не создан (нет разрешения?)\n".toByteArray())
                    } else {
                        s.setFileDescriptorsForSend(arrayOf(fd.fileDescriptor))
                        out.write("FD\n".toByteArray())
                        s.setFileDescriptorsForSend(null)
                    }
                    out.flush()
                }
                "PROTECT" -> {
                    val fds: Array<FileDescriptor>? = s.ancillaryFileDescriptors
                    val ok = fds?.firstOrNull()?.let { f ->
                        val pfd = ParcelFileDescriptor.dup(f)
                        val r = protect(pfd.fd)
                        pfd.close()
                        fds.forEach { runCatching { android.system.Os.close(it) } }
                        r
                    } ?: false
                    s.outputStream.write((if (ok) "OK\n" else "ERR\n").toByteArray())
                    s.outputStream.flush()
                }
                else -> s.outputStream.write("ERR\n".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
        }
    }

    /** Строка до \n (сокет читаем побайтно, чтобы не потерять приложенные дескрипторы). */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trim()
            sb.append(b.toChar())
        }
    }

    @Synchronized
    private fun establish(): ParcelFileDescriptor? {
        tun?.let { return it }
        val b = Builder()
            .setSession("vpngramm")
            .setMtu(VpnConfig.MTU)
            .addAddress(VpnConfig.TUN_V4.substringBefore('/'), VpnConfig.TUN_V4.substringAfter('/').toInt())
            .addAddress(VpnConfig.TUN_V6.substringBefore('/'), VpnConfig.TUN_V6.substringAfter('/').toInt())
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(VpnConfig.TUN_DNS)
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val pfd = b.establish()
        tun = pfd
        return pfd
    }

    // ---------- сети телефона ----------

    private fun registerNetworks() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = changed()
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = changed()
            private fun changed() {
                runCatching { setUnderlyingNetworks(cm.activeNetwork?.let { arrayOf(it) }) }
                sendIfaces()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    /** Сведения о сетях для движка: интерфейсы и основная сеть (по ней sing-box следит за сменой Wi-Fi/мобильной). */
    private fun sendIfaces() {
        val out = events ?: return
        val json = runCatching { ifacesJson(this) }.getOrNull() ?: return
        runCatching { synchronized(out) { out.write("IFACES $json\n".toByteArray()); out.flush() } }
    }

    // ---------- остановка ----------

    private fun fail(message: String) {
        post {
            VpnState.error = message
            VpnState.status = VpnState.Status.ERROR
            VpnState.addLog("ОШИБКА: $message")
        }
        stopTunnel(message)
    }

    private fun stopEngine() {
        stopping = true
        events?.let { o -> runCatching { synchronized(o) { o.write("STOP\n".toByteArray()); o.flush() } } }
        engine?.let { p ->
            runCatching { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroy() }
        }
        engine = null
        runCatching { server?.close() }
        server = null
        runCatching { tun?.close() }
        tun = null
    }

    @Synchronized
    private fun stopTunnel(error: String?) {
        thread(name = "vpn-stop") {
            stopEngine()
            netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
            netCallback = null
            post {
                if (error == null) {
                    VpnState.status = VpnState.Status.OFF
                } else if (VpnState.status != VpnState.Status.ERROR) {
                    VpnState.error = error; VpnState.status = VpnState.Status.ERROR
                }
                VpnState.since = 0
                runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(this, text)) }
    }

    companion object {
        const val ACTION_START = "app.hy2setup.vpn.START"
        const val ACTION_STOP = "app.hy2setup.vpn.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_SERVER = "server"
        const val EXTRA_PROTOCOL = "protocol"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 7
        /** Цветовые коды терминала в журнале sing-box. */
        private val ANSI = Regex("\u001B\\[[0-9;]*m")

        fun start(ctx: Context, config: String, server: String, protocol: String) {
            val i = Intent(ctx, VpnTunnelService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config).putExtra(EXTRA_SERVER, server).putExtra(EXTRA_PROTOCOL, protocol)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, VpnTunnelService::class.java).setAction(ACTION_STOP)) }
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            ctx.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        }

        fun notification(ctx: Context, text: String): Notification {
            val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 2, Intent(ctx, VpnTunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_vpn)
                .setContentTitle("vpngramm VPN")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "Отключить", stop)
                .build()
        }

        /** Последняя конфигурация — для плитки и перезапуска системой. Хранится в закрытой папке приложения. */
        private fun lastFile(ctx: Context) = File(ctx.noBackupFilesDir, "vpn-last.json")

        fun saveLastConfig(ctx: Context, config: String, server: String, protocol: String) {
            runCatching { lastFile(ctx).writeText(JSONObject().put("config", config).put("server", server).put("protocol", protocol).toString()) }
        }

        /** Последнее подключение: конфигурация, сервер и протокол. */
        data class Last(val config: String, val server: String, val protocol: String)

        fun lastConfig(ctx: Context): Last? = runCatching {
            val o = JSONObject(lastFile(ctx).readText())
            Last(o.getString("config"), o.optString("server"), o.optString("protocol", "VLESS"))
        }.getOrNull()

        /** Интерфейсы телефона (кроме нашего туннеля) и номер основной сети. */
        fun ifacesJson(ctx: Context): String {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val active = cm?.activeNetwork
            val lp = active?.let { cm.getLinkProperties(it) }
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val defaultName = lp?.interfaceName
            val type = when {
                caps == null -> "other"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            val list = JSONArray()
            var defaultIndex = -1
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                if (!ni.isUp || ni.isLoopback || ni.name.startsWith("tun")) return@forEach
                val addrs = JSONArray()
                ni.interfaceAddresses.forEach { a ->
                    val host = a.address.hostAddress?.substringBefore('%') ?: return@forEach
                    addrs.put("$host/${a.networkPrefixLength}")
                }
                val isDefault = ni.name == defaultName
                if (isDefault) defaultIndex = ni.index
                list.put(JSONObject().put("name", ni.name).put("index", ni.index).put("mtu", runCatching { ni.mtu }.getOrDefault(1500))
                    .put("addresses", addrs).put("type", if (isDefault) type else "other").put("metered", isDefault && metered))
            }
            return JSONObject().put("interfaces", list).put("default", defaultIndex).toString()
        }

        /** IPv4 сервера, найденный до подключения (пока DNS ещё не идёт через туннель). */
        fun resolveIpv4(host: String): String? = runCatching {
            java.net.InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()
    }
}
