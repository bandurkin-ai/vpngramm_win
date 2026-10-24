#!/bin/bash
# Проверка скриптов «Комбайна+» в контейнере os-$1 (сертификат из профиля, ключи Reality из профиля, копия сертификата).
C=os-$1; W=/tmp/ostest/work-plus/$1; rm -rf $W; mkdir -p $W
S=/home/user/Hy2Setup/app/build/scripts; P=/tmp/ostest/plus
ENV="-e https_proxy=http://172.17.0.1:3128 -e CURL_CA_BUNDLE=/opt/ca.crt -e SSL_CERT_FILE=/opt/ca.crt"
PASS=(); FAIL=()
python3 - "$S/install_plus.sh" "$W/install_plus.sh" <<'PY'
import sys,base64,re
s=open(sys.argv[1]).read()
b=lambda f: base64.b64encode(open('/tmp/ostest/plus/'+f,'rb').read()).decode()
lines=s.split('\n'); out=[]
for l in lines:
    for ext,f in (('.crt','vpn.crt'),('.key','vpn.key'),('.json','vpn.json')):
        if l.startswith("echo '") and l.endswith("vpn.example.com"+ext+"'"):
            l=re.sub(r"^echo '[^']*'", "echo '"+b(f)+"'", l)
    out.append(l)
open(sys.argv[2],'w').write('\n'.join(out))
PY
for f in install_vless_keys.sh export_cert.sh remove.sh remove_vless.sh; do cp $S/$f $W/$f; done
sed -i -e 's#bash <(curl -fsSL https://get.hy2.sh/)#bash /opt/dl/install_server.sh --local /opt/dl/hysteria-linux-amd64#' \
       -e 's#\(install-release.sh)"\) @ install$#\1 @ install --local /opt/dl/Xray-linux-64.zip#' $W/*.sh
run() { local name=$1 s=$2 chk=$3 hchk=$4
  docker cp -q $W/$s $C:/root/$s
  docker exec $ENV $C bash /root/$s > $W/$name.log 2>&1; local code=$?
  if [ $code -eq 0 ] && { [ -z "$chk" ] || docker exec $C bash -c "$chk" >> $W/$name.log 2>&1; } && { [ -z "$hchk" ] || bash -c "$hchk"; }; then PASS+=("$name"); else FAIL+=("$name(code=$code)"); fi
}
D=/var/lib/hysteria/acme/certificates/acme-v02.api.letsencrypt.org-directory/vpn.example.com
run hy2_cert   install_plus.sh 'sleep 8; systemctl is-active hysteria-server && ss -lunH | grep -q ":443\b" && ! journalctl -u hysteria-server --no-pager | grep -q "obtaining certificate"' "grep -q 'Служба работает' $W/hy2_cert.log && grep -q 'из постоянного профиля' $W/hy2_cert.log"
docker exec $C sh -c "journalctl -u hysteria-server --no-pager | tail -8" > $W/hy2_journal.log 2>&1
run vless_keys install_vless_keys.sh 'systemctl is-active xray && ss -ltnH | grep -q ":443\b" && /usr/local/bin/xray x25519 -i iO2HTWeqgbV5Q-YiChZzgD9WnMleLH9aO0wt-FPL0Vo | grep -q OnzLYEwWXpv--0raqKhr-8YYr88Ix_cADvHl0LGKSHw && grep -q "\"privateKey\": \"iO2HTWeqgbV5Q-YiChZzgD9WnMleLH9aO0wt-FPL0Vo\"" /usr/local/etc/xray/config.json && systemctl is-active hysteria-server' "grep -q 'Ключи Reality — из постоянного профиля' $W/vless_keys.log"
run export_cert export_cert.sh '' "[ \"\$(grep '^##CRT=' $W/export_cert.log | cut -d= -f2-)\" = \"\$(base64 -w0 $P/vpn.crt)\" ] && [ \"\$(grep '^##KEY=' $W/export_cert.log | cut -d= -f2-)\" = \"\$(base64 -w0 $P/vpn.key)\" ] && grep -q '^##META=' $W/export_cert.log"
[ -z "$NOREMOVE" ] && run remove_all remove.sh '! systemctl is-active hysteria-server'
[ -z "$NOREMOVE" ] && run remove_vl  remove_vless.sh '! systemctl is-active xray'
echo "== $1: PASS ${#PASS[@]}: ${PASS[*]}"
echo "== $1: FAIL ${#FAIL[@]}: ${FAIL[*]}"
