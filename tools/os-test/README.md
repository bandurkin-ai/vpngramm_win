# Проверка скриптов на Debian 12 и Ubuntu 22.04 / 24.04 / 26.04

Контейнеры Docker с systemd и sshd (`Dockerfile.ubuntu`, `Dockerfile.debian`), запуск: `up.sh <u22.04|u24.04|u26.04|d12>`.
`run.sh <имя>` выполняет скрипты из `app/build/scripts` (их создаёт `ScriptsTest`): установка Hysteria2 (с доменом
и без), VLESS + Reality, Telegram (mtg), экспорт ссылок, статус, рестарт, удаление.
Безопасность — через настоящий SSH: `HARDEN_TEST=1 HARDEN_UPDATES=1 HARDEN_CONTAINER=os-u24.04 HARDEN_HOST=<ip> HARDEN_PORT=22
./gradlew testDebugUnitTest --tests app.hy2setup.HardenerTest --rerun`.

Debian 12 собирается из образов Docker Hub (`repack.sh` переупаковывает openssh-server, curl, unzip), так как в среде
сборки зеркала Debian были недоступны. Для Ubuntu 26.04 на хосте с cgroup v1 `up.sh` монтирует cgroup v2 внутри контейнера.

`run_plus.sh <имя>` — проверка «Комбайна+»: Hysteria2 со своим сертификатом из профиля (в хранилище certmagic, без
Let's Encrypt), VLESS с заранее заданными ключами Reality (публичный ключ сверяется через `xray x25519 -i`),
чтение копии сертификата обратно и удаление. Тестовый сертификат: `openssl req -x509 ... -subj /CN=vpn.example.com`
в `/tmp/ostest/plus/vpn.{crt,key,json}`.

`e2e_all.sh <имя> [iptables|nocert]` — сквозной тест настоящими клиентами: сервер ставится скриптами Комбайна+,
отдельный контейнер-клиент запускает hysteria и xray по ссылкам приложения (`links_plus.txt`, их пишет `ScriptsTest`;
`mkclient.py` превращает ссылки в конфигурации) и открывает http://archive.ubuntu.com/ubuntu/ через туннель.
`iptables` — перед установкой входящие закрыты политикой DROP; `nocert` — нет сертификата Let's Encrypt.
В конце запускается `diagnose.sh`.

`run_live.sh <имя>` — «Живая проверка» скриптами приложения (`live_check.sh`, `live_result.sh` из `ScriptsTest`):
верные ссылки → «работает», неверный ключ Reality и пароль Hysteria2 → «ссылка не совпадает», без клиента →
«не дошёл». Проверяется, что config.json Xray восстановлен побайтно, временный drop-in удалён, службы active.
Клиентские конфигурации с неверными ключами лежат в /tmp/ostest/work-live (vl_bad.json, hy_badauth.yaml и др.).

`run_self.sh <имя>` — Reality «под себя»: Hysteria2 с `masquerade.listenHTTPS: 127.0.0.1:8443`, VLESS с SNI = свой
домен и dest = 127.0.0.1:8443. Проверяется трафик через оба протокола и что посторонний TLS к серверу с этим SNI
получает сертификат домена (так сервер выглядит обычным сайтом).
`LiveCheckSshTest` — живая проверка через настоящий SSH приложения (сжатая загрузка скриптов): `LIVE_HOST=<ip>`.
Вкладка «Сервер», VLESS под свой домен: `PRECERT=1 HYSRC=server_self_hy2.sh VLSRC=server_self_vless.sh run_self.sh <имя>`
(PRECERT кладёт тестовый сертификат, будто Let's Encrypt его уже выдал; ключ Reality берётся из вывода сервера).
