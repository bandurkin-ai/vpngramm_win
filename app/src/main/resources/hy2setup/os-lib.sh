# Общий слой для всех скриптов сервера: определение системы (Debian, Ubuntu, Alpine) и её ядра,
# менеджер пакетов, службы. На Alpine (OpenRC) команды systemctl и journalctl эмулируются,
# поэтому остальные скрипты работают без правок для каждой системы.
# Для проверок: OSLIB_OSRELEASE, OSLIB_KERNEL, OSLIB_INIT, OSLIB_INITD подменяют файл os-release, ядро, тип init и каталог служб.

OS_ID=""; OS_VER=""; OS_NAME=""; OS_CODENAME=""; OS_FAMILY=""; OS_MAJOR=0
KVER=""; KMAJ=0; KMIN=0; INIT=""; PKG=""; ARCH_GO=""; OS_SUPPORT=""

os_detect() {
    local f="${OSLIB_OSRELEASE:-/etc/os-release}" like=""
    OS_ID=""; OS_VER=""; OS_NAME=""; OS_CODENAME=""; like=""
    if [ -r "$f" ]; then
        OS_ID=$(. "$f" >/dev/null 2>&1; echo "${ID:-}")
        OS_VER=$(. "$f" >/dev/null 2>&1; echo "${VERSION_ID:-}")
        OS_NAME=$(. "$f" >/dev/null 2>&1; echo "${PRETTY_NAME:-}")
        OS_CODENAME=$(. "$f" >/dev/null 2>&1; echo "${VERSION_CODENAME:-}")
        like=$(. "$f" >/dev/null 2>&1; echo " ${ID_LIKE:-} ")
    fi
    # семейство: производные (Mint, Raspbian, Kali…) считаются по ID_LIKE
    OS_FAMILY=other
    case "$OS_ID" in
        debian|ubuntu|alpine) OS_FAMILY=$OS_ID;;
        *) case "$like" in *" ubuntu "*) OS_FAMILY=ubuntu;; *" debian "*) OS_FAMILY=debian;; esac;;
    esac
    OS_MAJOR=$(echo "$OS_VER" | sed 's/[^0-9].*//')
    [ -n "$OS_MAJOR" ] || OS_MAJOR=0
    [ -n "$OS_NAME" ] || OS_NAME="${OS_ID:-неизвестная система}"

    KVER="${OSLIB_KERNEL:-$(uname -r 2>/dev/null)}"
    KMAJ=$(echo "$KVER" | cut -d. -f1 | tr -cd '0-9'); KMAJ=${KMAJ:-0}
    KMIN=$(echo "$KVER" | cut -d. -f2 | tr -cd '0-9'); KMIN=${KMIN:-0}

    if [ -n "${OSLIB_INIT:-}" ]; then INIT="$OSLIB_INIT"
    elif [ -d /run/systemd/system ]; then INIT=systemd
    elif command -v rc-service >/dev/null 2>&1; then INIT=openrc
    elif command -v systemctl >/dev/null 2>&1; then INIT=systemd
    else INIT=none; fi

    if command -v apt-get >/dev/null 2>&1; then PKG=apt
    elif command -v apk >/dev/null 2>&1; then PKG=apk
    elif command -v dnf >/dev/null 2>&1; then PKG=dnf
    elif command -v yum >/dev/null 2>&1; then PKG=yum
    else PKG=none; fi

    case "$(uname -m 2>/dev/null)" in
        x86_64|amd64) ARCH_GO=amd64;;
        aarch64|arm64) ARCH_GO=arm64;;
        armv7*|armv8l) ARCH_GO=armv7;;
        armv6*) ARCH_GO=armv6;;
        i386|i686) ARCH_GO=386;;
        s390x) ARCH_GO=s390x;;
        riscv64) ARCH_GO=riscv64;;
        loongarch64) ARCH_GO=loong64;;
        *) ARCH_GO="";;
    esac

    # Поддерживаются: Debian 11+ (testing/sid без номера — новейшая), Ubuntu 22.04+, Alpine
    OS_SUPPORT=other
    case "$OS_FAMILY" in
        debian)
            if [ "$OS_ID" = debian ] && [ "$OS_MAJOR" -gt 0 ] && [ "$OS_MAJOR" -lt 11 ]; then OS_SUPPORT=old; else OS_SUPPORT=ok; fi;;
        ubuntu)
            if [ "$OS_ID" = ubuntu ] && [ "$OS_MAJOR" -gt 0 ] && [ "$OS_MAJOR" -lt 22 ]; then OS_SUPPORT=old; else OS_SUPPORT=ok; fi;;
        alpine) OS_SUPPORT=ok;;
    esac
}

