#!/bin/bash
# vpngramm-fw — файрвол vpngramm: снаружи открыты только SSH и порты установленных VPN.
#   vpngramm-fw apply  — построить и применить правила (своя таблица nftables «inet vpngramm»)
#   vpngramm-fw print  — только показать правила
#   vpngramm-fw ports  — показать открываемые порты
#   vpngramm-fw off    — убрать таблицу vpngramm (всё снова открыто, как до защиты)
# FW_ROOT — префикс путей к конфигурациям (для проверки), FW_SSH_PORTS — порты SSH вместо sshd -T.
R="${FW_ROOT:-}"

ssh_ports() {
    if [ -n "${FW_SSH_PORTS:-}" ]; then echo "$FW_SSH_PORTS"; return; fi
    local P
    P=$( (command -v sshd >/dev/null && sshd -T || /usr/sbin/sshd -T) 2>/dev/null | awk 'tolower($1)=="port"{print $2}' | sort -un | tr '\n' ' ')
    echo "${P:-22}"
}

# порты протоколов — из их конфигураций (как в «Проверить»)
vpn_ports() {
    local C P out=""
    C="$R/etc/hysteria/config.yaml"
    if [ -f "$C" ]; then
        P=$(sed -n 's/^listen: *[^:]*:\([0-9][0-9]*\).*/\1/p' "$C" | head -1)
        out="$out ${P:-443}/udp"
        # Let's Encrypt проверяет домен по 80/tcp
        grep -q '^acme:' "$C" && out="$out 80/tcp"
    fi
    if [ -f "$R/usr/local/etc/xray/config.json" ]; then
        P=$(sed -n 's/^PORT=//p' "$R/usr/local/etc/xray/hy2setup.env" 2>/dev/null | head -1)
        [ -z "$P" ] && P=$(grep -o '"port": *[0-9]*' "$R/usr/local/etc/xray/config.json" | head -1 | grep -o '[0-9]*$')
        out="$out ${P:-443}/tcp"
    fi
    if [ -f "$R/etc/mtg.toml" ]; then
        P=$(sed -n 's/^bind-to *= *".*:\([0-9][0-9]*\)".*/\1/p' "$R/etc/mtg.toml" | head -1)
        [ -n "$P" ] && out="$out $P/tcp"
    fi
    if [ -f "$R/etc/amnezia/amneziawg/awg0.conf" ]; then
        P=$(sed -n 's/^ListenPort *= *//p' "$R/etc/amnezia/amneziawg/awg0.conf" | head -1 | tr -d ' ')
        [ -n "$P" ] && out="$out $P/udp"
    fi
    echo $out | tr ' ' '\n' | awk 'NF && !seen[$0]++' | tr '\n' ' '
}

# «443, 993» из списка «443/tcp 993/tcp …» для протокола $1
set_of() {
    local proto="$1"; shift
    echo "$@" | tr ' ' '\n' | sed -n "s#^\([0-9][0-9]*\)/$proto\$#\1#p" | sort -un | paste -sd, - | sed 's/,/, /g'
}

ruleset() {
    local SSH VPN TCP UDP
    SSH=$(ssh_ports)
    VPN=$(vpn_ports)
    TCP=$(set_of tcp $(for p in $SSH; do echo "$p/tcp"; done) $VPN)
    UDP=$(set_of udp $VPN)
    echo "add table inet vpngramm"
    echo "delete table inet vpngramm"
    echo "table inet vpngramm {"
    echo "    chain input {"
    echo "        type filter hook input priority filter; policy drop;"
    echo "        iif \"lo\" accept"
    echo "        ct state established,related accept"
    echo "        ct state invalid drop"
    echo "        ip protocol icmp accept"
    echo "        meta l4proto ipv6-icmp accept"
    echo "        udp sport 67 udp dport 68 accept"
    echo "        udp sport 547 udp dport 546 accept"
    echo "        iifname \"awg0\" accept"
    echo "        tcp dport { $TCP } accept"
    [ -n "$UDP" ] && echo "        udp dport { $UDP } accept"
    echo "    }"
    echo "}"
}

case "${1:-}" in
    apply)
        command -v nft >/dev/null 2>&1 || { echo "ОШИБКА: нет nft (пакет nftables)"; exit 1; }
        ruleset | nft -f - || { echo "ОШИБКА: nftables не принял правила"; exit 1; }
        echo "Файрвол vpngramm: открыты SSH ($(ssh_ports | sed 's/ *$//')/tcp) и порты VPN: $(vpn_ports | sed 's/ *$//')."
        ;;
    print) ruleset ;;
    ports) echo "ssh: $(ssh_ports)"; echo "vpn: $(vpn_ports)" ;;
    off)
        nft delete table inet vpngramm 2>/dev/null || true
        echo "Файрвол vpngramm снят."
        ;;
    *) echo "использование: vpngramm-fw apply|print|ports|off"; exit 2 ;;
esac
