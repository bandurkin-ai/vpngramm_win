package app.hy2setup.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.hy2setup.MainActivity

/** Плитка в шторке быстрых настроек: включить или выключить VPN одним касанием (последний сервер). */
class VpnTileService : TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (VpnState.active) {
            VpnTunnelService.stop(this)
        } else {
            val last = VpnTunnelService.lastConfig(this)
            // нужен разрешённый VPN и хотя бы одно подключение из приложения — иначе открываем приложение
            if (last == null || VpnService.prepare(this) != null) {
                openApp()
                return
            }
            runCatching { VpnTunnelService.start(this, last.config, last.server, last.protocol) }.onFailure { openApp() }
        }
        refresh()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (VpnState.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (VpnState.active) VpnState.server.ifEmpty { "Подключено" } else "Выключен"
        t.updateTile()
    }
}
