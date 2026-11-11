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

# IPv4 имени PPA через DNS-over-HTTPS по IP-адресу (свой DNS сервера для этого не нужен)
awg_doh_ip() {
    local ip=""
    ip=$(curl -fsS --max-time 10 -H 'accept: application/dns-json' "https://1.1.1.1/dns-query?name=$AWG_PPA_HOST&type=A" 2>/dev/null \
        | grep -oE '"data": *"[0-9]+(\.[0-9]+){3}"' | head -1 | grep -oE '[0-9]+(\.[0-9]+){3}') || true
    if [ -z "$ip" ]; then
        ip=$(curl -fsS --max-time 10 "https://8.8.8.8/resolve?name=$AWG_PPA_HOST&type=A" 2>/dev/null \
            | grep -oE '"data": *"[0-9]+(\.[0-9]+){3}"' | head -1 | grep -oE '[0-9]+(\.[0-9]+){3}') || true
    fi
    echo "$ip"
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
    if ! getent ahostsv4 "$AWG_PPA_HOST" >/dev/null 2>&1; then
        echo "DNS сервера не разрешает $AWG_PPA_HOST — узнаю его адрес через DNS-over-HTTPS (1.1.1.1, 8.8.8.8)"
        ip=$(awg_doh_ip)
        if [ -n "$ip" ]; then
            awg_hosts_cleanup
            echo "$ip $AWG_PPA_HOST # vpngramm-tmp" >> "$AWG_HOSTS"
            echo "Временно: $AWG_PPA_HOST → $ip (строка в $AWG_HOSTS уберётся после установки)"
            out=$(apt-get -q update 2>&1) || true
            if awg_ppa_ready; then return 0; fi
            reason=$(awg_reason "$out")
            awg_hosts_cleanup
        else
            reason="${reason:-DNS сервера не разрешает имя}, а DNS-over-HTTPS тоже недоступен"
        fi
    fi
    echo "ОШИБКА: сервер не может открыть репозиторий Amnezia ($AWG_PPA_HOST): ${reason:-пакеты amneziawg не найдены}." \
        "Проверьте DNS сервера (/etc/resolv.conf) или повторите установку позже — ядро уже обновлено, перезагрузки не будет."
    return 1
}
