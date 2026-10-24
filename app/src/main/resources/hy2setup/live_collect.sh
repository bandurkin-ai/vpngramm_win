#!/bin/bash
# «Живая проверка» Hy2Setup: на время окна включает подробные журналы Xray/Hysteria2,
# собирает, что сервер видит от клиентов, и пишет понятный итог в $OUT.
# Параметры (подставляются приложением): SECS — длительность окна, PHONE — публичный IP телефона (может быть пустым).
SECS=__SECS__
PHONE='__PHONE__'
OUT=/root/.hy2setup-live.log
XCONF=/usr/local/etc/xray/config.json
XBAK=/root/.hy2setup-live-xray.json
HDROP=/etc/systemd/system/hysteria-server.service.d/zz-hy2setup-live.conf
UDPRULE=""

restore() {
    if [ -f "$XBAK" ]; then
        cp -p "$XBAK" "$XCONF" && rm -f "$XBAK"
        systemctl restart xray.service 2>/dev/null
    fi
    if [ -f "$HDROP" ]; then
        rm -f "$HDROP"
        systemctl daemon-reload
        systemctl restart hysteria-server.service 2>/dev/null
    fi
    [ -n "$UDPRULE" ] && iptables -D INPUT -p udp --dport 443 -m comment --comment hy2setup-live -j ACCEPT 2>/dev/null
    rm -f /root/.hy2setup-live.sh
}
trap restore EXIT