# Строка «##OS|…» для приложения: id|версия|ядро|init|пакеты|архитектура|поддержка|название
os_marker() { echo "##OS|${OS_ID:-?}|${OS_VER:-?}|${KVER:-?}|${INIT:-?}|${PKG:-?}|${ARCH_GO:-?}|${OS_SUPPORT:-?}|${OS_NAME}"; }

# Проверка системы перед установкой: печатает сведения и завершает скрипт, если система не подходит.
os_require() {
    step "Проверка системы"
    echo "${OS_NAME}, ядро ${KVER}, служба: ${INIT}, пакеты: ${PKG}"
    os_marker
    if [ "$OS_SUPPORT" = old ]; then
        echo "ОШИБКА: ${OS_NAME} слишком старая. Поддерживаются Debian 11 и новее, Ubuntu 22.04 и новее, Alpine Linux."; exit 1
    fi
    if [ "$INIT" = none ]; then
        echo "ОШИБКА: нужна система со службами systemd (Debian, Ubuntu) или OpenRC (Alpine)."; exit 1
    fi
    if [ "$PKG" = none ]; then
        echo "ОШИБКА: не найден менеджер пакетов (apt или apk)."; exit 1
    fi
    if [ "$OS_SUPPORT" = other ]; then
        echo "ПРЕДУПРЕЖДЕНИЕ: ${OS_NAME} не из списка проверенных (Debian 11+, Ubuntu 22.04+, Alpine) — установка может не получиться."
    fi
}

pkg_install() {
    case "$PKG" in
        apt) apt-get -o DPkg::Lock::Timeout=600 update -qq && apt-get -o DPkg::Lock::Timeout=600 install -y -qq "$@";;
        apk) apk add --no-cache -q "$@";;
        dnf) dnf install -y -q "$@";;
        yum) yum install -y -q "$@";;
        *) echo "ОШИБКА: не найден менеджер пакетов, установите вручную: $*"; exit 1;;
    esac
}

# Alpine: bash и утилиты, на которые рассчитаны остальные скрипты (в минимальном образе есть только busybox)
os_base() {
    [ "$INIT" = openrc ] && [ "$PKG" = apk ] || return 0
    [ -f /var/lib/vpngramm/base-ok ] && return 0
    apk add --no-cache -q bash curl ca-certificates openssl iproute2 grep coreutils sed gawk util-linux procps tar unzip >/dev/null 2>&1 || true
    apk add --no-cache -q iproute2-ss >/dev/null 2>&1 || true
    mkdir -p /var/lib/vpngramm && : > /var/lib/vpngramm/base-ok
}

# ---------------------------------------------------------------------------------------------
#  OpenRC (Alpine): systemctl и journalctl для уже написанных скриптов
# ---------------------------------------------------------------------------------------------
_svc_name() { local n="${1%.service}"; echo "${n//@/-}"; }

