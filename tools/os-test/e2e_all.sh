#!/bin/bash
# Полный сквозной прогон на ОС $1: установка Комбайна+ → трафик через клиентов → диагностика.
# $2=iptables — перед установкой закрыть входящие iptables (политика DROP, открыт только 22).
# $2=nocert — Hysteria2 без сохранённого сертификата (Let's Encrypt в стенде недоступен).
T=$1; MODE=$2; C=os-$T
ENV="-e https_proxy=http://172.17.0.1:3128 -e CURL_CA_BUNDLE=/opt/ca.crt -e SSL_CERT_FILE=/opt/ca.crt"
/tmp/ostest/bin/up.sh $T; docker exec $C systemctl is-system-running --wait >/dev/null 2>&1
if [ "$MODE" = iptables ]; then
    docker exec $C bash -c 'DEBIAN_FRONTEND=noninteractive apt-get install -y -qq iptables >/dev/null 2>&1;
      iptables -A INPUT -i lo -j ACCEPT; iptables -A INPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT;
      iptables -A INPUT -p tcp --dport 22 -j ACCEPT; iptables -P INPUT DROP; iptables -S INPUT | head -5'
fi
if [ "$MODE" = nocert ]; then
    # тот же install_plus, но без блока сертификата
    S=/home/user/Hy2Setup/app/build/scripts; W=/tmp/ostest/work-plus/$T; mkdir -p $W
    grep -v "/var/lib/hysteria/acme/certificates\|из постоянного профиля" $S/install_plus.sh |
      sed 's#bash <(curl -fsSL https://get.hy2.sh/)#bash /opt/dl/install_server.sh --local /opt/dl/hysteria-linux-amd64#' > $W/nocert.sh
    sed -i 's/seq 1 24/seq 1 2/' $W/nocert.sh
    docker cp -q $W/nocert.sh $C:/root/nocert.sh
    docker exec $ENV $C bash /root/nocert.sh > $W/nocert.log 2>&1; echo "== $T nocert install: code $?"
    grep -E "ПРЕДУПРЕЖДЕНИЕ|ОШИБКА" $W/nocert.log | head -3
else
    NOREMOVE=1 /tmp/ostest/bin/run_plus.sh $T
fi
docker exec $C bash -c 'echo "127.0.1.1 vpn.example.com" >> /etc/hosts'
docker cp -q /home/user/Hy2Setup/app/build/scripts/diagnose.sh $C:/root/diagnose.sh
docker exec $ENV $C bash /root/diagnose.sh > /tmp/ostest/work-e2e/$T/diagnose-before.log 2>&1
echo "== $T до клиентов:"; grep -E "^(OK|ИНФО|ПРОБЛЕМА): (Hysteria2|VLESS):|/etc/hosts|vpn.example.com" /tmp/ostest/work-e2e/$T/diagnose-before.log | sed "s/^/   /"
/tmp/ostest/bin/run_e2e.sh $T
docker cp -q /home/user/Hy2Setup/app/build/scripts/diagnose.sh $C:/root/diagnose.sh
docker exec $ENV $C bash /root/diagnose.sh > /tmp/ostest/work-e2e/$T/diagnose-$MODE.log 2>&1
echo "== $T diagnose: $(grep -c '^OK:' /tmp/ostest/work-e2e/$T/diagnose-$MODE.log) OK, $(grep -c '^ПРОБЛЕМА:' /tmp/ostest/work-e2e/$T/diagnose-$MODE.log) ПРОБЛЕМА"
echo "== $T после клиентов:"; grep -E "^(OK|ИНФО|ПРОБЛЕМА): (Hysteria2|VLESS):|^ПРОБЛЕМА" /tmp/ostest/work-e2e/$T/diagnose-$MODE.log | sed "s/^/   /"
