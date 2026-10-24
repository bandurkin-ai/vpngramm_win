#!/bin/bash
# Сквозной тест встроенного клиента: сервер стенда ← движок vpncore в контейнере с TUN.
#  без PROTO — VLESS (os-d12, сценарий run_v2.sh); PROTO=xhttp — VLESS XHTTP (install_vless_xhttp.sh на os-d12);
#  PROTO=hy2 — Hysteria2 (hy2_server.sh); PROTO=awg — AmneziaWG (awg_server.sh).
S=/home/user/Hy2Setup/app/build/scripts; W=$(mktemp -d); E=${ENGINE:?путь к vpncore для linux}
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' os-d12)
VL=$(sed -n 2p $S/v2s_links_owner.txt)
# UUID владельца — из конфигурации самого сервера (файл ссылок мог устареть после переустановок)
UUID=$(docker exec os-d12 python3 -c 'import json,re;print(json.loads(re.sub(r"//.*","",open("/usr/local/etc/xray/config.json").read()))["inbounds"][0]["settings"]["clients"][0]["id"])'); PBK=$(echo "$VL" | sed 's#.*pbk=\([^&]*\).*#\1#'); SID=$(echo "$VL" | sed 's#.*sid=\([^&#]*\).*#\1#')
URL=http://archive.ubuntu.com/ubuntu/
if [ "$PROTO" = awg ]; then
  # AmneziaWG: сервер — awg_server.sh (контейнер awgsrv), проверочная страница внутри него
  IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' awgsrv)
  sed -e "s/SERVER_IP/$IP/" $S/vpn_app_awg.json > $W/config.json; URL=http://198.51.100.1:8080/
elif [ "$PROTO" = xhttp ]; then
  # VLESS + Reality + XHTTP на том же 443: сервер ставится install_vless_xhttp.sh (UUID 1111…, путь /xh123)
  sed -e "s/SERVER_IP/$IP/" $S/vpn_app_xhttp.json > $W/config.json
elif [ "$PROTO" = hy2 ]; then
  # Hysteria2: второй сервер hy2_server.sh на 4443/UDP с самоподписанным сертификатом (только на стенде — insecure)
  python3 - $S/vpn_app_hy2.json $IP > $W/config.json <<'PY'
import json,sys
c=json.load(open(sys.argv[1])); o=c["outbounds"][0]
o["server"]=sys.argv[2]; o["server_port"]=4443; o["tls"]["insecure"]=True
print(json.dumps(c))
PY
elif [ -n "$APPCFG" ]; then
  # конфигурация, собранная приложением (VpnConfigTest): подставляем адрес и UUID сервера стенда
  sed -e "s/SERVER_IP/$IP/" -e "s/$(cat $S/vpn_app_uuid.txt)/$UUID/" $S/vpn_app_config.json > $W/config.json
else
  DNS=${DNS:-https} python3 $(dirname $0)/cfg.py $IP 443 $UUID vpn.example.com $PBK $SID ${BYPASS:-0} > $W/config.json
fi
cp $E $W/vpncore; cp $(dirname $0)/fakeapp.py $W/
docker rm -f vpncl >/dev/null 2>&1
docker run -d --name vpncl --cap-add NET_ADMIN --device /dev/net/tun -v $W:/w --entrypoint sleep hy2test:d12 infinity >/dev/null
docker exec -d -e VPNCORE_XRAY_LOG=${XRAYLOG:-warning} vpncl bash -c 'python3 /w/fakeapp.py vpngramm-test /w/vpncore /w/config.json > /w/app.log 2>&1'
sleep 6
echo "== журнал:"; grep -c . $W/app.log; grep -iE "error|outbound/|amneziawg|xray|STARTED" $W/app.log | head -8
echo "== HTTP через туннель ($URL): $(docker exec vpncl curl -s -m 20 -o /dev/null -w '%{http_code}' $URL)"
echo "== DNS через туннель: $(docker exec vpncl getent hosts archive.ubuntu.com | head -1)"
echo "== маршрут: $(docker exec vpncl ip route get 1.1.1.1 | head -1)"
docker logs vpncl 2>&1 | tail -3
[ -n "$KEEP" ] || docker rm -f vpncl >/dev/null
