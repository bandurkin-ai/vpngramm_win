# Запасной путь установки AmneziaWG, когда репозиторий Amnezia (Launchpad) недоступен с сервера:
# модуль ядра (DKMS) и awg/awg-quick собираются из исходников Amnezia на GitHub.
# Версии зафиксированы: тег клонируется и сверяется с ожидаемым коммитом — подменённый код не соберётся.
# Нужны: git, dkms, build-essential и заголовки ядра (из обычного репозитория дистрибутива) и awg-repo.sh (hosts).
AWG_GH_BASE=${AWG_GH_BASE:-https://github.com/amnezia-vpn}
AWG_GH_DIR=${AWG_GH_DIR:-/usr/local/src/vpngramm-awg}
AWG_GH_KMOD_TAG=v3.1.20260906
AWG_GH_KMOD_SHA=4569c4c67f3a57414969260cafbbd04694fbaae0
AWG_GH_TOOLS_TAG=v3.1.20260812
AWG_GH_TOOLS_SHA=ee0f0a9aa34ff0a0da4b3433b9512781cfe02843
AWG_DKMS_VER=1.0.0

# клонировать $1 (репозиторий) с тегом $2 и проверить, что это коммит $3
awg_gh_fetch() {
    local name="$1" tag="$2" sha="$3" i=1 head="" err=""
    rm -rf "${AWG_GH_DIR:?}/$name"
    while :; do
        err=$(git -c advice.detachedHead=false clone -q --depth 1 --branch "$tag" "$AWG_GH_BASE/$name.git" "$AWG_GH_DIR/$name" 2>&1) && break
        rm -rf "${AWG_GH_DIR:?}/$name"
        if [ "$i" -ge 3 ]; then
            echo "ОШИБКА: не удалось скачать $name с GitHub: $(echo "$err" | tail -1 | cut -c1-200)"
            return 1
        fi
        echo "GitHub пока недоступен ($(echo "$err" | tail -1 | cut -c1-120)) — повтор через $AWG_PAUSE с"
        sleep "$AWG_PAUSE"
        i=$((i + 1))
    done
    head=$(git -C "$AWG_GH_DIR/$name" rev-parse HEAD 2>/dev/null) || true
    if [ "$head" != "$sha" ]; then
        echo "ОШИБКА: $name $tag на GitHub — коммит ${head:-?}, а ожидался $sha. Собирать не буду."
        return 1
    fi
    echo "Скачан $name $tag (${sha:0:12})"
}

awg_github_install() {
    local kver="${1:-$(uname -r)}" log=""
    echo "Собираю AmneziaWG из исходников Amnezia на GitHub: модуль $AWG_GH_KMOD_TAG, утилиты $AWG_GH_TOOLS_TAG"
    awg_hosts_pin github.com || true
    mkdir -p "$AWG_GH_DIR"
    awg_gh_fetch amneziawg-linux-kernel-module "$AWG_GH_KMOD_TAG" "$AWG_GH_KMOD_SHA" || return 1
    awg_gh_fetch amneziawg-tools "$AWG_GH_TOOLS_TAG" "$AWG_GH_TOOLS_SHA" || return 1

    # модуль ядра через DKMS — пересоберётся сам при обновлении ядра
    dkms remove -m amneziawg -v "$AWG_DKMS_VER" --all >/dev/null 2>&1 || true
    rm -rf "/usr/src/amneziawg-$AWG_DKMS_VER"
    make -s -C "$AWG_GH_DIR/amneziawg-linux-kernel-module/src" DKMSDIR="/usr/src/amneziawg-$AWG_DKMS_VER" dkms-install >/dev/null || {
        echo "ОШИБКА: не удалось подготовить исходники модуля для DKMS"; return 1; }
    dkms add -m amneziawg -v "$AWG_DKMS_VER" >/dev/null || { echo "ОШИБКА: dkms add amneziawg не прошёл"; return 1; }
    if ! dkms build -m amneziawg -v "$AWG_DKMS_VER" -k "$kver" >/dev/null 2>&1; then
        echo "ОШИБКА: модуль AmneziaWG не собрался под ядро $kver. Конец журнала сборки:"
        log="/var/lib/dkms/amneziawg/$AWG_DKMS_VER/build/make.log"
        [ -f "$log" ] && tail -n 15 "$log" | cut -c1-300
        return 1
    fi
    dkms install -m amneziawg -v "$AWG_DKMS_VER" -k "$kver" --force >/dev/null 2>&1 \
        || dkms install -m amneziawg -v "$AWG_DKMS_VER" -k "$kver" >/dev/null \
        || { echo "ОШИБКА: dkms install amneziawg не прошёл"; return 1; }
    echo "Модуль ядра собран и установлен (DKMS)"

    # awg, awg-quick и служба awg-quick@
    make -s -C "$AWG_GH_DIR/amneziawg-tools/src" -j"$(nproc 2>/dev/null || echo 2)" >/dev/null || {
        echo "ОШИБКА: утилиты AmneziaWG не собрались"; return 1; }
    make -s -C "$AWG_GH_DIR/amneziawg-tools/src" install PREFIX=/usr SYSCONFDIR=/etc \
        WITH_WGQUICK=yes WITH_SYSTEMDUNITS=yes SYSTEMDUNITDIR=/lib/systemd/system >/dev/null || {
        echo "ОШИБКА: утилиты AmneziaWG не установились"; return 1; }
    systemctl daemon-reload 2>/dev/null || true
    echo "Утилиты awg и awg-quick установлены"
}