_systemctl_openrc() {
    local a cmd="" quiet=0 now=0 units=() skip=0
    for a in "$@"; do
        if [ "$skip" = 1 ]; then skip=0; continue; fi
        case "$a" in
            -q|--quiet) quiet=1;;
            --now) now=1;;
            -p|-n|-u|--property|--since|--on-active|--unit) skip=1;;
            -*) ;;
            *) if [ -z "$cmd" ]; then cmd="$a"; else units+=("$a"); fi;;
        esac
    done
    local u n rc=0
    case "$cmd" in
        daemon-reload|daemon-reexec|reset-failed|mask|unmask|link|preset) return 0;;
        show) echo 0; return 0;;
        reboot) reboot; return 0;;
        poweroff) poweroff; return 0;;
        enable)
            for u in "${units[@]}"; do n=$(_svc_name "$u")
                [ -f "${OSLIB_INITD:-/etc/init.d}/$n" ] || { rc=1; continue; }
                rc-update add "$n" default >/dev/null 2>&1
                [ "$now" = 1 ] && rc-service "$n" start >/dev/null 2>&1
            done; return $rc;;
        disable)
            for u in "${units[@]}"; do n=$(_svc_name "$u")
                [ "$now" = 1 ] && rc-service "$n" stop >/dev/null 2>&1
                rc-update del "$n" default >/dev/null 2>&1
            done; return 0;;
        start|stop|restart|reload)
            for u in "${units[@]}"; do n=$(_svc_name "$u")
                [ -f "${OSLIB_INITD:-/etc/init.d}/$n" ] || { echo "Служба $n не найдена" >&2; rc=1; continue; }
                if [ "$cmd" = reload ] || [ "$cmd" = restart ]; then
                    rc-service "$n" "$cmd" >/dev/null 2>&1 || { [ "$cmd" = restart ] && rc-service "$n" start >/dev/null 2>&1; } || rc=1
                else
                    rc-service "$n" "$cmd" >/dev/null 2>&1 || rc=1
                fi
            done; return $rc;;
        is-active|status)
            for u in "${units[@]}"; do n=$(_svc_name "$u")
                if [ -f "${OSLIB_INITD:-/etc/init.d}/$n" ] && rc-service "$n" status >/dev/null 2>&1; then
                    [ "$quiet" = 1 ] || echo active
                else
                    [ "$quiet" = 1 ] || echo inactive; rc=3
                fi
            done; return $rc;;
        is-enabled)
            for u in "${units[@]}"; do n=$(_svc_name "$u")
                rc-update show default 2>/dev/null | awk '{print $1}' | grep -qx "$n" || rc=1
            done; return $rc;;
        cat)
            for u in "${units[@]}"; do n=$(_svc_name "$u"); [ -f "${OSLIB_INITD:-/etc/init.d}/$n" ] || rc=1; done; return $rc;;
        *) return 1;;
    esac
}

_journalctl_openrc() {
    local unit="" n=200 a prev=""
    for a in "$@"; do
        case "$prev" in -u) unit="$a";; -n) n="$a";; esac
        prev="$a"
    done
    local name; name=$(_svc_name "${unit:-messages}")
    if [ -f "/var/log/$name.log" ]; then tail -n "$n" "/var/log/$name.log"
    elif [ "$name" = sshd ] || [ "$name" = ssh ] || [ "$name" = fail2ban ]; then tail -n "$n" /var/log/messages 2>/dev/null | grep -i "$name" || true
    else return 0; fi
}

# Служба OpenRC: openrc_service ИМЯ ОПИСАНИЕ ПРОГРАММА АРГУМЕНТЫ…  (OR_DELAY — пауза перед перезапуском, с)
openrc_service() {
    local name="$1" desc="$2" cmd="$3"; shift 3
    cat > "${OSLIB_INITD:-/etc/init.d}/$name" <<ORC
#!/sbin/openrc-run
description="$desc"
command="$cmd"
command_args="$*"
supervisor=supervise-daemon
respawn_delay=${OR_DELAY:-3}
respawn_max=0
output_log="/var/log/$name.log"
error_log="/var/log/$name.log"
rc_ulimit="-n 65536"
${OR_ENV:-}
depend() {
    need net
}
ORC
    chmod 755 "${OSLIB_INITD:-/etc/init.d}/$name"
    # журналы служб не раздуваются: раз в сутки обрезаются до 5 МБ
    if [ -d /etc/periodic/daily ] && [ ! -f /etc/periodic/daily/vpngramm-logs ]; then
        printf '#!/bin/sh\nfor f in /var/log/hysteria-server.log /var/log/xray.log /var/log/mtg.log /var/log/xray/access.log; do\n    [ -f "$f" ] && [ "$(stat -c %%s "$f")" -gt 5242880 ] && tail -c 1048576 "$f" > "$f.tmp" && cat "$f.tmp" > "$f" && rm -f "$f.tmp"\ndone\nexit 0\n' > /etc/periodic/daily/vpngramm-logs
        chmod 755 /etc/periodic/daily/vpngramm-logs
    fi
}

