package app.hy2setup.ui

import androidx.compose.runtime.Composable
import app.hy2setup.MainViewModel

/**
 * Вкладки VPN в версии для Windows нет: встроенный VPN-клиент есть только в Android (vpnSupported = false,
 * вкладка скрыта в нижней навигации). Если раздел всё же выбран — показывается Главная.
 */
@Composable
fun VpnScreen(vm: MainViewModel) = HomeScreen(vm)
