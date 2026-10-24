#!/bin/bash
# Сквозной тест: сервер os-$1 (уже с установленными скриптами Комбайна+), клиент — отдельный контейнер
# с hysteria и xray, настроенными по ссылкам приложения. Цель: http://archive.ubuntu.com/ubuntu/ через SOCKS.
T=$1; C=os-$T; W=/tmp/ostest/work-e2e/$T; mkdir -p $W
S=/home/user/Hy2Setup/app/build/scripts
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $C)
python3 /tmp/ostest/bin/mkclient.py $S/links_plus.txt $W
CL=cl-$T; docker rm -f $CL >/dev/null 2>&1
docker run -d --name $CL --add-host vpn.example.com:$IP -v /tmp/ostest/dl:/opt/dl:ro -v $W:/w \
    --entrypoint sleep hy2test:d12 infinity >/dev/null
docker exec $CL bash -c 'mkdir -p /c && cd /c && cp /opt/dl/x/xray xray && cp /opt/dl/hysteria-linux-amd64 hy && chmod +x hy xray
  (./hy client -c /w/hy.yaml > /w/hy.log 2>&1 &); (./hy client -c /w/hy_uri.yaml > /w/hy_uri.log 2>&1 &)
  (./xray run -c /w/vl.json > /w/vl.log 2>&1 &); sleep 4'
for n in vl:1080 hy:1081 hy_uri:1082; do
  code=$(docker exec $CL curl -s -m 20 -o /dev/null -w '%{http_code}' --socks5-hostname 127.0.0.1:${n#*:} http://archive.ubuntu.com/ubuntu/)
  echo "== $T ${n%%:*}: HTTP $code"
done
docker rm -f $CL >/dev/null
