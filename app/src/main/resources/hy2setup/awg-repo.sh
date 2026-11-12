# Подготовка репозитория Amnezia (PPA) перед установкой AmneziaWG.
# apt-get update не падает, если репозиторий недоступен, — поэтому проверяем, что пакеты из PPA действительно видны;
# при сбое повторяем, а если DNS сервера не разрешает имя PPA — узнаём адрес через DNS-over-HTTPS и временно
# прописываем его в hosts (строка с пометкой vpngramm-tmp убирается после установки и при любом выходе).
# Переменные для проверки: AWG_HOSTS (файл hosts), AWG_TRIES (попыток), AWG_PAUSE (пауза между ними, с).
AWG_PPA_HOST=ppa.launchpadcontent.net
AWG_HOSTS=${AWG_HOSTS:-/etc/hosts}
AWG_TRIES=${AWG_TRIES:-6}
AWG_PAUSE=${AWG_PAUSE:-20}

# пакеты из PPA видны apt
awg_ppa_ready() {
    apt-cache policy amneziawg-dkms 2>/dev/null | grep -qE 'Candidate: +[0-9]'
}

# убрать временную строку из hosts
awg_hosts_cleanup() {
    if [ -f "$AWG_HOSTS" ] && grep -q '# vpngramm-tmp$' "$AWG_HOSTS" 2>/dev/null; then
        sed -i '/# vpngramm-tmp$/d' "$AWG_HOSTS" || true
    fi
}

# IPv4 имени ($1, по умолчанию PPA) через DNS-over-HTTPS по IP-адресу (свой DNS сервера для этого не нужен)
awg_doh_ip() {
    local h="${1:-$AWG_PPA_HOST}" ip=""
    ip=$(curl -fsS --max-time 10 -H 'accept: application/dns-json' "https://1.1.1.1/dns-query?name=$h&type=A" 2>/dev/null \
        | grep -oE '"data": *"[0-9]+(\.[0-9]+){3}"' | head -1 | grep -oE '[0-9]+(\.[0-9]+){3}') || true
    if [ -z "$ip" ]; then
        ip=$(curl -fsS --max-time 10 "https://8.8.8.8/resolve?name=$h&type=A" 2>/dev/null \
            | grep -oE '"data": *"[0-9]+(\.[0-9]+){3}"' | head -1 | grep -oE '[0-9]+(\.[0-9]+){3}') || true
    fi
    echo "$ip"
}

# если DNS сервера не разрешает имя $1 — временно прописать его адрес из DNS-over-HTTPS в hosts (0 — прописано)
awg_hosts_pin() {
    local h="$1" ip=""
    getent ahostsv4 "$h" >/dev/null 2>&1 && return 1
    echo "DNS сервера не разрешает $h — узнаю его адрес через DNS-over-HTTPS (1.1.1.1, 8.8.8.8)"
    ip=$(awg_doh_ip "$h")
    [ -n "$ip" ] || { echo "DNS-over-HTTPS тоже недоступен"; return 2; }
    echo "$ip $h # vpngramm-tmp" >> "$AWG_HOSTS"
    echo "Временно: $h → $ip (строка в $AWG_HOSTS уберётся после установки)"
    return 0
}

# причина из вывода apt-get update (строка про PPA)
awg_reason() {
    local r=""
    # сначала сама причина («Temporary failure resolving …», «Could not connect …»), иначе строка Err/W/E про PPA
    r=$(echo "$1" | grep -iE "$AWG_PPA_HOST|amnezia" | grep -iE "fail|could not|unable|timed? ?out|refused|no route" | grep -viE "^ *(err|hit|get|ign):" \
        | head -1 | sed 's/^ *//' | cut -c1-200) || true
    [ -z "$r" ] && r=$(echo "$1" | grep -iE "$AWG_PPA_HOST|amnezia" | grep -iE "^ *(err|w:|e:)" | head -1 | sed 's/^ *//' | cut -c1-200) || true
    echo "$r"
}

awg_repo_prepare() {
    local i=1 out="" reason="" ip=""
    while :; do
        out=$(apt-get -q update 2>&1) || true
        if awg_ppa_ready; then return 0; fi
        reason=$(awg_reason "$out")
        [ "$i" -ge "$AWG_TRIES" ] && break
        echo "Репозиторий Amnezia пока недоступен (${reason:-пакеты amneziawg не видны}) — повтор через $AWG_PAUSE с (попытка $((i + 1)) из $AWG_TRIES)"
        sleep "$AWG_PAUSE"
        i=$((i + 1))
    done
    local rc=0
    awg_hosts_pin "$AWG_PPA_HOST" || rc=$?
    if [ "$rc" -eq 0 ]; then
        out=$(apt-get -q update 2>&1) || true
        if awg_ppa_ready; then return 0; fi
        reason=$(awg_reason "$out")
        awg_hosts_cleanup
    elif [ "$rc" -eq 2 ]; then
        reason="${reason:-DNS сервера не разрешает имя}, а DNS-over-HTTPS тоже недоступен"
    fi
    echo "Репозиторий Amnezia недоступен с этого сервера ($AWG_PPA_HOST): ${reason:-пакеты amneziawg не найдены}."
    return 1
}
