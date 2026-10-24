#!/bin/bash
# Сервер AmneziaWG для стенда: amneziawg-go (пользовательский режим) в контейнере awgsrv с конфигурацией приложения.
# Выхода в интернет (NAT) на стенде нет, поэтому проверочная страница — на «внешнем» адресе 198.51.100.1 внутри сервера.
S=/home/user/Hy2Setup/app/build/scripts; W=$(mktemp -d); B=${AWGSRV:?путь к amneziawg-go для linux}
cp $B $W/awgsrv; python3 $(dirname $0)/conf2uapi.py $S/vpn_awg_server.conf > $W/uapi.txt || exit 1
docker rm -f awgsrv >/dev/null 2>&1
docker run -d --name awgsrv --cap-add NET_ADMIN --device /dev/net/tun --entrypoint sleep hy2test:d12 infinity >/dev/null
docker cp $W/. awgsrv:/w
docker exec awgsrv bash -c '
  /w/awgsrv -f awg0 > /w/srv.log 2>&1 &
  for i in $(seq 20); do [ -S /var/run/amneziawg/awg0.sock ] && break; sleep 0.3; done
  python3 -c "
import socket; s=socket.socket(socket.AF_UNIX); s.connect(\"/var/run/amneziawg/awg0.sock\")
s.sendall(open(\"/w/uapi.txt\",\"rb\").read()+b\"\n\"); print(\"uapi:\", s.recv(100).decode().strip())"
  ip addr add 10.66.66.1/24 dev awg0; ip addr add 198.51.100.1/32 dev awg0; ip link set awg0 mtu 1280 up
  mkdir -p /w/www; echo awg-ok > /w/www/index.html
  cd /w/www && nohup python3 -m http.server 8080 --bind 198.51.100.1 > /w/http.log 2>&1 &
  sleep 1; ip -br addr show awg0'
