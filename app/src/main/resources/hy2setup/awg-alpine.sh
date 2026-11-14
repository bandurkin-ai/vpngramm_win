# AmneziaWG на Alpine Linux: в Alpine нет модуля ядра AmneziaWG, поэтому ставится userspace-реализация
# amneziawg-go (не зависит от ядра и работает и в контейнерах с TUN) и утилиты awg/awg-quick из исходников Amnezia.
# Версии зафиксированы: тег клонируется и сверяется с ожидаемым коммитом (awg_gh_fetch из awg-github.sh).
AWG_ALPINE_GO_TAG=v3.1.20260828
AWG_ALPINE_GO_SHA=b5928efb6ca19f0153958460c3d141f04abc5c2e

awg_alpine_install() {
    step "AmneziaWG на Alpine: amneziawg-go (userspace) — несколько минут"
    echo "Alpine: ядро ${KVER}. Модуля ядра AmneziaWG для Alpine нет — собираю amneziawg-go и утилиты из исходников Amnezia."
    apk add --no-cache -q git make gcc musl-dev linux-headers go iptables ip6tables bash iproute2 >/dev/null || {
        echo "ОШИБКА: не удалось поставить пакеты для сборки (apk)."; exit 1; }
    modprobe tun 2>/dev/null || true
    if [ ! -c /dev/net/tun ]; then
        mkdir -p /dev/net && mknod /dev/net/tun c 10 200 2>/dev/null && chmod 600 /dev/net/tun || true
    fi
    [ -c /dev/net/tun ] || { echo "ОШИБКА: у сервера нет устройства /dev/net/tun — включите TUN/TAP в панели хостинга."; exit 1; }
    grep -qx tun /etc/modules 2>/dev/null || echo tun >> /etc/modules
    awg_hosts_pin github.com || true
    mkdir -p "$AWG_GH_DIR"
    awg_gh_fetch amneziawg-go "$AWG_ALPINE_GO_TAG" "$AWG_ALPINE_GO_SHA" || exit 1
    awg_gh_fetch amneziawg-tools "$AWG_GH_TOOLS_TAG" "$AWG_GH_TOOLS_SHA" || exit 1
    echo "Сборка amneziawg-go (Go скачает нужные модули)…"
    ( cd "$AWG_GH_DIR/amneziawg-go" && CGO_ENABLED=0 GOFLAGS=-buildvcs=false GOTOOLCHAIN=auto go build -o /usr/bin/amneziawg-go . ) >/tmp/.awg-go-build.log 2>&1 || {
        echo "ОШИБКА: amneziawg-go не собрался. Конец журнала сборки:"; tail -n 12 /tmp/.awg-go-build.log | cut -c1-300; rm -f /tmp/.awg-go-build.log; exit 1; }
    rm -f /tmp/.awg-go-build.log
    make -s -C "$AWG_GH_DIR/amneziawg-tools/src" -j"$(nproc 2>/dev/null || echo 2)" >/dev/null || { echo "ОШИБКА: утилиты AmneziaWG не собрались"; exit 1; }
    make -s -C "$AWG_GH_DIR/amneziawg-tools/src" install PREFIX=/usr SYSCONFDIR=/etc WITH_WGQUICK=yes WITH_SYSTEMDUNITS=no >/dev/null || {
        echo "ОШИБКА: утилиты AmneziaWG не установились"; exit 1; }
    awg_hosts_cleanup
    cat > /etc/init.d/awg-quick-awg0 <<'ORC'
#!/sbin/openrc-run
description="AmneziaWG awg0 (amneziawg-go)"
export WG_QUICK_USERSPACE_IMPLEMENTATION=amneziawg-go
depend() {
    need net
}
start() {
    ebegin "Starting AmneziaWG awg0"
    modprobe tun 2>/dev/null
    awg-quick down awg0 >/dev/null 2>&1
    awg-quick up awg0 >/var/log/awg-quick-awg0.log 2>&1
    eend $?
}
stop() {
    ebegin "Stopping AmneziaWG awg0"
    awg-quick down awg0 >/dev/null 2>&1
    eend 0
}
ORC
    chmod 755 /etc/init.d/awg-quick-awg0
    echo "Модуль: amneziawg-go $(/usr/bin/amneziawg-go --version 2>/dev/null | head -1), $(awg --version 2>/dev/null | head -1)"
}
