package app.hy2setup.ui

import android.app.Activity
import android.net.TrafficStats
import android.net.VpnService
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hy2setup.LogSummary
import app.hy2setup.MainViewModel
import app.hy2setup.StableProfile
import app.hy2setup.vpn.VpnConfig
import app.hy2setup.vpn.VpnProtocol
import app.hy2setup.vpn.VpnState
import kotlinx.coroutines.delay

/** Встроенный VPN-клиент: подключение к выбранному серверу одной кнопкой. */
@Composable
internal fun VpnPanel(vm: MainViewModel, profile: StableProfile, proto: VpnProtocol) {
    val ctx = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.vpnConnect() else vm.vpnError = "Без разрешения Android не даёт включить VPN."
    }
    val status = VpnState.status
    val here = VpnState.server == profile.fqdn
    val on = status == VpnState.Status.ON && here
    val connecting = status == VpnState.Status.CONNECTING && here

    // время подключения и скорость (весь трафик движка идёт от UID приложения)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var down by remember { mutableLongStateOf(0L) }
    var up by remember { mutableLongStateOf(0L) }
    LaunchedEffect(on) {
        var rx = TrafficStats.getUidRxBytes(Process.myUid())
        var tx = TrafficStats.getUidTxBytes(Process.myUid())
        while (on) {
            delay(1000)
            now = System.currentTimeMillis()
            val r = TrafficStats.getUidRxBytes(Process.myUid()); val t = TrafficStats.getUidTxBytes(Process.myUid())
            down = (r - rx).coerceAtLeast(0); up = (t - tx).coerceAtLeast(0); rx = r; tx = t
        }
    }

    val look = lookOf(
        when {
            on -> LogSummary.State.OK
            connecting -> LogSummary.State.RUNNING
            status == VpnState.Status.ERROR && here -> LogSummary.State.ERROR
            else -> LogSummary.State.INFO
        },
    )
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = look.container, contentColor = look.content),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Shield, null, Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            on -> "VPN включён"
                            connecting -> "Подключение…"
                            else -> "VPN выключен"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        when {
                            on -> "${profile.fqdn} · ${VpnState.protocol.ifEmpty { proto.title }} · ${duration(now - VpnState.since)}"
                            status.let { it == VpnState.Status.ON || it == VpnState.Status.CONNECTING } && !here -> "Сейчас подключено к ${VpnState.server}"
                            else -> "${proto.title} к ${profile.fqdn}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (on) Text("↓ ${speed(down)}   ↑ ${speed(up)}", style = MaterialTheme.typography.titleMedium)
            val err = vm.vpnError ?: VpnState.error?.takeIf { status == VpnState.Status.ERROR && here }
            if (err != null) Text(err, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    if (on || connecting) {
                        vm.vpnDisconnect()
                    } else {
                        vm.vpnError = null
                        val i = runCatching { VpnService.prepare(ctx) }.getOrNull()
                        if (i != null) permission.launch(i) else vm.vpnConnect()
                    }
                },
                enabled = proto in VpnConfig.available(profile) || on || connecting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = if (on || connecting) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
                ) else ButtonDefaults.buttonColors(),
            ) { Text(if (on || connecting) "Отключить" else "Подключить", style = MaterialTheme.typography.titleMedium) }
            if (VpnState.log.isNotEmpty()) TextButton(onClick = { showLog = true }) { Text("Журнал VPN") }
        }
    }
    if (showLog) LogSheet("Журнал VPN", VpnState.log, connecting) { showLog = false }
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

private fun speed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_000_000 -> "%.1f МБ/с".format(bytesPerSec / 1e6)
    bytesPerSec >= 1_000 -> "%.0f КБ/с".format(bytesPerSec / 1e3)
    else -> "$bytesPerSec Б/с"
}
