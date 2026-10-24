#!/bin/bash
# Прогон скриптов приложения в контейнере os-$1. Скрипты — из app/build/scripts (без изменений),
# кроме строк загрузки установщиков: get.hy2.sh закрыт политикой сети, api.github.com тоже.
C=os-$1
W=/tmp/ostest/work/$1; rm -rf $W; mkdir -p $W
ENV="-e https_proxy=http://172.17.0.1:3128 -e CURL_CA_BUNDLE=/opt/ca.crt -e SSL_CERT_FILE=/opt/ca.crt"
PASS=(); FAIL=()
prep() { # $1 script → $W/$1
  sed -e 's#bash <(curl -fsSL https://get.hy2.sh/)#bash /opt/dl/install_server.sh --local /opt/dl/hysteria-linux-amd64#' \
      -e 's#\(install-release.sh)"\) @ install$#\1 @ install --local /opt/dl/Xray-linux-64.zip#' \
      /home/user/Hy2Setup/app/build/scripts/$1 > $W/$1
  docker cp -q $W/$1 $C:/root/$1
}
run() { # name script check-in-container [check-on-host]
  local name=$1 s=$2 chk=$3 hchk=$4
  prep $s
  docker exec $ENV $C bash /root/$s > $W/$name.log 2>&1; local code=$?
  if [ $code -eq 0 ] && { [ -z "$chk" ] || docker exec $C bash -c "$chk" >> $W/$name.log 2>&1; } && { [ -z "$hchk" ] || bash -c "$hchk"; }; then PASS+=("$name"); else FAIL+=("$name(code=$code)"); fi
}
run hy2_install   install.sh       'systemctl is-active hysteria-server && ss -lunH | grep -q ":443\b"' "grep -q '##PIN=' $W/hy2_install.log"
run vless_install install_vless.sh 'systemctl is-active xray && ss -ltnH | grep -q ":443\b" && /usr/local/bin/xray run -test -c /usr/local/etc/xray/config.json'
run tg_install    install_tg.sh    'systemctl is-active mtg && ss -ltnH | grep -q ":18443\b"'
run export        export.sh        "" "grep -q '##HY2|' $W/export.log && grep -q '##VLESS|' $W/export.log && grep -q '##TG|' $W/export.log"
run export_tg     export_tg.sh     "" "grep -q '##TG|' $W/export_tg.log"
run status        status.sh        "" "grep -q 'Служба: active' $W/status.log"
run status_tg     status_tg.sh     "" "grep -q 'Служба: active' $W/status_tg.log"
run restart       restart.sh       'sleep 2; systemctl is-active hysteria-server'
run remove_hy2    remove.sh        '! systemctl is-active hysteria-server && systemctl is-active xray'
run remove_vless  remove_vless.sh  '! systemctl is-active xray && [ ! -e /usr/local/bin/xray ] && systemctl is-active mtg'
run remove_tg     remove_tg.sh     '! systemctl is-active mtg'
run hy2_domain    install_domain.sh 'grep -q "acme:" /etc/hysteria/config.yaml && systemctl show -p Restart hysteria-server | grep -q on-failure' "grep -q 'служба ждёт сертификат' $W/hy2_domain.log"
run remove_hy2b   remove.sh        '! systemctl is-active hysteria-server && [ ! -e /etc/systemd/system/hysteria-server.service.d/hy2setup.conf ]'
echo "== $1: PASS ${#PASS[@]}: ${PASS[*]}"
echo "== $1: FAIL ${#FAIL[@]}: ${FAIL[*]}"
