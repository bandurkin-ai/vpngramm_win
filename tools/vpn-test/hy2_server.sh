#!/bin/bash
# Второй Hysteria2 на сервере стенда (os-d12, порт 4443/UDP) с самоподписанным сертификатом на vpn.example.com: настоящий
# Let's Encrypt на стенде не выпустить (этот же сертификат можно дать основному Hysteria2). userpass u0:aaaa, Salamander bbbb — как в vpn_app_hy2.json.
docker exec os-d12 bash -c '
  pkill -f "^/usr/local/bin/hysteria server -c /etc/hysteria/test" 2>/dev/null
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 -subj /CN=vpn.example.com \
    -keyout /etc/hysteria/test.key -out /etc/hysteria/test.crt 2>/dev/null
  cat > /etc/hysteria/test.yaml <<Y
listen: :4443
tls:
  cert: /etc/hysteria/test.crt
  key: /etc/hysteria/test.key
auth:
  type: userpass
  userpass:
    u0: aaaa
obfs:
  type: salamander
  salamander:
    password: bbbb
Y
  chmod 644 /etc/hysteria/test.key /etc/hysteria/test.crt
  nohup /usr/local/bin/hysteria server -c /etc/hysteria/test.yaml > /etc/hysteria/test.log 2>&1 &
  sleep 1; tail -2 /etc/hysteria/test.log'
