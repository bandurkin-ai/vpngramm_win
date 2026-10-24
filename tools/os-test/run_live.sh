#!/bin/bash
# Живая проверка на ОС $1 скриптами приложения (live_check.sh / live_result.sh): верные ссылки, неверные ключи, без клиента.
# После каждого прогона: config.json Xray восстановлен побайтно, временный drop-in удалён, службы active.
T=$1; C=os-$T; S=/home/user/Hy2Setup/app/build/scripts; W=/tmp/ostest/work-live-$T; mkdir -p $W
/tmp/ostest/bin/up.sh $T; docker exec $C systemctl is-system-running --wait >/dev/null 2>&1
NOREMOVE=1 /tmp/ostest/bin/run_plus.sh $T | grep FAIL
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $C)
CL=cll-$T; docker rm -f $CL >/dev/null 2>&1
docker run -d --name $CL --add-host vpn.example.com:$IP -v /tmp/ostest/dl:/opt/dl:ro -v /tmp/ostest/work-live:/w \
    --entrypoint sleep hy2test:d12 infinity >/dev/null
docker exec $CL bash -c 'cd /tmp && cp /opt/dl/x/xray . && cp /opt/dl/hysteria-linux-amd64 hy && chmod +x xray hy'
CLIP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $CL)
# телефон = IP клиента-контейнера
sed "s/172.17.0.3/$CLIP/" $S/live_check.sh > $W/live_check.sh
case_() { # $1 имя, $2 запуск клиентов, $3 порты
    docker exec $C md5sum /usr/local/etc/xray/config.json | cut -c1-32 > $W/md5
    docker cp -q $W/live_check.sh $C:/root/lc.sh; docker cp -q $S/live_result.sh $C:/root/lr.sh
    docker exec $C bash /root/lc.sh | grep -q '##LIVE_STARTED' || echo "   не запустилась!"
    sleep 6
    docker exec $CL bash -c "cd /tmp; $2 sleep 3; for p in $3; do curl -s -m 10 -o /dev/null --socks5-hostname 127.0.0.1:\$p http://archive.ubuntu.com/ubuntu/; done; sleep 2; pkill xray; pkill hy; true"
    echo "## $T — $1"
    docker exec $C bash /root/lr.sh | grep -E '^(OK|ПРОБЛЕМА)' | cut -c1-120 | sed 's/^/   /'
    R=$(docker exec $C bash -c "[ \"\$(md5sum /usr/local/etc/xray/config.json | cut -c1-32)\" = \"$(cat $W/md5)\" ] && echo cfg-ok;
        ls /etc/systemd/system/hysteria-server.service.d/ | grep -q live && echo dropin-LEFT || echo dropin-ok;
        systemctl is-active xray hysteria-server | tr '\n' ' '; [ -e /root/.hy2setup-live.sh ] && echo script-LEFT")
    echo "   восстановление: $(echo $R)"
}
case_ "верные ссылки" "(./xray run -c /w/vl.json >/dev/null 2>&1 &); (./hy client -c /w/hy.yaml >/dev/null 2>&1 &);" "1080 1081"
case_ "неверный pbk, неверный пароль" "(./xray run -c /w/vl_bad.json >/dev/null 2>&1 &); (./hy client -c /w/hy_badauth.yaml >/dev/null 2>&1 &);" "1090 1091"
case_ "без клиента" "" ""
docker rm -f $CL >/dev/null
