package app.hy2setup

/**
 * Система сервера, определённая при входе по SSH ([Scripts.osProbe]): дистрибутив, версия, ядро, служба init и
 * менеджер пакетов. Установочные скрипты сами подстраиваются под систему (os-lib.sh); здесь — проверка и подсказки.
 *
 * Поддерживаются: Debian 11, 12, 13 и новее, Ubuntu 22.04, 24.04, 25.x, 26.x и новее, Alpine Linux.
 */
data class LinuxInfo(
    val id: String,
    val version: String,
    val kernel: String,
    /** systemd, openrc или none. */
    val init: String,
    /** apt, apk, dnf, yum или none. */
    val pkg: String,
    val arch: String,
    /** ok — из списка поддерживаемых, old — слишком старая версия, other — другой дистрибутив. */
    val support: String,
    val name: String,
) {
    val isAlpine get() = id == "alpine"

    /** Короткое описание для журнала: «Debian GNU/Linux 12 (bookworm), ядро 6.1.0, systemd, apt». */
    fun describe() = "$name, ядро $kernel, служба: $init, пакеты: $pkg"

    /** Причина, по которой ставить на этот сервер нельзя; null — можно. */
    fun problem(): String? = when {
        support == "old" -> "Система «$name» слишком старая. Поддерживаются Debian 11 и новее, Ubuntu 22.04 и новее, Alpine Linux."
        init == "none" -> "На сервере нет ни systemd, ни OpenRC — службы протоколов поставить не получится. Нужны Debian, Ubuntu или Alpine."
        pkg == "none" -> "На сервере не найден менеджер пакетов (apt или apk). Нужны Debian, Ubuntu или Alpine."
        else -> null
    }

    /** Предупреждения и пояснения, как скрипты подстроятся под систему. */
    fun notes(): List<String> = buildList {
        if (support == "other") add("ПРЕДУПРЕЖДЕНИЕ: $name не из списка проверенных (Debian 11+, Ubuntu 22.04+, Alpine) — установка может не получиться.")
        if (isAlpine) {
            add("Alpine: службы OpenRC; Hysteria2, Xray и mtg ставятся готовыми программами с GitHub, AmneziaWG — программная реализация amneziawg-go (нужен TUN).")
        }
        val (maj, min) = kernelVersion()
        if (id == "debian" && version.substringBefore('.') == "11") {
            add("Debian 11 (ядро $kernel): AmneziaWG соберётся для этого ядра без обновления; Hysteria2, Xray и mtg работают как обычно.")
        }
        if (!isAlpine && (maj < 6 || (maj == 6 && min < 7))) {
            add("Ядро $kernel старее 6.7" + if (id == "debian" && version.substringBefore('.') == "12") " — для AmneziaWG приложение поставит новое ядро и перезагрузит сервер." else " — AmneziaWG 3.1 на нём проверялся мало.")
        }
    }

    /** Мажорная и минорная версии ядра. */
    fun kernelVersion(): Pair<Int, Int> {
        val p = kernel.split('.', '-')
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0)
    }

    companion object {
        /** Разбор строки «##OS|id|версия|ядро|init|пакеты|архитектура|поддержка|название». */
        fun parse(line: String): LinuxInfo? {
            if (!line.startsWith(Scripts.OS_MARKER)) return null
            val f = line.removePrefix(Scripts.OS_MARKER).trim().split("|", limit = 8)
            if (f.size < 8) return null
            return LinuxInfo(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7])
        }
    }
}