{
echo "==> Живая проверка: ${SECS} секунд"
HAS_X=0; HAS_H=0
systemctl cat xray.service >/dev/null 2>&1 && [ -f "$XCONF" ] && HAS_X=1
systemctl cat hysteria-server.service >/dev/null 2>&1 && HAS_H=1

if [ "$HAS_X" = 1 ]; then
    cp -p "$XCONF" "$XBAK"
    sed -i -e 's/"loglevel": *"warning"/"loglevel": "info"/' \
           -e 's/"realitySettings": *{/"realitySettings": { "show": true,/' "$XCONF"
    if /usr/local/bin/xray run -test -c "$XCONF" >/dev/null 2>&1; then
        systemctl restart xray.service
    else
        cp -p "$XBAK" "$XCONF"; rm -f "$XBAK"; HAS_X=0
        echo "ИНФО: подробный журнал Xray включить не удалось — VLESS не проверяется"
    fi
fi
if [ "$HAS_H" = 1 ]; then
    mkdir -p "$(dirname "$HDROP")"
    printf '[Service]\nEnvironment=HYSTERIA_LOG_LEVEL=debug\n' > "$HDROP"
    systemctl daemon-reload
    systemctl restart hysteria-server.service
fi
sleep 2
START=$(date '+%Y-%m-%d %H:%M:%S')
STARTX=$(date '+%Y/%m/%d %H:%M:%S')
if command -v iptables >/dev/null 2>&1 &&
   iptables -I INPUT -p udp --dport 443 -m comment --comment hy2setup-live -j ACCEPT 2>/dev/null; then
    UDPRULE=1
else
    U0=$(awk '/^Udp:/ { if (h) { print $2; exit } h = 1 }' /proc/net/snmp)
fi
echo "ИНФО: начало $START — выключите и снова включите в Happ VLESS, откройте сайт; затем то же с Hysteria2"
[ -n "$PHONE" ] && echo "ИНФО: IP телефона (без VPN): $PHONE"

sleep "$SECS"

if [ -n "$UDPRULE" ]; then
    UDPN=$(iptables -L INPUT -v -n -x 2>/dev/null | awk '/hy2setup-live/ { print $1; exit }')
else
    U1=$(awk '/^Udp:/ { if (h) { print $2; exit } h = 1 }' /proc/net/snmp)
    UDPN=$(( ${U1:-0} - ${U0:-0} ))
fi

# какой IP считать телефоном: заданный, иначе тот, что чаще всех похож на настоящего клиента
# (оператор может показывать телефон с другого IP, тогда берём самый частый)
pick() {
    L=$(grep .)
    if [ -n "$PHONE" ] && echo "$L" | grep -qx "$PHONE"; then echo "$PHONE"
    else echo "$L" | sort | uniq -c | sort -rn | awk 'NR == 1 { print $2 }'; fi
}

if [ "$HAS_X" = 1 ]; then
    echo
    echo "==> VLESS (TCP 443)"
    XJ=$(journalctl -u xray --since "$START" --no-pager -o cat 2>/dev/null)
    # по каждому подключению: ключ подошёл (conn == conn: true) / ключ не подошёл, shortId виден / ничего не расшифровано
    STATS=$(echo "$XJ" | awk -F'\t' '
        /^REALITY remoteAddr: / {
            split($1, a, " "); addr = a[3]; ip = addr; sub(/:[0-9]+$/, "", ip); seen[addr] = ip
            if ($2 ~ /ClientShortId/) sid[addr] = 1
            if ($2 ~ /hs.c.conn == conn: true/) ok[addr] = 1
            if ($2 ~ /hs.c.conn == conn: false/) bad[addr] = 1
        }
        END { for (k in seen) {
            s = ok[k] ? "ok" : (bad[k] ? (sid[k] ? "badsid" : "badkey") : "other")
            print seen[k], s } }')
    UUIDBAD=$(echo "$XJ" | grep -o 'invalid request from [0-9.]*' | awk '{ print $4 }')
    ACC=$(awk -v s="$STARTX" '($1 " " substr($2, 1, 8)) >= s && /accepted/ { ip = $4; sub(/^tcp:/, "", ip); sub(/:[0-9]+$/, "", ip); print ip }' /var/log/xray/access.log 2>/dev/null)
    TOTAL=$(echo "$STATS" | grep -c .)
    if [ "$TOTAL" = 0 ]; then
        echo "ПРОБЛЕМА: VLESS: за ${SECS} с к серверу не было ни одного TCP-подключения на 443 — Happ до сервера не дошёл (не та ссылка/адрес, VPN не включался или трафик блокируется по пути)"
    else
        # кандидаты в телефон — подключения, похожие на клиента Reality
        WHO=$( { echo "$STATS" | awk '$2 != "badkey" { print $1 }'; echo "$ACC"; } | grep . | pick)
        [ -z "$WHO" ] && WHO=$(echo "$STATS" | awk '{ print $1 }' | pick)
        n() { echo "$STATS" | awk -v ip="$WHO" -v s="$1" '$1 == ip && $2 == s' | wc -l; }
        OKN=$(n ok); BK=$(n badkey); BS=$(n badsid); OT=$(n other)
        UB=$(echo "$UUIDBAD" | grep -cx "$WHO")
        AC=$(echo "$ACC" | grep -cx "$WHO")
        echo "ИНФО: подключений к 443/TCP: $TOTAL, из них с $WHO: $((OKN + BK + BS + OT)) (ключ подошёл $OKN, ключ не подошёл $BK, неверный shortId $BS, оборвались до проверки ключа $OT)"
        if [ "$AC" -gt 0 ]; then
            echo "OK: VLESS работает на сервере: через него с $WHO открывались сайты ($AC соединений)"
        elif [ "$UB" -gt 0 ]; then
            echo "ПРОБЛЕМА: VLESS: ключ Reality подошёл, но UUID пользователя не тот — в Happ старая ссылка. Удалите её и импортируйте заново"
        elif [ "$OKN" -gt 0 ]; then
            echo "ПРОБЛЕМА: VLESS: клиент подключился, но не открыл ни одного сайта — проверьте в Happ режим маршрутизации"
        elif [ "$BS" -gt 0 ]; then
            echo "ПРОБЛЕМА: VLESS: ключ подошёл, но shortId (sid) не тот — в Happ старая или изменённая ссылка. Удалите её и импортируйте заново"
        elif [ "$BK" -gt 0 ] && [ -n "$PHONE" ] && [ "$WHO" = "$PHONE" ]; then
            echo "ПРОБЛЕМА: VLESS: телефон ($WHO) дошёл до сервера, но ключ Reality (pbk) не подошёл — в Happ старая или неполная ссылка, либо рукопожатие портится по пути. Удалите ссылку VLESS в Happ и импортируйте заново"
        elif [ "$BK" -gt 0 ]; then
            echo "ПРОБЛЕМА: VLESS: подключения были, но ни одно не прошло по ключу Reality (pbk) — если это ваш телефон, в Happ старая или неполная ссылка, либо рукопожатие портится по пути"
        else
            echo "ПРОБЛЕМА: VLESS: телефон подключается к серверу, но рукопожатие обрывается по пути — провайдер (ТСПУ) режет Reality с чужим сайтом маскировки. Переведите VLESS на свой домен (Комбайн+ → «Перевести VLESS на свой домен»)"
        fi
    fi
fi

if [ "$HAS_H" = 1 ]; then
    echo
    echo "==> Hysteria2 (UDP 443)"
    HJ=$(journalctl -u hysteria-server --since "$START" --no-pager -o cat 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g')
    HC=$(echo "$HJ" | grep "client connected" | grep -o '"addr": *"[^"]*"' | cut -d'"' -f4 | sed 's/:[0-9]*$//')
    HAUTH=$(echo "$HJ" | grep "masquerade request" | grep '"/auth"' | grep -o '"addr": *"[^"]*"' | cut -d'"' -f4 | sed 's/:[0-9]*$//')
    HTLS=$(echo "$HJ" | grep "matched certificate" | grep -o '"remote_ip": *"[^"]*"' | cut -d'"' -f4)
    HREQ=$(echo "$HJ" | grep -cE '(TCP|UDP) request')
    echo "ИНФО: UDP-пакетов на сервер за окно: ${UDPN:-?}"
    if [ -n "$HC" ]; then
        WHO=$(echo "$HC" | pick)
        if [ "$HREQ" -gt 0 ]; then
            echo "OK: Hysteria2 работает на сервере: клиент $WHO подключился, запросов к сайтам: $HREQ"
        else
            echo "ПРОБЛЕМА: Hysteria2: клиент $WHO подключился, но не открыл ни одного сайта — проверьте в Happ режим маршрутизации"
        fi
    elif [ -n "$HAUTH" ]; then
        echo "ПРОБЛЕМА: Hysteria2: телефон дошёл до сервера, но пароль не подошёл — в Happ старая ссылка. Удалите её и импортируйте заново"
    elif [ -n "$HTLS" ]; then
        echo "ПРОБЛЕМА: Hysteria2: шифрование (obfs) прошло, но вход не завершился — соединение обрывается по пути"
    elif [ "${UDPN:-0}" -ge 8 ]; then
        echo "ПРОБЛЕМА: Hysteria2: UDP-пакеты до сервера доходят ($UDPN), но не расшифровываются — пароль (или obfs salamander) в Happ не совпадает со ссылкой. Удалите ссылку Hysteria2 в Happ и импортируйте заново. Если Happ был включён ещё до начала проверки — выключите и включите его и повторите"
    else
        echo "ПРОБЛЕМА: Hysteria2: UDP-пакеты от телефона до сервера не доходят — оператор или сеть блокирует UDP. Используйте VLESS"
    fi
fi
echo
echo "Живая проверка завершена."
} > "$OUT" 2>&1
