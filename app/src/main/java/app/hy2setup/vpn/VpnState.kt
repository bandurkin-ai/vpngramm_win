package app.hy2setup.vpn

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Состояние встроенного клиента — общее для службы, экрана и плитки быстрых настроек. */
object VpnState {
    enum class Status { OFF, CONNECTING, ON, STOPPING, ERROR }

    var status by mutableStateOf(Status.OFF)
        internal set
    /** К какому серверу подключены (домен) и каким протоколом. */
    var server by mutableStateOf("")
        internal set
    var protocol by mutableStateOf("")
        internal set
    /** Когда соединение установлено (для «подключено N мин»). */
    var since by mutableStateOf(0L)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    /** Журнал движка (последние строки). */
    val log = mutableStateListOf<String>()

    internal fun addLog(line: String) {
        log.add(line)
        if (log.size > 400) log.removeRange(0, log.size - 400)
    }

    val active: Boolean get() = status == Status.CONNECTING || status == Status.ON
}
