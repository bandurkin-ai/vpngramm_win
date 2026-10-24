#!/bin/bash
# vpngramm 2.0 на ОС $1: два человека (Hysteria2 userpass, два UUID в Xray), сайт из каталога и подписка через Reality.
T=$1; C=os-$T; S=/home/user/Hy2Setup/app/build/scripts; W=/tmp/ostest/work-v2/$T; rm -rf $W; mkdir -p $W
ENV="-e https_proxy=http://172.17.0.1:3128 -e CURL_CA_BUNDLE=/opt/ca.crt -e SSL_CERT_FILE=/opt/ca.crt"
/tmp/ostest/bin/up.sh $T; docker exec $C systemctl is-system-running --wait >/dev/null 2>&1
python3 - "$S/v2s_hy.sh" "$W/hy.sh" <<'PY'
import sys,base64,re
s=open(sys.argv[1]).read()
b=lambda f: base64.b64encode(open('/tmp/ostest/plus/'+f,'rb').read()).decode()
out=[]
for l in s.split('\n'):
    for ext,f in (('.crt','vpn.crt'),('.key','vpn.key'),('.json','vpn.json')):
        if l.startswith("echo '") and l.endswith("vpn.example.com"+ext+"'"):
            l=re.sub(r"^echo '[^']*'", "echo '"+b(f)+"'", l)
    out.append(l)
open(sys.argv[2],'w').write('\n'.join(out))
PY
cp $S/v2s_vl.sh $W/vl.sh; cp $S/v2s_site.sh $W/site.sh; cp $S/v2s_check.sh $W/check.sh
sed -i -e 's#bash <(curl -fsSL https://get.hy2.sh/)#bash /opt/dl/install_server.sh --local /opt/dl/hysteria-linux-amd64#' \
       -e 's#\(install-release.sh)"\) @ install$#\1 @ install --local /opt/dl/Xray-linux-64.zip#' $W/*.sh
for f in site hy vl; do docker cp -q $W/$f.sh $C:/root/$f.sh; docker exec $ENV $C bash /root/$f.sh > $W/$f.log 2>&1; echo "== $T $f: код $?"; done
docker cp -q $W/check.sh $C:/root/check.sh; docker exec $ENV $C bash /root/check.sh > $W/check.log 2>&1
echo "== $T службы: $(grep '^##SVC' $W/check.log | tr '\n' ' ') сайт: $(grep -c '^##SITE=file' $W/check.log)"
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $C)
CL=cls-$T; docker rm -f $CL >/dev/null 2>&1
docker run -d --name $CL --add-host vpn.example.com:$IP -v /tmp/ostest/dl:/opt/dl:ro -v $W:/w --entrypoint sleep hy2test:d12 infinity >/dev/null
docker exec $CL bash -c 'cd /tmp && cp /opt/dl/x/xray . && cp /opt/dl/hysteria-linux-amd64 hy && chmod +x xray hy'
for who in owner user; do
  mkdir -p $W/$who; python3 /tmp/ostest/bin/mkclient.py $S/v2s_links_$who.txt $W/$who
  docker exec $CL bash -c "cd /tmp && pkill xray; pkill hy; sleep 1; (./xray run -c /w/$who/vl.json > /w/$who/cvl.log 2>&1 &); (./hy client -c /w/$who/hy_uri.yaml > /w/$who/chy.log 2>&1 &); sleep 4"
  for n in vl:1080 hy:1082; do
    echo "== $T $who ${n%%:*}: HTTP $(docker exec $CL curl -s -m 20 -o /dev/null -w '%{http_code}' --socks5-hostname 127.0.0.1:${n#*:} http://archive.ubuntu.com/ubuntu/)"
  done
done
# подписка: обычный HTTPS к домену (без ключа Reality) → Hysteria2 отдаёт файл
for tok in $(cat $S/v2s_tokens.txt); do
  echo "== $T подписка $tok: $(docker exec $CL curl -sk -m 15 https://vpn.example.com/s/$tok | grep -c '://') ссылок"
done
docker exec $CL curl -sk -m 15 https://vpn.example.com/s/$(sed -n 2p $S/v2s_tokens.txt) > $W/sub_user.txt
cmp -s $W/sub_user.txt $S/v2s_sub_user.txt && echo "== $T подписка совпадает с приложением" || echo "== $T подписка ОТЛИЧАЕТСЯ"
echo "== $T каталог /s/: $(docker exec $CL curl -sk -m 15 https://vpn.example.com/s/ | grep -c "$(head -1 $S/v2s_tokens.txt)") упоминаний токена (должно быть 0)"
echo "== $T главная: $(docker exec $CL curl -sk -m 15 https://vpn.example.com/ | grep -o '<title>[^<]*')"
docker rm -f $CL >/dev/null
