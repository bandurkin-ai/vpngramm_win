import sys, json, urllib.parse as u
# Конфигурации клиентов из ссылок приложения: hysteria2:// → hysteria client, vless:// → xray client
hy, vl = [l.strip() for l in open(sys.argv[1]) if l.strip()]
out = sys.argv[2]
p = u.urlsplit(hy); q = dict(u.parse_qsl(p.query))
open(out + '/hy.yaml', 'w').write(f"""server: {p.hostname}:{p.port}
auth: {u.unquote(p.username)}
obfs:
  type: {q['obfs']}
  salamander:
    password: {q['obfs-password']}
tls:
  sni: {q['sni']}
  insecure: true
socks5:
  listen: 127.0.0.1:1081
""")
# ссылка целиком, как её импортирует клиент; insecure — только из-за тестового сертификата
open(out + '/hy_uri.yaml', 'w').write(f"server: {hy.split('#')[0]}&insecure=1\nsocks5:\n  listen: 127.0.0.1:1082\n")
p = u.urlsplit(vl); q = dict(u.parse_qsl(p.query))
cfg = {"log": {"loglevel": "warning"},
       "inbounds": [{"port": 1080, "listen": "127.0.0.1", "protocol": "socks", "settings": {"udp": True}}],
       "outbounds": [{"protocol": "vless", "settings": {"vnext": [{"address": p.hostname, "port": p.port,
                      "users": [{"id": p.username, "encryption": q["encryption"], "flow": q["flow"]}]}]},
                      "streamSettings": {"network": q["type"], "security": q["security"],
                                         "realitySettings": {"serverName": q["sni"], "fingerprint": q["fp"],
                                                             "publicKey": q["pbk"], "shortId": q["sid"]}}}]}
json.dump(cfg, open(out + '/vl.json', 'w'), indent=1)
