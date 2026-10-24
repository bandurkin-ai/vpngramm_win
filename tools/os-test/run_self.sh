#!/bin/bash
# Reality «под себя» на ОС $1: Hysteria2 c listenHTTPS 127.0.0.1:8443 + VLESS c dest 127.0.0.1:8443 и SNI = свой домен.
T=$1; C=os-$T; S=/home/user/Hy2Setup/app/build/scripts; W=/tmp/ostest/work-self/$T; rm -rf $W; mkdir -p $W
ENV="-e https_proxy=http://172.17.0.1:3128 -e CURL_CA_BUNDLE=/opt/ca.crt -e SSL_CERT_FILE=/opt/ca.crt"
/tmp/ostest/bin/up.sh $T; docker exec $C systemctl is-system-running --wait >/dev/null 2>&1
HYSRC=${HYSRC:-install_plus_self.sh}; VLSRC=${VLSRC:-install_vless_self.sh}
if [ -n "$PRECERT" ]; then  # будто Let's Encrypt уже выдал сертификат
    D=/var/lib/hysteria/acme/certificates/acme-v02.api.letsencrypt.org-directory/vpn.example.com
    docker exec $C mkdir -p $D; for e in crt key json; do docker cp -q /tmp/ostest/plus/vpn.$e $C:$D/vpn.example.com.$e; done
fi
python3 - "$S/$HYSRC" "$W/hy.sh" <<'PY'
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
cp $S/$VLSRC $W/vl.sh
sed -i -e 's#bash <(curl -fsSL https://get.hy2.sh/)#bash /opt/dl/install_server.sh --local /opt/dl/hysteria-linux-amd64#' \
       -e 's#\(install-release.sh)"\) @ install$#\1 @ install --local /opt/dl/Xray-linux-64.zip#' $W/*.sh
for f in hy vl; do docker cp -q $W/$f.sh $C:/root/$f.sh; docker exec $ENV $C bash /root/$f.sh > $W/$f.log 2>&1; echo "== $T установка $f: код $?"; done
docker exec $C bash -c 'sleep 3; systemctl is-active hysteria-server xray | tr "\n" " "; ss -ltnH "sport = :8443" | grep -q 127.0.0.1 && echo "8443: слушает" || echo "8443: НЕТ"'
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $C)
LINKS=$S/links_plus_self.txt
if [ "$VLSRC" != install_vless_self.sh ]; then  # ключ Reality создан на сервере — берём его из вывода, как приложение
    PBK=$(grep '^##PBK=' $W/vl.log | cut -d= -f2-)
    head -1 $S/links_plus_self.txt > $W/links.txt
    sed -n 2p $S/links_plus_self.txt | sed "s/pbk=[^&]*/pbk=$PBK/" >> $W/links.txt
    LINKS=$W/links.txt
fi
python3 /tmp/ostest/bin/mkclient.py $LINKS $W
CL=cls-$T; docker rm -f $CL >/dev/null 2>&1
docker run -d --name $CL --add-host vpn.example.com:$IP -v /tmp/ostest/dl:/opt/dl:ro -v $W:/w --entrypoint sleep hy2test:d12 infinity >/dev/null
docker exec $CL bash -c 'cd /tmp && cp /opt/dl/x/xray . && cp /opt/dl/hysteria-linux-amd64 hy && chmod +x xray hy
  (./xray run -c /w/vl.json > /w/cvl.log 2>&1 &); (./hy client -c /w/hy.yaml > /w/chy.log 2>&1 &); sleep 4'
for n in vl:1080 hy:1081; do
  echo "== $T ${n%%:*}: HTTP $(docker exec $CL curl -s -m 20 -o /dev/null -w '%{http_code}' --socks5-hostname 127.0.0.1:${n#*:} http://archive.ubuntu.com/ubuntu/)"
done
# посторонний (без ключа) TLS с SNI своего домена: должен получить наш сертификат через Reality → Hysteria2
echo "== $T чужой TLS: $(docker exec $CL bash -c 'echo | openssl s_client -connect vpn.example.com:443 -servername vpn.example.com 2>/dev/null | openssl x509 -noout -subject 2>/dev/null')"
docker rm -f $CL >/dev/null