# Hysteria2 без systemd (Alpine): готовый бинарник с GitHub и служба OpenRC (официальный get.hy2.sh требует systemd)
hy2_openrc_install() {
    local arch="$ARCH_GO" tmp=""
    case "$arch" in armv7|armv6) arch=arm;; esac
    [ -n "$arch" ] || { echo "ОШИБКА: неподдерживаемая архитектура $(uname -m)"; return 1; }
    tmp=$(mktemp)
    curl -fsSL --retry 3 --retry-delay 3 "https://github.com/apernet/hysteria/releases/latest/download/hysteria-linux-$arch" -o "$tmp" \
        || { rm -f "$tmp"; return 1; }
    install -m 755 "$tmp" /usr/local/bin/hysteria && rm -f "$tmp"
    /usr/local/bin/hysteria version 2>/dev/null | head -2
    mkdir -p /etc/hysteria /var/lib/hysteria
    OR_DELAY=30 openrc_service hysteria-server "Hysteria2 server" /usr/local/bin/hysteria server --config /etc/hysteria/config.yaml
}

# Xray без systemd (Alpine): архив с GitHub (бинарник и geo-файлы) и служба OpenRC. $1 — тег версии (может быть пустым)
xray_openrc_install() {
    local tag="$1" a="" url="" tmp=""
    case "$ARCH_GO" in
        amd64) a=64;; 386) a=32;; arm64) a=arm64-v8a;; armv7) a=arm32-v7a;; armv6) a=arm32-v6;;
        s390x) a=s390x;; riscv64) a=riscv64;; loong64) a=loong64;;
        *) echo "ОШИБКА: неподдерживаемая архитектура $(uname -m)"; return 1;;
    esac
    if [ -n "$tag" ]; then url="https://github.com/XTLS/Xray-core/releases/download/$tag/Xray-linux-$a.zip"
    else url="https://github.com/XTLS/Xray-core/releases/latest/download/Xray-linux-$a.zip"; fi
    tmp=$(mktemp -d)
    if ! { curl -fsSL --retry 3 --retry-delay 3 "$url" -o "$tmp/x.zip" && unzip -oq "$tmp/x.zip" -d "$tmp"; }; then rm -rf "$tmp"; return 1; fi
    install -m 755 "$tmp/xray" /usr/local/bin/xray
    mkdir -p /usr/local/share/xray /usr/local/etc/xray /var/log/xray
    cp -f "$tmp/geoip.dat" "$tmp/geosite.dat" /usr/local/share/xray/ 2>/dev/null || true
    rm -rf "$tmp"
    OR_ENV='export XRAY_LOCATION_ASSET=/usr/local/share/xray' openrc_service xray "Xray" /usr/local/bin/xray run -config /usr/local/etc/xray/config.json
}

# Отложенный запуск команды: sched_run ИМЯ СЕКУНДЫ КОМАНДА… (на systemd — таймер, на OpenRC — фоновый процесс)
sched_run() {
    local name="$1" sec="$2"; shift 2
    if [ "$INIT" = systemd ] && command -v systemd-run >/dev/null 2>&1; then
        systemctl stop "$name.timer" "$name.service" 2>/dev/null || true
        systemctl reset-failed "$name.service" 2>/dev/null || true
        systemd-run --quiet --on-active="$sec" --unit="$name" "$@"
    else
        sched_cancel "$name"
        setsid nohup sh -c 'echo $$ > /run/'"$name"'.pid; sleep '"$sec"'; rm -f /run/'"$name"'.pid; exec "$@"' sh "$@" >/dev/null 2>&1 < /dev/null &
    fi
}
sched_cancel() {
    local name="$1"
    if [ "$INIT" = systemd ]; then
        systemctl stop "$name.timer" "$name.service" 2>/dev/null || true
    elif [ -f "/run/$name.pid" ]; then
        kill "$(cat "/run/$name.pid")" 2>/dev/null || true; rm -f "/run/$name.pid"
    fi
}

os_detect
if [ "$INIT" = openrc ]; then
    systemctl() { _systemctl_openrc "$@"; }
    journalctl() { _journalctl_openrc "$@"; }
    # дочерние bash (фоновые задачи, «живая проверка») получают те же команды без повторной загрузки слоя
    export -f systemctl journalctl _systemctl_openrc _journalctl_openrc _svc_name
    os_base
fi
