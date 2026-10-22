package app.hy2setup

/** Параметры установки Hysteria2 на сервер. */
data class InstallParams(
    val port: Int,
    val authPassword: String,
    val obfsPassword: String,
    /** Если пусто — используется самоподписанный сертификат. */
    val domain: String,
    val email: String,
    /** SNI для самоподписанного сертификата. */
    val sni: String,
    val masqueradeUrl: String,
    /** Публичный IPv4 сервера. Если задан вместе с доменом, скрипт дождётся обновления DNS. */
    val expectedIp: String = "",
    /** ACME только через HTTP-01 (порт 80): TCP 443 занят Xray (Комбайн+). */
    val acmeHttp: Boolean = false,
    /** Сохранённый ранее сертификат Let's Encrypt (PEM) — ставится вместо нового выпуска. */
    val cert: CertBackup? = null,
    /** Отдавать сайт-маскировку ещё и по TCP на 127.0.0.1:8443 с сертификатом домена — для Reality «под себя». */
    val localHttps: Boolean = false,
    /** Несколько людей: «имя → пароль» (auth userpass); пусто — один пароль [authPassword]. */
    val users: Map<String, String> = emptyMap(),
    /** Сайт домена из каталога (страница и подписки) вместо проксирования [masqueradeUrl]; пусто — прокси. */
    val siteDir: String = "",
    /** Где сайт слушает HTTPS по TCP («127.0.0.1:8443» или «:443»); пусто — по [localHttps]. */
    val siteListen: String = "",
)

/** Протокол, который устанавливается на сервер. */
enum class Protocol(val title: String) {
    HY2("Hysteria2 + Salamander"),
    VLESS("VLESS + Reality"),
    AWG("AmneziaWG 3.1"),
}

/** Параметры установки VLESS + Reality. Ключи Reality генерируются на сервере. */
data class VlessParams(
    val port: Int,
    val uuid: String,
    /** Сайт, под который маскируется Reality (SNI), например www.microsoft.com. */
    val sni: String,
    val shortId: String,
    /** Адрес сервера для ссылки (домен или IP). */
    val address: String,
    /** Заданные заранее ключи Reality (Комбайн+); пусто — сгенерировать на сервере. */
    val privateKey: String = "",
    val publicKey: String = "",
    /** Куда Reality отправляет чужие подключения; пусто — «SNI:443». Для «под себя» — 127.0.0.1:8443 (Hysteria2). */
    val dest: String = "",
    /** UUID остальных людей (у каждого свой ключ). */
    val extraUuids: List<String> = emptyList(),
)

/** Bash-скрипты, которые приложение выполняет на VPS по SSH. */
object Scripts {

    /** Маркер, по которому приложение вытаскивает отпечаток сертификата из вывода. */
    const val PIN_MARKER = "##PIN="

    private val HEADER = """
        #!/bin/bash
        trap 'rm -f "${'$'}0"' EXIT
        exec </dev/null
        export DEBIAN_FRONTEND=noninteractive
        step() { echo; echo "==> ${'$'}1"; }
        if [ "${'$'}(id -u)" != "0" ]; then echo "ОШИБКА: нужны права root"; exit 1; fi
    """.trimIndent()

    /** Блок auth для config.yaml: один пароль или userpass для нескольких людей (с отступом heredoc). */
    internal fun hy2Auth(p: InstallParams): String {
        val pad = "        "
        if (p.users.isEmpty()) return "${pad}auth:\n$pad  type: password\n$pad  password: \$AUTH_PW"
        require(p.users.all { (u, pw) -> SAFE_TOKEN.matches(u) && SAFE_TOKEN.matches(pw) }) { "Недопустимые символы в имени или пароле Hysteria2" }
        return "${pad}auth:\n$pad  type: userpass\n$pad  userpass:\n" + p.users.entries.joinToString("\n") { (u, pw) -> "$pad    $u: $pw" }
    }

    /** Блок masquerade: сайт из каталога (с подписками) или прокси на чужой сайт. */
    internal fun hy2Masquerade(p: InstallParams): String {
        val pad = "        "
        val listen = p.siteListen.ifEmpty { if (p.localHttps) "127.0.0.1:8443" else "" }
        val body = if (p.siteDir.isNotEmpty()) "${pad}masquerade:\n$pad  type: file\n$pad  file:\n$pad    dir: ${p.siteDir}"
        else "${pad}masquerade:\n$pad  type: proxy\n$pad  proxy:\n$pad    url: \$MASQ\n$pad    rewriteHost: true"
        return body + if (listen.isNotEmpty()) "\n$pad  listenHTTPS: $listen" else ""
    }

    private val SAFE_TOKEN = Regex("^[A-Za-z0-9_-]+$")

    fun install(p: InstallParams): String = HEADER + "\n" + """
        set -e
        PORT=${p.port}
        AUTH_PW='${p.authPassword}'
        OBFS_PW='${p.obfsPassword}'
        DOMAIN='${p.domain}'
        EMAIL='${p.email}'
        SNI='${p.sni}'
        MASQ='${p.masqueradeUrl}'
        EXPECTED_IP='${p.expectedIp}'
        ACME_HTTP=${if (p.acmeHttp) 1 else 0}
        ACME_DIR=/var/lib/hysteria/acme

        step "Проверка системы"
        if ! command -v systemctl >/dev/null 2>&1; then
            echo "ОШИБКА: нужен systemd (Ubuntu/Debian/CentOS и т.п.)"; exit 1
        fi
        pkg_install() {
            if command -v apt-get >/dev/null 2>&1; then
                apt-get -o DPkg::Lock::Timeout=600 update -qq && apt-get -o DPkg::Lock::Timeout=600 install -y -qq "${'$'}@"
            elif command -v dnf >/dev/null 2>&1; then
                dnf install -y -q "${'$'}@"
            elif command -v yum >/dev/null 2>&1; then
                yum install -y -q "${'$'}@"
            else
                echo "ОШИБКА: не найден менеджер пакетов, установите вручную: ${'$'}@"; exit 1
            fi
        }
        command -v curl >/dev/null 2>&1 || pkg_install curl ca-certificates
        command -v openssl >/dev/null 2>&1 || pkg_install openssl
        echo "OK"

        step "Установка Hysteria2 (официальный скрипт get.hy2.sh)"
        TERM=xterm bash <(curl -fsSL https://get.hy2.sh/)
        if ! [ -x /usr/local/bin/hysteria ]; then
            echo "ОШИБКА: Hysteria2 не установилась — не удалось скачать установщик (get.hy2.sh). Проверьте интернет на сервере."
            exit 1
        fi

        step "Запись конфигурации /etc/hysteria/config.yaml"
        mkdir -p /etc/hysteria
        SITE='${p.siteDir}'
        if [ -n "${'$'}SITE" ]; then
            # сайт домена отдаётся из каталога; страницу и подписки пишет приложение, здесь — только заглушка
            mkdir -p "${'$'}SITE/s"
            [ -f "${'$'}SITE/index.html" ] || echo '<!doctype html><title>Welcome</title><p>This site is under construction.</p>' > "${'$'}SITE/index.html"
            [ -f "${'$'}SITE/s/index.html" ] || cp "${'$'}SITE/index.html" "${'$'}SITE/s/index.html"
            chmod -R a+rX "${'$'}SITE"
        fi
        if [ -z "${'$'}DOMAIN" ]; then
            echo "Домена нет — выпускаю самоподписанный сертификат (CN=${'$'}SNI)"
            openssl req -x509 -nodes -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
                -keyout /etc/hysteria/server.key -out /etc/hysteria/server.crt \
                -subj "/CN=${'$'}SNI" -days 36500 >/dev/null 2>&1
            chmod 600 /etc/hysteria/server.key
            if id hysteria >/dev/null 2>&1; then
                chown hysteria:hysteria /etc/hysteria/server.key /etc/hysteria/server.crt
            fi
            PIN=${'$'}(openssl x509 -noout -fingerprint -sha256 -in /etc/hysteria/server.crt | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f')
            echo "${PIN_MARKER}${'$'}PIN"
        fi
        {
            echo "listen: :${'$'}PORT"
            echo
            if [ -n "${'$'}DOMAIN" ]; then
                echo "acme:"
                echo "  domains:"
                echo "    - ${'$'}DOMAIN"
                if [ -n "${'$'}EMAIL" ]; then echo "  email: ${'$'}EMAIL"; fi
                if [ "${'$'}ACME_HTTP" = "1" ]; then
                    echo "  type: http"
                    echo "  dir: ${'$'}ACME_DIR"
                fi
            else
                echo "tls:"
                echo "  cert: /etc/hysteria/server.crt"
                echo "  key: /etc/hysteria/server.key"
            fi
            cat <<EOF

${hy2Auth(p)}

        obfs:
          type: salamander
          salamander:
            password: ${'$'}OBFS_PW

${hy2Masquerade(p)}

        # выход в интернет только по IPv4: у VPS IPv4 есть всегда, а IPv6 бывает настроен, но не работать
        outbounds:
          - name: v4
            type: direct
            direct:
              mode: 4
        EOF
        } > /etc/hysteria/config.yaml
        chmod 640 /etc/hysteria/config.yaml
        if id hysteria >/dev/null 2>&1; then chown root:hysteria /etc/hysteria/config.yaml; fi
        echo "OK"
${acmePrepare(p)}
        FW_PORTS="${'$'}PORT/udp"
        if [ -n "${'$'}DOMAIN" ]; then FW_PORTS="${'$'}FW_PORTS 80/tcp"; fi
${firewall()}
        echo "ВАЖНО: если у хостинга есть свой файрвол (Security Group), откройте там: ${'$'}FW_PORTS."

        if [ -n "${'$'}DOMAIN" ] && [ -n "${'$'}EXPECTED_IP" ]; then
            step "Ожидание DNS: ${'$'}DOMAIN -> ${'$'}EXPECTED_IP"
            DNS_OK=0
            # публичный DNS (его же спросит Let's Encrypt), а не /etc/hosts: хостер часто пишет туда имя как 127.0.0.1
            pub_a() {
                for R in "https://dns.google/resolve" "https://cloudflare-dns.com/dns-query"; do
                    A=${'$'}(curl -s -m 6 -H "accept: application/dns-json" "${'$'}R?name=${'$'}DOMAIN&type=A" 2>/dev/null |
                        grep -oE '"data": ?"[0-9]+(\.[0-9]+){3}"' | grep -oE '[0-9]+(\.[0-9]+){3}')
                    [ -n "${'$'}A" ] && { echo "${'$'}A"; return; }
                done
                getent ahostsv4 "${'$'}DOMAIN" 2>/dev/null | awk '${'$'}1 !~ /^127\./ {print ${'$'}1}'
            }
            for i in ${'$'}(seq 1 24); do
                if pub_a | grep -qx "${'$'}EXPECTED_IP"; then
                    DNS_OK=1; break
                fi
                sleep 5
            done
            if [ "${'$'}DNS_OK" = "1" ]; then
                echo "DNS обновился."
            else
                echo "ПРЕДУПРЕЖДЕНИЕ: за 2 минуты имя ещё не стало указывать на этот сервер."
                echo "Продолжаю: сертификат Let's Encrypt будет получен, когда DNS обновится."
            fi
        fi

        step "Запуск службы hysteria-server"
        # Hysteria завершается, если сертификат ещё не выдан (DNS не обновился) — пусть systemd повторяет
        mkdir -p /etc/systemd/system/hysteria-server.service.d
        printf '[Unit]\nStartLimitIntervalSec=0\n\n[Service]\nRestart=on-failure\nRestartSec=30\n' \
            > /etc/systemd/system/hysteria-server.service.d/hy2setup.conf
        systemctl daemon-reload
        systemctl enable hysteria-server.service >/dev/null 2>&1 || true
        systemctl restart hysteria-server.service
        sleep 5
        if systemctl is-active --quiet hysteria-server.service; then
            echo "Служба работает."
        elif [ -n "${'$'}DOMAIN" ] && [ "${'$'}{DNS_OK:-1}" = "0" ]; then
            echo "ПРЕДУПРЕЖДЕНИЕ: служба ждёт сертификат Let's Encrypt — DNS ещё не указывает на сервер."
            echo "Служба сама повторяет попытку каждые 30 секунд; ссылка заработает, когда DNS обновится."
        else
            echo "ОШИБКА: служба не запустилась. Последние строки журнала:"
            journalctl -u hysteria-server.service -n 25 --no-pager || true
            exit 1
        fi

        step "Готово"
    """.trimIndent()

    /**
     * Открывает порты из ${'$'}FW_PORTS («443/udp 80/tcp») в локальном файрволе: ufw, firewalld,
     * а если их нет — в iptables/nftables, когда там запрещающая политика (так бывает в шаблонах хостеров).
     */
    private fun firewall(): String = """
        step "Настройка файрвола"
        FW_DONE=""
        if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
            for P in ${'$'}FW_PORTS; do ufw allow "${'$'}P" >/dev/null; done
            FW_DONE="ufw"
        elif command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
            for P in ${'$'}FW_PORTS; do firewall-cmd --permanent --add-port="${'$'}P" >/dev/null; done
            firewall-cmd --reload >/dev/null
            FW_DONE="firewalld"
        else
            if command -v iptables >/dev/null 2>&1 && iptables -S INPUT 2>/dev/null | grep -qE '^-P INPUT (DROP|REJECT)|-j (DROP|REJECT)'; then
                for P in ${'$'}FW_PORTS; do
                    iptables -C INPUT -p "${'$'}{P#*/}" --dport "${'$'}{P%/*}" -j ACCEPT 2>/dev/null ||
                        iptables -I INPUT -p "${'$'}{P#*/}" --dport "${'$'}{P%/*}" -j ACCEPT
                done
                if command -v netfilter-persistent >/dev/null 2>&1; then netfilter-persistent save >/dev/null 2>&1 || true
                elif [ -d /etc/iptables ]; then iptables-save > /etc/iptables/rules.v4
                elif [ -f /etc/sysconfig/iptables ]; then iptables-save > /etc/sysconfig/iptables
                fi
                FW_DONE="iptables"
            fi
            if command -v nft >/dev/null 2>&1 && nft list chain inet filter input 2>/dev/null | grep -q "policy drop"; then
                for P in ${'$'}FW_PORTS; do
                    nft list chain inet filter input | grep -q "${'$'}{P#*/} dport ${'$'}{P%/*} accept" ||
                        nft insert rule inet filter input "${'$'}{P#*/}" dport "${'$'}{P%/*}" accept
                done
                if [ -f /etc/nftables.conf ]; then { echo "#!/usr/sbin/nft -f"; echo "flush ruleset"; nft list ruleset; } > /etc/nftables.conf; fi
                FW_DONE="${'$'}{FW_DONE:+${'$'}FW_DONE, }nftables"
            fi
        fi
        if [ -n "${'$'}FW_DONE" ]; then
            echo "Открыты порты ${'$'}FW_PORTS (${'$'}FW_DONE)."
        else
            echo "Локальный файрвол не ограничивает входящие — менять ничего не нужно."
        fi
    """.trimIndent().lines().joinToString("\n") { "        $it" }

    /**
     * Диагностика «подключение есть, интернета нет»: службы, порты, сертификат, выход сервера в интернет,
     * DNS имени, сайт Reality, время, файрвол. Каждая строка начинается с «OK:», «ПРОБЛЕМА:» или «ИНФО:».
     */
    fun diagnose(fqdn: String = ""): String = HEADER + "\n" + """
        F='$fqdn'
        ok() { echo "OK: ${'$'}*"; }
        bad() { echo "ПРОБЛЕМА: ${'$'}*"; }
        info() { echo "ИНФО: ${'$'}*"; }
        listening() { ss -H "-ln${'$'}1" "sport = :${'$'}2" 2>/dev/null | grep -q .; }

        step "Службы"
        for U in hysteria-server xray mtg awg-quick@awg0; do
            systemctl cat "${'$'}U.service" >/dev/null 2>&1 || continue
            ST=${'$'}(systemctl is-active "${'$'}U.service" 2>/dev/null || true)
            NR=${'$'}(systemctl show -p NRestarts --value "${'$'}U.service" 2>/dev/null || echo 0)
            if [ "${'$'}ST" = "active" ] && [ "${'$'}{NR:-0}" -lt 3 ]; then ok "служба ${'$'}U работает"
            elif [ "${'$'}ST" = "active" ]; then bad "служба ${'$'}U перезапускалась ${'$'}NR раз — она падает (журнал ниже)"
            else bad "служба ${'$'}U не работает (${'$'}ST)"; fi
        done
        if systemctl cat hysteria-server.service >/dev/null 2>&1; then
            listening u 443 && ok "Hysteria2 слушает 443/UDP" || bad "никто не слушает 443/UDP — Hysteria2 не принимает подключения"
            ERR=${'$'}(journalctl -u hysteria-server --since "-15 min" --no-pager 2>/dev/null | grep -E "FATAL|ERROR" | tail -3)
            if [ -n "${'$'}ERR" ]; then
                echo "${'$'}ERR" | grep -q "obtaining certificate\|acme" && bad "Hysteria2 не получила сертификат Let's Encrypt"
                echo "${'$'}ERR" | sed 's/^/    /' | cut -c1-300
            fi
        fi
        if systemctl cat xray.service >/dev/null 2>&1; then
            listening t 443 && ok "VLESS (Xray) слушает 443/TCP" || bad "никто не слушает 443/TCP — VLESS не принимает подключения"
            /usr/local/bin/xray run -test -c /usr/local/etc/xray/config.json >/dev/null 2>&1 && ok "конфигурация Xray верна" || bad "конфигурация Xray с ошибкой"
        fi
        if [ -f /etc/amnezia/amneziawg/awg0.conf ]; then
            AP=${'$'}(sed -n 's/^ListenPort *= *//p' /etc/amnezia/amneziawg/awg0.conf | head -1)
            lsmod | grep -q '^amneziawg' && ok "модуль ядра AmneziaWG загружен" || bad "модуль ядра AmneziaWG не загружен (ядро ${'$'}(uname -r))"
            ip link show awg0 >/dev/null 2>&1 && ok "интерфейс awg0 поднят, порт ${'$'}AP/UDP" || bad "интерфейс awg0 не поднят"
            HS=${'$'}(awg show awg0 latest-handshakes 2>/dev/null | awk '{print ${'$'}2}' | sort -n | tail -1)
            if [ -n "${'$'}HS" ] && [ "${'$'}HS" != "0" ]; then info "AmneziaWG: последнее подключение клиента ${'$'}(( ${'$'}(date +%s) - HS )) с назад"
            else info "AmneziaWG: клиент ещё не подключался"; fi
        fi

        if [ -n "${'$'}F" ]; then
            step "Сертификат ${'$'}F"
            CRT=/var/lib/hysteria/acme/certificates/acme-v02.api.letsencrypt.org-directory/${'$'}F/${'$'}F.crt
            if [ -s "${'$'}CRT" ]; then
                END=${'$'}(openssl x509 -noout -enddate -in "${'$'}CRT" 2>/dev/null | cut -d= -f2)
                if openssl x509 -checkend 0 -noout -in "${'$'}CRT" >/dev/null 2>&1; then ok "сертификат есть, действует до ${'$'}END"
                else bad "сертификат просрочен (${'$'}END)"; fi
            elif grep -q "acme:" /etc/hysteria/config.yaml 2>/dev/null; then
                bad "сертификата Let's Encrypt нет — без него Hysteria2 не работает (нужны открытый 80/TCP и верный DNS)"
            fi
            listening t 80 && info "порт 80/TCP слушает: ${'$'}(ss -ltnpH 'sport = :80' | grep -o 'users:(("[^"]*' | cut -d'"' -f2 | head -1)"

            step "DNS имени ${'$'}F"
            MYIP=${'$'}(curl -4 -s -m 8 https://api.ipify.org 2>/dev/null || true)
            # публичный DNS, а не /etc/hosts: хостер часто прописывает там имя сервера как 127.0.1.1
            doh() {
                for R in "https://dns.google/resolve" "https://cloudflare-dns.com/dns-query"; do
                    A=${'$'}(curl -s -m 8 -H "accept: application/dns-json" "${'$'}R?name=${'$'}F&type=A" 2>/dev/null |
                        grep -oE '"data": ?"[0-9]+(\.[0-9]+){3}"' | grep -oE '[0-9]+(\.[0-9]+){3}' | head -1)
                    [ -n "${'$'}A" ] && { echo "${'$'}A"; return; }
                done
            }
            DNSIP=${'$'}(doh)
            if [ -z "${'$'}MYIP" ]; then info "внешний IPv4 сервера узнать не удалось"
            elif [ -z "${'$'}DNSIP" ]; then bad "публичные DNS не знают имя ${'$'}F"
            elif [ "${'$'}DNSIP" = "${'$'}MYIP" ]; then ok "${'$'}F → ${'$'}MYIP (этот сервер, по данным публичных DNS)"
            else bad "${'$'}F указывает на ${'$'}DNSIP, а у сервера IPv4 ${'$'}MYIP"; fi
            HOSTSIP=${'$'}(awk -v n="${'$'}F" '${'$'}1 !~ /^#/ { for (i = 2; i <= NF; i++) if (${'$'}i == n) { print ${'$'}1; exit } }' /etc/hosts 2>/dev/null)
            case "${'$'}HOSTSIP" in 127.*|::1) info "в /etc/hosts имя ${'$'}F указано как ${'$'}HOSTSIP — так прописал хостер, клиентам это не мешает" ;; esac
        fi

        step "Выход сервера в интернет"
        getent ahostsv4 www.google.com >/dev/null 2>&1 && ok "DNS на сервере работает" || bad "сервер не может разрешать имена (DNS) — проверьте /etc/resolv.conf"
        C4=${'$'}(curl -4 -s -o /dev/null -m 10 -w '%{http_code}' https://www.google.com/generate_204 2>/dev/null || true)
        [ "${'$'}C4" = "204" ] && ok "IPv4: интернет есть" || bad "IPv4: сервер не выходит в интернет (ответ ${'$'}{C4:-нет})"
        if ip -6 route show default 2>/dev/null | grep -q .; then
            C6=${'$'}(curl -6 -s -o /dev/null -m 10 -w '%{http_code}' https://www.google.com/generate_204 2>/dev/null || true)
            [ "${'$'}C6" = "204" ] && ok "IPv6: интернет есть" || info "IPv6 настроен, но не работает — прокси выходят только по IPv4, это не мешает"
        fi
        if [ -f /usr/local/etc/xray/hy2setup.env ]; then
            RS=${'$'}(grep '^SNI=' /usr/local/etc/xray/hy2setup.env | cut -d= -f2)
            if [ -n "${'$'}RS" ]; then
                CR=${'$'}(curl -4 -s -o /dev/null -m 10 -w '%{http_code}' "https://${'$'}RS/" 2>/dev/null || true)
                [ -n "${'$'}CR" ] && [ "${'$'}CR" != "000" ] && ok "сайт маскировки Reality ${'$'}RS доступен" || bad "сайт маскировки Reality ${'$'}RS недоступен с сервера — VLESS не будет работать"
            fi
        fi

        step "Подключения клиентов (за 30 минут)"
        info "проверка честная, только если перед ней вы включили ссылку в Happ и открыли любой сайт"
        if systemctl cat hysteria-server.service >/dev/null 2>&1; then
            HC=${'$'}(journalctl -u hysteria-server --since "-30 min" --no-pager 2>/dev/null | grep -c "client connected")
            if [ "${'$'}{HC:-0}" -gt 0 ]; then
                LA=${'$'}(journalctl -u hysteria-server --since "-30 min" --no-pager 2>/dev/null | grep "client connected" | tail -1 | grep -o '"addr": *"[^"]*"' | cut -d'"' -f4)
                ok "Hysteria2: клиенты подключаются — подключений: ${'$'}HC, последнее с ${'$'}{LA:-?}"
            else
                info "Hysteria2: подключений не было — трафик телефона до сервера не доходит. Похоже, оператор блокирует UDP (так часто бывает на мобильном интернете). Используйте VLESS или Wi-Fi"
            fi
        fi
        if [ -f /var/log/xray/access.log ]; then
            SINCE=${'$'}(date -d "-30 min" '+%Y/%m/%d %H:%M:%S' 2>/dev/null)
            XA=${'$'}(awk -v s="${'$'}SINCE" '(${'$'}1 " " substr(${'$'}2, 1, 8)) >= s && /accepted/' /var/log/xray/access.log 2>/dev/null | wc -l)
            if [ "${'$'}{XA:-0}" -gt 0 ]; then
                LX=${'$'}(grep accepted /var/log/xray/access.log | tail -1 | awk '{print ${'$'}4}')
                ok "VLESS: клиенты подключаются — открыто соединений: ${'$'}XA, последнее с ${'$'}{LX:-?}"
            else
                info "VLESS: подключений не было — трафик телефона до сервера не доходит (или ссылку VLESS ещё не включали)"
            fi
        elif systemctl cat xray.service >/dev/null 2>&1; then
            info "VLESS: журнал подключений не включён (сервер установлен старой версией) — переустановите VLESS"
        fi

        step "Время и файрвол"
        if [ "${'$'}(timedatectl show -p NTPSynchronized --value 2>/dev/null)" = "yes" ]; then ok "время синхронизировано"
        else info "время не синхронизировано по NTP: ${'$'}(date -u '+%F %T') UTC"; fi
        if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
            for P in 443/udp 443/tcp 80/tcp; do ufw status | grep -q "^${'$'}P " && ok "ufw: ${'$'}P открыт" || bad "ufw включён, но ${'$'}P не открыт"; done
        elif command -v iptables >/dev/null 2>&1 && iptables -S INPUT 2>/dev/null | grep -qE '^-P INPUT (DROP|REJECT)'; then
            for P in 443/udp 443/tcp 80/tcp; do
                iptables -C INPUT -p "${'$'}{P#*/}" --dport "${'$'}{P%/*}" -j ACCEPT 2>/dev/null && ok "iptables: ${'$'}P открыт" || bad "iptables запрещает входящие, ${'$'}P не открыт"
            done
        else
            ok "локальный файрвол не ограничивает входящие"
        fi
        info "если всё OK, а интернета нет — проверьте файрвол в панели хостинга: должны быть открыты 443/UDP, 443/TCP и 80/TCP"
    """.trimIndent()

    const val LIVE_STARTED = "##LIVE_STARTED"

    /**
     * Запускает «живую проверку» на [seconds] секунд отдельной службой systemd, чтобы она не зависела от SSH-сессии
     * (пользователь в это время сворачивает приложение и включает Happ). [phoneIp] — публичный IP телефона без VPN.
     * Сам сборщик — ресурс hy2setup/live_collect.sh; итог потом читает [liveResult].
     */
    fun liveCheck(seconds: Int, phoneIp: String): String {
        val ip = phoneIp.takeIf { Regex("^[0-9a-fA-F.:]{3,45}$").matches(it) }.orEmpty()
        val collector = Scripts::class.java.getResourceAsStream("/hy2setup/live_collect.sh")!!
            .bufferedReader().use { it.readText() }
            .replace("__SECS__", seconds.coerceIn(20, 300).toString())
            .replace("__PHONE__", ip)
        return HEADER + "\n" + """
            systemctl stop hy2setup-live.service 2>/dev/null || true
            systemctl reset-failed hy2setup-live.service 2>/dev/null || true
            rm -f /root/.hy2setup-live.log
            echo '${gzB64(collector)}' | base64 -d | gunzip > /root/.hy2setup-live.sh
            if command -v systemd-run >/dev/null 2>&1; then
                systemd-run --quiet --unit=hy2setup-live --collect bash /root/.hy2setup-live.sh
            else
                setsid nohup bash /root/.hy2setup-live.sh >/dev/null 2>&1 < /dev/null &
            fi
            echo "$LIVE_STARTED"
        """.trimIndent()
    }

    /** Ждёт окончания живой проверки (до 2 минут), печатает итог и удаляет его с сервера. */
    fun liveResult(): String = HEADER + "\n" + """
        for i in ${'$'}(seq 1 60); do
            systemctl is-active --quiet hy2setup-live.service 2>/dev/null || pgrep -f hy2setup-live.sh >/dev/null || break
            sleep 2
        done
        if [ -s /root/.hy2setup-live.log ]; then
            cat /root/.hy2setup-live.log
            rm -f /root/.hy2setup-live.log
        else
            echo "ПРОБЛЕМА: результат живой проверки не найден — запустите её ещё раз"
        fi
    """.trimIndent()

    /** Путь сертификата в хранилище certmagic, которым пользуется Hysteria (ACME). */
    fun acmeCertDir(fqdn: String) =
        "/var/lib/hysteria/acme/certificates/acme-v02.api.letsencrypt.org-directory/$fqdn"

    /** Готовит каталог ACME и при наличии кладёт туда сохранённый сертификат. Строки с отступом 8 — под trimIndent. */
    private fun acmePrepare(p: InstallParams): String {
        if (!p.acmeHttp || p.domain.isEmpty()) return ""
        val b = StringBuilder()
        b.append("mkdir -p /var/lib/hysteria/acme\n")
        val c = p.cert
        if (c != null) {
            val dir = acmeCertDir(p.domain)
            b.append("step \"Сертификат Let's Encrypt — из постоянного профиля\"\n")
            b.append("mkdir -p '$dir'\n")
            b.append("echo '${b64(c.crt)}' | base64 -d > '$dir/${p.domain}.crt'\n")
            b.append("echo '${b64(c.key)}' | base64 -d > '$dir/${p.domain}.key'\n")
            if (c.meta.isNotEmpty()) b.append("echo '${b64(c.meta)}' | base64 -d > '$dir/${p.domain}.json'\n")
            b.append("chmod 600 '$dir/${p.domain}.key'\n")
            b.append("echo \"OK\"\n")
        }
        b.append("if id hysteria >/dev/null 2>&1; then chown -R hysteria:hysteria /var/lib/hysteria/acme; fi\n")
        b.append("if ss -ltnH 'sport = :80' 2>/dev/null | grep -q .; then\n")
        b.append("    echo \"ПРЕДУПРЕЖДЕНИЕ: TCP-порт 80 занят — Let's Encrypt не сможет проверить домен:\"; ss -ltnpH 'sport = :80' || true\n")
        b.append("fi\n")
        return b.lines().filter { it.isNotEmpty() }.joinToString("\n") { "        $it" }
    }

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    /** gzip + base64: так большой вложенный скрипт занимает втрое меньше (важно на путях с «заморозкой» ТСПУ). */
    private fun gzB64(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(s.toByteArray()) }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /**
     * Записывает сайт домена (страницу и подписки) в [dir]; лишние подписки (отключённых людей) удаляются.
     * Hysteria2 отдаёт каталог сразу, перезапуск не нужен.
     */
    fun writeSite(dir: String, files: Map<String, String>): String {
        require(files.keys.all { Regex("^[A-Za-z0-9._/-]+$").matches(it) && ".." !in it }) { "Недопустимое имя файла сайта" }
        val b = StringBuilder(HEADER).append("\n")
        b.append("set -e\nD='$dir'\nstep \"Сайт домена и подписки\"\n")
        b.append("mkdir -p \"${'$'}D/s\"\nfind \"${'$'}D/s\" -type f -delete 2>/dev/null || true\n")
        for ((name, text) in files) {
            b.append("echo '${gzB64(text)}' | base64 -d | gunzip > \"${'$'}D/$name\"\n")
        }
        b.append("chmod -R a+rX \"${'$'}D\"\n")
        b.append("echo \"Подписок: ${'$'}(ls \"${'$'}D/s\" | grep -vc '^index.html${'$'}')\"\n")
        return b.toString()
    }

    const val CRT_MARKER = "##CRT="
    const val KEY_MARKER = "##KEY="
    const val META_MARKER = "##META="

    /** Ждёт сертификат Let's Encrypt для [fqdn] (до 3 минут) и печатает его base64-маркерами. */
    fun exportCert(fqdn: String, waits: Int = 36): String = HEADER + "\n" + """
        D='${acmeCertDir(fqdn)}'
        F='$fqdn'
        step "Копия сертификата Let's Encrypt"
        for i in ${'$'}(seq 1 $waits); do
            [ -s "${'$'}D/${'$'}F.crt" ] && [ -s "${'$'}D/${'$'}F.key" ] && break
            sleep 5
        done
        if [ ! -s "${'$'}D/${'$'}F.crt" ]; then
            echo "ПРЕДУПРЕЖДЕНИЕ: сертификат ещё не выпущен (DNS или порт 80?) — копия не сохранена."
            exit 0
        fi
        echo "$CRT_MARKER${'$'}(base64 -w0 "${'$'}D/${'$'}F.crt")"
        echo "$KEY_MARKER${'$'}(base64 -w0 "${'$'}D/${'$'}F.key")"
        if [ -s "${'$'}D/${'$'}F.json" ]; then echo "$META_MARKER${'$'}(base64 -w0 "${'$'}D/${'$'}F.json")"; fi
        echo "Сертификат сохранён в приложении (действует до ${'$'}(openssl x509 -noout -enddate -in "${'$'}D/${'$'}F.crt" 2>/dev/null | cut -d= -f2))."
    """.trimIndent()

    // =====================================================================
    //  VLESS + Reality (Xray)
    // =====================================================================

    const val PBK_MARKER = "##PBK="

    fun installVless(p: VlessParams): String = HEADER + "\n" + """
        set -e
        PORT=${p.port}
        UUID='${p.uuid}'
        SNI='${p.sni}'
        SID='${p.shortId}'
        DEST='${p.dest.ifEmpty { "${p.sni}:443" }}'
        ADDR='${p.address}'
        XRAY=/usr/local/bin/xray
        CONF=/usr/local/etc/xray/config.json

        step "Проверка системы"
        if ! command -v systemctl >/dev/null 2>&1; then
            echo "ОШИБКА: нужен systemd (Ubuntu/Debian/CentOS и т.п.)"; exit 1
        fi
        pkg_install() {
            if command -v apt-get >/dev/null 2>&1; then
                apt-get -o DPkg::Lock::Timeout=600 update -qq && apt-get -o DPkg::Lock::Timeout=600 install -y -qq "${'$'}@"
            elif command -v dnf >/dev/null 2>&1; then
                dnf install -y -q "${'$'}@"
            elif command -v yum >/dev/null 2>&1; then
                yum install -y -q "${'$'}@"
            else
                echo "ОШИБКА: не найден менеджер пакетов, установите вручную: ${'$'}@"; exit 1
            fi
        }
        command -v curl >/dev/null 2>&1 || pkg_install curl ca-certificates
        command -v openssl >/dev/null 2>&1 || pkg_install openssl
        command -v unzip >/dev/null 2>&1 || pkg_install unzip
        if ss -ltnpH "sport = :${'$'}{PORT}" 2>/dev/null | grep -v '"xray"' | grep -q .; then
            echo "ПРЕДУПРЕЖДЕНИЕ: TCP-порт ${'$'}{PORT} уже занят другой программой:"
            ss -ltnpH "sport = :${'$'}{PORT}" || true
        fi
        echo "OK"

        step "Установка Xray (официальный скрипт XTLS/Xray-install)"
        # установщик вызывает tput под set -e; в SSH-сессии без терминала TERM не задан
        TERM=xterm bash -c "${'$'}(curl -fsSL https://raw.githubusercontent.com/XTLS/Xray-install/main/install-release.sh)" @ install
        if ! [ -x "${'$'}{XRAY}" ]; then
            echo "ОШИБКА: Xray не установился — не удалось скачать установщик или Xray с GitHub. Проверьте интернет на сервере."
            exit 1
        fi
        ${'$'}{XRAY} version | head -1

        PRIV='${p.privateKey}'
        PBK='${p.publicKey}'
        if [ -n "${'$'}{PRIV}" ] && [ -n "${'$'}{PBK}" ]; then
            step "Ключи Reality — из постоянного профиля"
        else
            step "Генерация ключей Reality"
            KEYS=${'$'}(${'$'}{XRAY} x25519)
            PRIV=${'$'}(echo "${'$'}{KEYS}" | awk -F': ' 'tolower(${'$'}1) ~ /private/ {print ${'$'}2; exit}')
            PBK=${'$'}(echo "${'$'}{KEYS}" | awk -F': ' 'tolower(${'$'}1) ~ /public/ {print ${'$'}2; exit}')
        fi
        if [ -z "${'$'}{PRIV}" ] || [ -z "${'$'}{PBK}" ]; then
            echo "ОШИБКА: не удалось разобрать вывод xray x25519:"; echo "${'$'}{KEYS}"; exit 1
        fi
        echo "OK"

        step "Запись конфигурации ${'$'}{CONF}"
        mkdir -p /usr/local/etc/xray
        cat > "${'$'}{CONF}" <<EOF
        {
          "log": { "loglevel": "warning", "access": "/var/log/xray/access.log" },
          "inbounds": [
            {
              "port": ${'$'}{PORT},
              "protocol": "vless",
              "settings": {
                "clients": [ ${(listOf("\${UUID}") + p.extraUuids).joinToString(", ") { "{ \"id\": \"$it\", \"flow\": \"xtls-rprx-vision\" }" }} ],
                "decryption": "none"
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": {
                  "dest": "${'$'}{DEST}",
                  "serverNames": [ "${'$'}{SNI}" ],
                  "privateKey": "${'$'}{PRIV}",
                  "shortIds": [ "${'$'}{SID}" ]
                }
              },
              "sniffing": { "enabled": true, "destOverride": [ "http", "tls", "quic" ], "routeOnly": true }
            }
          ],
          "outbounds": [
            { "protocol": "freedom", "tag": "direct", "settings": { "domainStrategy": "UseIPv4" } },
            { "protocol": "blackhole", "tag": "block" }
          ]
        }
        EOF
        XUSER=${'$'}(grep -E '^[[:space:]]*User[[:space:]]*=' /etc/systemd/system/xray.service 2>/dev/null | tail -1 | cut -d= -f2 | tr -d ' ')
        [ -z "${'$'}{XUSER}" ] && XUSER=root
        chgrp "${'$'}(id -gn "${'$'}{XUSER}")" "${'$'}{CONF}" && chmod 640 "${'$'}{CONF}"
        # журнал подключений — для «Проверить сервер» (видно, доходит ли трафик телефона)
        mkdir -p /var/log/xray && touch /var/log/xray/access.log
        chown -R "${'$'}{XUSER}:${'$'}(id -gn "${'$'}{XUSER}")" /var/log/xray
        printf '/var/log/xray/access.log {\n    weekly\n    rotate 2\n    missingok\n    notifempty\n    copytruncate\n}\n' \
            > /etc/logrotate.d/hy2setup-xray
        if ! TEST=${'$'}(${'$'}{XRAY} run -test -c "${'$'}{CONF}" 2>&1); then
            echo "ОШИБКА: Xray отклонил конфигурацию:"; echo "${'$'}{TEST}" | tail -5; exit 1
        fi
        echo "${'$'}{TEST}" | tail -1
        # данные для повторного получения ссылки
        umask 077
        cat > /usr/local/etc/xray/hy2setup.env <<EOF
        PORT=${'$'}{PORT}
        UUID=${'$'}{UUID}
        SNI=${'$'}{SNI}
        PBK=${'$'}{PBK}
        SID=${'$'}{SID}
        ADDR=${'$'}{ADDR}
        EOF
        echo "${PBK_MARKER}${'$'}{PBK}"
        echo "OK"

        FW_PORTS="${'$'}{PORT}/tcp"
${firewall()}
        echo "ВАЖНО: если у хостинга есть свой файрвол (Security Group), откройте TCP-порт ${'$'}{PORT} там."

        step "Запуск службы xray"
        systemctl enable xray.service >/dev/null 2>&1 || true
        systemctl restart xray.service
        sleep 2
        if systemctl is-active --quiet xray.service; then
            echo "Служба работает."
        else
            echo "ОШИБКА: служба не запустилась. Последние строки журнала:"
            journalctl -u xray.service -n 25 --no-pager || true
            exit 1
        fi

        step "Готово"
    """.trimIndent()

    // =====================================================================
    //  Обслуживание
    // =====================================================================

    /** Имя systemd-службы для протокола. */
    fun serviceOf(protocol: Protocol) = when (protocol) {
        Protocol.HY2 -> "hysteria-server.service"
        Protocol.VLESS -> "xray.service"
        Protocol.AWG -> "awg-quick@awg0.service"
    }

    fun status(): String = HEADER + "\n" + """
        show() {
            step "${'$'}1"
            if systemctl cat "${'$'}2" >/dev/null 2>&1; then
                echo "Служба: ${'$'}(systemctl is-active "${'$'}2" 2>/dev/null || true)"
                journalctl -u "${'$'}2" -n 8 --no-pager 2>/dev/null || true
            else
                echo "Не установлен."
            fi
        }
        show "Hysteria2 + Salamander" hysteria-server.service
        show "VLESS + Reality (Xray)" xray.service
        show "Telegram-прокси (mtg)" mtg.service
        show "AmneziaWG" awg-quick@awg0.service
        step "Слушающие порты"
        (ss -tulnp 2>/dev/null | grep -E 'hysteria|xray|mtg') || echo "не найдено"
    """.trimIndent()

    fun restart(protocol: Protocol): String = HEADER + "\n" + """
        step "Перезапуск ${serviceOf(protocol)}"
        systemctl restart ${serviceOf(protocol)}
        sleep 2
        systemctl is-active ${serviceOf(protocol)}
    """.trimIndent()

    fun remove(protocol: Protocol): String = HEADER + "\n" + when (protocol) {
        Protocol.HY2 -> """
            step "Остановка и удаление Hysteria2"
            systemctl disable --now hysteria-server.service >/dev/null 2>&1 || true
            bash <(curl -fsSL https://get.hy2.sh/) --remove || true
            rm -rf /etc/hysteria /etc/systemd/system/hysteria-server.service.d/hy2setup.conf
            systemctl daemon-reload 2>/dev/null || true
            step "Готово. Правила файрвола (если открывали) нужно удалить вручную."
        """.trimIndent()
        Protocol.VLESS -> """
            step "Остановка и удаление Xray (VLESS)"
            systemctl disable --now xray.service >/dev/null 2>&1 || true
            TERM=xterm bash -c "${'$'}(curl -fsSL https://raw.githubusercontent.com/XTLS/Xray-install/main/install-release.sh)" @ remove --purge || true
            rm -rf /usr/local/etc/xray
            step "Готово. Правила файрвола (если открывали) нужно удалить вручную."
        """.trimIndent()
        Protocol.AWG -> """
            step "Остановка и удаление AmneziaWG"
            systemctl disable --now awg-quick@awg0.service >/dev/null 2>&1 || true
            rm -f /etc/amnezia/amneziawg/awg0.conf $AWG_META
            echo "Конфигурация удалена (пакеты amneziawg остались — они ничего не делают без конфигурации)."
            step "Готово. Правила файрвола (если открывали) нужно удалить вручную."
        """.trimIndent()
    }

    const val LINK_MARKER_HY2 = "##HY2|"
    const val LINK_MARKER_VLESS = "##VLESS|"
    const val LINK_MARKER_AWG = "##AWG|"
    /** Параметры AmneziaWG на сервере (для «Получить ссылки»): JSON [AwgParams]. */
    const val AWG_META = "/etc/amnezia/amneziawg/vpngramm.json"

    /** Читает настройки установленных протоколов и печатает строки-маркеры для сборки ссылок. */
    fun exportLinks(): String = HEADER + "\n" + """
        FOUND=0
        C=/etc/hysteria/config.yaml
        if [ -f "${'$'}{C}" ]; then
            FOUND=1
            val() { awk -v sec="${'$'}1" -v key="${'$'}2" '/^[^ #]/{s=${'$'}1} s==sec && ${'$'}1==key {print ${'$'}2; exit}' "${'$'}{C}" | tr -d '"'"'"; }
            HPORT=${'$'}(sed -n 's/^listen: *[^:]*:\([0-9][0-9]*\).*/\1/p' "${'$'}{C}" | head -1)
            [ -z "${'$'}{HPORT}" ] && HPORT=443
            AUTH=${'$'}(val "auth:" "password:")
            OBFS=${'$'}(val "obfs:" "password:")
            DOMAIN=${'$'}(val "acme:" "-")
            CERT=${'$'}(val "tls:" "cert:")
            HSNI=""; PIN=""
            if [ -z "${'$'}{DOMAIN}" ] && [ -n "${'$'}{CERT}" ] && [ -f "${'$'}{CERT}" ]; then
                PIN=${'$'}(openssl x509 -noout -fingerprint -sha256 -in "${'$'}{CERT}" | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f')
                HSNI=${'$'}(openssl x509 -noout -subject -in "${'$'}{CERT}" | sed -n 's/.*CN *= *\([^,/]*\).*/\1/p')
            fi
            echo "${LINK_MARKER_HY2}${'$'}{HPORT}|${'$'}{AUTH}|${'$'}{OBFS}|${'$'}{DOMAIN}|${'$'}{HSNI}|${'$'}{PIN}"
            echo "Hysteria2: найдена конфигурация (порт ${'$'}{HPORT}/udp)."
        fi
        E=/usr/local/etc/xray/hy2setup.env
        if [ -f "${'$'}{E}" ]; then
            FOUND=1
            ( . "${'$'}{E}"; echo "${LINK_MARKER_VLESS}${'$'}{PORT}|${'$'}{UUID}|${'$'}{SNI}|${'$'}{PBK}|${'$'}{SID}|${'$'}{ADDR}"; echo "VLESS: найдена конфигурация (порт ${'$'}{PORT}/tcp)." )
        elif [ -f /usr/local/etc/xray/config.json ]; then
            echo "Xray установлен не этим приложением — собрать ссылку VLESS автоматически нельзя."
        fi
        if [ -f $AWG_META ]; then
            FOUND=1
            echo "$LINK_MARKER_AWG${'$'}(base64 -w0 $AWG_META)"
            echo "AmneziaWG: найдена конфигурация (порт ${'$'}(sed -n 's/^ListenPort *= *//p' /etc/amnezia/amneziawg/awg0.conf 2>/dev/null)/udp)."
        fi
        if [ -f /etc/mtg.toml ]; then
            FOUND=1
            TGSECRET=${'$'}(sed -n 's/^secret *= *"\(.*\)".*/\1/p' /etc/mtg.toml | head -1)
            TGPORT=${'$'}(sed -n 's/^bind-to *= *".*:\([0-9][0-9]*\)".*/\1/p' /etc/mtg.toml | head -1)
            echo "${LINK_MARKER_TG}${'$'}{TGPORT:-443}|${'$'}{TGSECRET}"
            echo "Telegram-прокси: найдена конфигурация (порт ${'$'}{TGPORT:-443}/tcp)."
        fi
        [ "${'$'}{FOUND}" = "1" ] || echo "На сервере не найдено протоколов, установленных этим приложением."
    """.trimIndent()

    // =====================================================================
    //  Защита сервера
    // =====================================================================

    const val REBOOT_MARKER = "##REBOOT"
    const val PING_MARKER = "##KEYOK"

    /** Обновления и автообновления (строки без отступа — для склейки скриптов). */
    private fun updatesBody(): String = """
        APT="apt-get -q -o DPkg::Lock::Timeout=600 -o Dpkg::Use-Pty=0 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
        step "Установка обновлений системы (может занять несколько минут)"
        if command -v apt-get >/dev/null 2>&1; then
            # если прошлая установка пакетов прервалась (например, оборвалась связь) — доделываем её
            dpkg --configure -a || true
            ${'$'}{APT} update
            ${'$'}{APT} -y full-upgrade
            ${'$'}{APT} -y autoremove
            step "Включение автообновлений (unattended-upgrades)"
            ${'$'}{APT} -y install unattended-upgrades
            cat > /etc/apt/apt.conf.d/20auto-upgrades <<EOF
        APT::Periodic::Update-Package-Lists "1";
        APT::Periodic::Unattended-Upgrade "1";
        APT::Periodic::AutocleanInterval "7";
        EOF
            systemctl enable --now unattended-upgrades >/dev/null 2>&1 || true
            echo "Автообновления безопасности включены."
        elif command -v dnf >/dev/null 2>&1; then
            dnf -y upgrade
            step "Включение автообновлений (dnf-automatic)"
            dnf -y install dnf-automatic
            sed -i 's/^apply_updates.*/apply_updates = yes/' /etc/dnf/automatic.conf
            systemctl enable --now dnf-automatic.timer
            echo "Автообновления включены."
        elif command -v yum >/dev/null 2>&1; then
            yum -y update
            step "Включение автообновлений (yum-cron)"
            yum -y install yum-cron
            sed -i 's/^apply_updates.*/apply_updates = yes/' /etc/yum/yum-cron.conf
            systemctl enable --now yum-cron
            echo "Автообновления включены."
        else
            echo "ПРЕДУПРЕЖДЕНИЕ: неизвестный менеджер пакетов — обновления пропущены."
        fi
    """.trimIndent()

    private fun rebootCheck(): String = """
        if [ -f /var/run/reboot-required ]; then
            echo "Обновления требуют перезагрузки."
            echo "$REBOOT_MARKER"
        elif command -v needs-restarting >/dev/null 2>&1 && ! needs-restarting -r >/dev/null 2>&1; then
            echo "Обновления требуют перезагрузки."
            echo "$REBOOT_MARKER"
        fi
    """.trimIndent()

    /**
     * Обновления системы и автообновления в рамках одной SSH-сессии. Вход по паролю здесь НЕ отключается —
     * это делает [disablePasswordAuth] только после проверки входа по ключу.
     * Длинные обновления запускаются через [updatesStart]: так обрыв связи их не прерывает.
     */
    fun harden(updates: Boolean = true): String = HEADER + "\n" +
        "set -e\nexport NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1\n" +
        (if (updates) updatesBody() + "\n" else "") + rebootCheck()

    const val UPD_DONE = "__UPD_DONE__"
    const val UPD_POS = "##UPD_POS"
    const val UPD_STARTED = "##UPD_STARTED"

    /** Тело фонового скрипта: bash с set -e и функцией step. */
    private fun jobBody(body: String) = "#!/bin/bash\nset -e\nexec </dev/null\n" +
        "export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a NEEDRESTART_SUSPEND=1\n" +
        "step() { echo; echo \"==> ${'$'}1\"; }\n" + body + "\n"

    /**
     * Запускает обновления отдельной службой systemd (hy2setup-upd): они идут на сервере, даже если SSH-соединение
     * с телефоном оборвалось. Ход читает [updatesPoll].
     */
    fun updatesStart(): String = jobStart("upd", jobBody(updatesBody() + "\n" + rebootCheck()), "Обновление уже идёт на сервере")

    fun updatesPoll(offset: Long): String = jobPoll("upd", offset)

    /**
     * Запускает [body] отдельной службой systemd (hy2setup-[name]): работа идёт на сервере, даже если SSH-соединение
     * с телефоном оборвалось. Вывод — в /root/.hy2setup-[name].log, в конце строка «[UPD_DONE] код». Ход читает [jobPoll].
     * Если задача уже идёт (запущена прошлой попыткой), новая не запускается.
     */
    fun jobStart(name: String, body: String, busy: String): String {
        val log = "/root/.hy2setup-$name.log"
        val job = "#!/bin/bash\nbash /root/.hy2setup-$name-body.sh >> $log 2>&1\necho \"$UPD_DONE ${'$'}?\" >> $log\n"
        return HEADER + "\n" + """
            if systemctl is-active --quiet hy2setup-$name.service 2>/dev/null || pgrep -f "[h]y2setup-$name-[j]ob" >/dev/null 2>&1; then
                echo "$busy — продолжаю следить за ним."
            else
                systemctl reset-failed hy2setup-$name.service 2>/dev/null || true
                rm -f $log
                echo '${gzB64(body)}' | base64 -d | gunzip > /root/.hy2setup-$name-body.sh
                echo '${gzB64(job)}' | base64 -d | gunzip > /root/.hy2setup-$name-job.sh
                : > $log
                if command -v systemd-run >/dev/null 2>&1; then
                    systemd-run --quiet --unit=hy2setup-$name --collect bash /root/.hy2setup-$name-job.sh
                else
                    setsid nohup bash /root/.hy2setup-$name-job.sh >/dev/null 2>&1 < /dev/null &
                fi
            fi
            echo "$UPD_STARTED"
        """.trimIndent()
    }

    /**
     * Печатает новые строки журнала задачи [name] начиная с байта [offset] (не больше ~3 КБ за раз — короткие ответы
     * не упираются в «заморозку» мобильных сетей), затем строку «[UPD_POS] новое_смещение состояние»,
     * где состояние — run, done:КОД или lost (задача пропала, например сервер перезагрузился).
     */
    fun jobPoll(name: String, offset: Long): String = HEADER + "\n" + """
        LOG=/root/.hy2setup-$name.log
        OFF=$offset
        T=${'$'}(mktemp)
        SIZE=${'$'}(stat -c %s "${'$'}LOG" 2>/dev/null || echo 0)
        if [ "${'$'}SIZE" -gt "${'$'}OFF" ]; then tail -c +${'$'}((OFF + 1)) "${'$'}LOG" | head -c 3000 > "${'$'}T"; fi
        N=${'$'}(stat -c %s "${'$'}T")
        # берём только целые строки; строку длиннее 3 КБ отдаём кусками
        K=${'$'}(LC_ALL=C awk -v n="${'$'}N" '{ s += length(${'$'}0) + 1; last = length(${'$'}0) + 1 } END { if (s == n) print s + 0; else print s - last }' "${'$'}T")
        [ "${'$'}K" -eq 0 ] && [ "${'$'}N" -ge 3000 ] && K=${'$'}N
        head -c "${'$'}K" "${'$'}T" | tr -d '\r' | grep -av '^(Reading database' || true
        rm -f "${'$'}T"
        NEW=${'$'}((OFF + K))
        if systemctl is-active --quiet hy2setup-$name.service 2>/dev/null || pgrep -f "[h]y2setup-$name-[j]ob" >/dev/null 2>&1; then
            STATE=run
        elif grep -q "^$UPD_DONE " "${'$'}LOG" 2>/dev/null; then
            STATE="done:${'$'}(grep "^$UPD_DONE " "${'$'}LOG" | tail -1 | awk '{print ${'$'}2}')"
        else
            STATE=lost
        fi
        # пока не дочитали журнал до конца — задача для нас ещё идёт
        [ "${'$'}NEW" -lt "${'$'}(stat -c %s "${'$'}LOG" 2>/dev/null || echo 0)" ] && STATE=run
        echo "$UPD_POS ${'$'}NEW ${'$'}STATE"
    """.trimIndent()

    /** Разбор строки «[UPD_POS] смещение состояние». */
    fun parseUpdPos(line: String): Pair<Long, String>? {
        val parts = line.trim().split(" ")
        if (parts.size != 3 || parts[0] != UPD_POS) return null
        val off = parts[1].toLongOrNull() ?: return null
        return off to parts[2]
    }

    // =====================================================================
    //  AmneziaWG 3.1 (модуль ядра через DKMS из PPA Amnezia)
    // =====================================================================

    /** Новое ядро поставлено — приложение перезагружает сервер и запускает установку ещё раз. */
    const val AWG_REBOOT = "##AWG_REBOOT"

    /**
     * Установка AmneziaWG фоновой задачей «awg» (сборка модуля идёт минуты, обрыв связи её не прерывает).
     * На Debian 12 с ядром старше 6.7 сначала ставится ядро из bookworm-backports и печатается [AWG_REBOOT].
     */
    fun installAwg(p: AwgParams): String {
        val key = Scripts::class.java.getResourceAsStream("/hy2setup/amnezia-ppa.asc")!!.bufferedReader().use { it.readText() }
        val body = """
            PORT=${p.port}
            step "Проверка системы"
            command -v systemctl >/dev/null 2>&1 || { echo "ОШИБКА: нужен systemd"; exit 1; }
            command -v apt-get >/dev/null 2>&1 || { echo "ОШИБКА: AmneziaWG ставится только на Debian/Ubuntu"; exit 1; }
            VIRT=${'$'}(systemd-detect-virt 2>/dev/null || true)
            case "${'$'}VIRT" in openvz|lxc|lxc-libvirt|docker|podman)
                echo "ОШИБКА: VPS на контейнерной виртуализации (${'$'}VIRT) — модуль ядра AmneziaWG на нём не установить."; exit 1;;
            esac
            . /etc/os-release
            ARCH=${'$'}(dpkg --print-architecture)
            KVER=${'$'}(uname -r)
            KMAJ=${'$'}(echo "${'$'}KVER" | cut -d. -f1); KMIN=${'$'}(echo "${'$'}KVER" | cut -d. -f2 | tr -cd '0-9')
            echo "${'$'}PRETTY_NAME, ядро ${'$'}KVER, виртуализация ${'$'}{VIRT:-нет}"
            if [ "${'$'}ID" = debian ] && [ "${'$'}{VERSION_ID%%.*}" = 12 ] && { [ "${'$'}KMAJ" -lt 6 ] || { [ "${'$'}KMAJ" -eq 6 ] && [ "${'$'}KMIN" -lt 7 ]; }; }; then
                step "Ядро ${'$'}KVER старее 6.7 — ставлю новое ядро из bookworm-backports"
                grep -rqs "bookworm-backports" /etc/apt/sources.list /etc/apt/sources.list.d/ || \
                    echo "deb http://deb.debian.org/debian bookworm-backports main" > /etc/apt/sources.list.d/bookworm-backports.list
                apt-get -q update
                FLAV=${'$'}ARCH
                case "${'$'}KVER" in *-cloud-*) FLAV="cloud-${'$'}ARCH";; esac
                apt-get -q -y -o Dpkg::Use-Pty=0 -t bookworm-backports install "linux-image-${'$'}FLAV" "linux-headers-${'$'}FLAV"
                echo "Новое ядро установлено — нужна перезагрузка."
                echo "$AWG_REBOOT"
                exit 0
            fi
            if [ "${'$'}KMAJ" -lt 6 ] || { [ "${'$'}KMAJ" -eq 6 ] && [ "${'$'}KMIN" -lt 7 ]; }; then
                echo "ПРЕДУПРЕЖДЕНИЕ: ядро ${'$'}KVER старее 6.7 — AmneziaWG 3.1 на нём почти не проверялся."
            fi

            step "Репозиторий Amnezia"
            apt-get -q -y -o Dpkg::Use-Pty=0 install gpg ca-certificates curl iptables
            mkdir -p /etc/apt/keyrings
            echo '${gzB64(key)}' | base64 -d | gunzip | gpg --dearmor --yes -o /etc/apt/keyrings/amnezia-ppa.gpg
            gpg --show-keys --with-colons /etc/apt/keyrings/amnezia-ppa.gpg 2>/dev/null | grep -q '75C9DD72C799870E310542E24166F2C257290828' || {
                echo "ОШИБКА: ключ репозитория Amnezia не совпал с ожидаемым"; exit 1; }
            case "${'$'}ID:${'$'}{VERSION_CODENAME:-}" in
                debian:bookworm) C=focal;;
                debian:*) C=noble;;
                ubuntu:*) C=${'$'}VERSION_CODENAME
                    curl -fsI "https://ppa.launchpadcontent.net/amnezia/ppa/ubuntu/dists/${'$'}C/Release" >/dev/null 2>&1 || C=noble;;
                *) C=noble;;
            esac
            echo "deb [signed-by=/etc/apt/keyrings/amnezia-ppa.gpg] https://ppa.launchpadcontent.net/amnezia/ppa/ubuntu ${'$'}C main" \
                > /etc/apt/sources.list.d/amnezia-ppa.list
            apt-get -q update

            step "Сборка модуля ядра AmneziaWG (DKMS) — несколько минут"
            HDR="linux-headers-${'$'}KVER"
            if ! apt-cache show "${'$'}HDR" >/dev/null 2>&1; then
                case "${'$'}KVER" in *-cloud-*) HDR="linux-headers-cloud-${'$'}ARCH";; *) HDR="linux-headers-${'$'}ARCH";; esac
                [ "${'$'}ID" = ubuntu ] && HDR=linux-headers-generic
            fi
            apt-get -q -y -o Dpkg::Use-Pty=0 install dkms build-essential "${'$'}HDR" amneziawg-dkms amneziawg-tools
            if ! modprobe amneziawg; then
                echo "ОШИБКА: модуль AmneziaWG собран, но не загрузился. Частая причина — Secure Boot у VPS."
                dkms status 2>/dev/null | grep -i amnezia || true
                exit 1
            fi
            echo "Модуль: ${'$'}(cat /sys/module/amneziawg/version 2>/dev/null || echo ?), ${'$'}(awg --version 2>/dev/null | head -1)"
            echo amneziawg > /etc/modules-load.d/amneziawg.conf

            step "Конфигурация AmneziaWG (порт ${'$'}PORT/UDP)"
            NIC=${'$'}(ip route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if(${'$'}i=="dev") print ${'$'}(i+1)}' | head -1)
            [ -n "${'$'}NIC" ] || { echo "ОШИБКА: не удалось определить сетевой интерфейс"; exit 1; }
            mkdir -p /etc/amnezia/amneziawg
            umask 077
            echo '${gzB64(p.serverConf())}' | base64 -d | gunzip | sed "s/__NIC__/${'$'}NIC/g" > /etc/amnezia/amneziawg/awg0.conf
            echo '${gzB64(p.toJson().toString())}' | base64 -d | gunzip > $AWG_META
            chmod 600 /etc/amnezia/amneziawg/awg0.conf $AWG_META
            umask 022
            printf 'net.ipv4.ip_forward = 1\n' > /etc/sysctl.d/99-amneziawg-forwarding.conf
            sysctl -q -p /etc/sysctl.d/99-amneziawg-forwarding.conf
            if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
                ufw allow "${'$'}PORT/udp" >/dev/null && echo "UFW: открыт ${'$'}PORT/udp"
            elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
                firewall-cmd --permanent --add-port="${'$'}PORT/udp" >/dev/null && firewall-cmd --reload >/dev/null && echo "firewalld: открыт ${'$'}PORT/udp"
            fi

            step "Запуск awg-quick@awg0"
            systemctl enable awg-quick@awg0.service >/dev/null 2>&1
            systemctl restart awg-quick@awg0.service || true
            sleep 2
            if ! systemctl is-active --quiet awg-quick@awg0.service; then
                echo "ОШИБКА: служба AmneziaWG не запустилась:"
                journalctl -u awg-quick@awg0 -n 20 --no-pager | cut -c1-300
                exit 1
            fi
            echo "Служба работает, порт ${'$'}PORT/UDP."
            echo "ВАЖНО: если у хостинга есть свой файрвол (Security Group), откройте там ${'$'}PORT/udp."
        """.trimIndent()
        return jobStart("awg", jobBody(body), "Установка AmneziaWG уже идёт")
    }

    /** Проверка входа: печатает маркер. */
    fun ping(): String = HEADER + "\n" + "echo \"$PING_MARKER\""

    /**
     * Отключает вход по паролю в SSH (оставляет вход по ключу, root — только по ключу).
     * Проверяет конфигурацию через sshd -t и откатывает изменения при ошибке.
     */
    fun disablePasswordAuth(): String = HEADER + "\n" + """
        set -e
        step "Отключение входа по паролю в SSH"
        SSHD=${'$'}(command -v sshd || echo /usr/sbin/sshd)
        MAIN=/etc/ssh/sshd_config
        DROPIN_DIR=/etc/ssh/sshd_config.d
        DROPIN=${'$'}{DROPIN_DIR}/00-hy2setup-hardening.conf
        SETTINGS="PasswordAuthentication no
        ChallengeResponseAuthentication no
        PermitRootLogin prohibit-password
        PubkeyAuthentication yes"
        if [ -d "${'$'}{DROPIN_DIR}" ] && grep -qiE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/' "${'$'}{MAIN}"; then
            printf '%s\n' "${'$'}{SETTINGS}" > "${'$'}{DROPIN}"
            ROLLBACK="rm -f ${'$'}{DROPIN}"
        else
            cp "${'$'}{MAIN}" "${'$'}{MAIN}.hy2setup.bak"
            { printf '%s\n' "${'$'}{SETTINGS}"; grep -viE '^[[:space:]]*(PasswordAuthentication|ChallengeResponseAuthentication|KbdInteractiveAuthentication|PermitRootLogin|PubkeyAuthentication)[[:space:]]' "${'$'}{MAIN}.hy2setup.bak"; } > "${'$'}{MAIN}"
            ROLLBACK="cp ${'$'}{MAIN}.hy2setup.bak ${'$'}{MAIN}"
        fi
        if ! "${'$'}{SSHD}" -t; then
            echo "ОШИБКА: sshd отклонил конфигурацию — изменения откатываются."
            eval "${'$'}{ROLLBACK}"
            exit 1
        fi
        EFFECTIVE=${'$'}("${'$'}{SSHD}" -T 2>/dev/null | awk 'tolower(${'$'}1)=="passwordauthentication"{print ${'$'}2}')
        if [ "${'$'}{EFFECTIVE}" != "no" ]; then
            echo "ОШИБКА: после изменений вход по паролю всё ещё включён (PasswordAuthentication=${'$'}{EFFECTIVE}). Откат."
            eval "${'$'}{ROLLBACK}"
            exit 1
        fi
        systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || service ssh reload 2>/dev/null || service sshd reload
        echo "Вход по паролю отключён, вход по ключу оставлен (PasswordAuthentication=no)."
    """.trimIndent()

    /** Перезагрузка через несколько секунд, чтобы SSH-сессия успела корректно закрыться. */
    fun reboot(): String = HEADER + "\n" + """
        step "Перезагрузка сервера"
        nohup sh -c 'sleep 3; systemctl reboot' >/dev/null 2>&1 &
        echo "Сервер перезагрузится через 3 секунды."
    """.trimIndent()


    // =====================================================================
    //  Telegram MTProto-прокси (mtg, FakeTLS)
    // =====================================================================

    const val LINK_MARKER_TG = "##TG|"
    private const val MTG_FALLBACK_VERSION = "2.2.8"

    /**
     * Устанавливает mtg (github.com/9seconds/mtg) с FakeTLS-секретом под домен [frontDomain] на TCP-порт [port].
     * [secret] — готовый секрет профиля (ee…): тогда ссылка tg:// остаётся прежней; null — новый секрет.
     */
    fun installTelegram(port: Int, frontDomain: String, secret: String? = null): String {
        require(secret == null || Regex("^ee[0-9a-f]{34,}$").matches(secret)) { "Неверный секрет FakeTLS" }
        val secretLine = if (secret != null) "SECRET='$secret'"
        else "SECRET=${'$'}(/usr/local/bin/mtg generate-secret --hex \"${'$'}{FRONT}\")"
        return installTelegramBody(port, frontDomain, secretLine)
    }

    private fun installTelegramBody(port: Int, frontDomain: String, secretLine: String): String = HEADER + "\n" + """
        set -e
        PORT=$port
        FRONT='$frontDomain'

        step "Проверка системы"
        if ! command -v systemctl >/dev/null 2>&1; then
            echo "ОШИБКА: нужен systemd (Ubuntu/Debian/CentOS и т.п.)"; exit 1
        fi
        pkg_install() {
            if command -v apt-get >/dev/null 2>&1; then
                apt-get -o DPkg::Lock::Timeout=600 update -qq && apt-get -o DPkg::Lock::Timeout=600 install -y -qq "${'$'}@"
            elif command -v dnf >/dev/null 2>&1; then dnf install -y -q "${'$'}@"
            elif command -v yum >/dev/null 2>&1; then yum install -y -q "${'$'}@"
            else echo "ОШИБКА: не найден менеджер пакетов"; exit 1; fi
        }
        command -v curl >/dev/null 2>&1 || pkg_install curl ca-certificates
        command -v tar >/dev/null 2>&1 || pkg_install tar
        case "${'$'}(uname -m)" in
            x86_64|amd64) ARCH=amd64 ;;
            aarch64|arm64) ARCH=arm64 ;;
            armv7*|armv6*) ARCH=armv7 ;;
            i386|i686) ARCH=386 ;;
            *) echo "ОШИБКА: неподдерживаемая архитектура ${'$'}(uname -m)"; exit 1 ;;
        esac
        BUSY=${'$'}(ss -ltnpH "sport = :${'$'}{PORT}" 2>/dev/null | grep -v '"mtg"' || true)
        if [ -n "${'$'}{BUSY}" ]; then
            echo "ОШИБКА: TCP-порт ${'$'}{PORT} уже занят другой программой — выберите другой порт:"
            echo "${'$'}{BUSY}"
            exit 1
        fi
        echo "OK"

        step "Загрузка mtg (github.com/9seconds/mtg)"
        VER=${'$'}(curl -fsSLI -o /dev/null -w '%{url_effective}' https://github.com/9seconds/mtg/releases/latest 2>/dev/null | sed -n 's#.*/tag/v\([0-9][0-9.]*\)${'$'}#\1#p')
        [ -n "${'$'}{VER}" ] || VER=$MTG_FALLBACK_VERSION
        TMP=${'$'}(mktemp -d)
        curl -fsSL "https://github.com/9seconds/mtg/releases/download/v${'$'}{VER}/mtg-${'$'}{VER}-linux-${'$'}{ARCH}.tar.gz" -o "${'$'}{TMP}/mtg.tgz"
        tar xzf "${'$'}{TMP}/mtg.tgz" -C "${'$'}{TMP}"
        install -m 755 "${'$'}{TMP}/mtg-${'$'}{VER}-linux-${'$'}{ARCH}/mtg" /usr/local/bin/mtg
        rm -rf "${'$'}{TMP}"
        /usr/local/bin/mtg --version | head -1

        step "Секрет FakeTLS и конфигурация /etc/mtg.toml"
        $secretLine
        case "${'$'}{SECRET}" in ee*) ;; *) echo "ОШИБКА: mtg не выдал FakeTLS-секрет"; exit 1 ;; esac
        cat > /etc/mtg.toml <<EOF
        secret = "${'$'}{SECRET}"
        bind-to = "0.0.0.0:${'$'}{PORT}"
        EOF
        chmod 644 /etc/mtg.toml
        cat > /etc/systemd/system/mtg.service <<'EOF'
        [Unit]
        Description=mtg - MTProto proxy server
        Documentation=https://github.com/9seconds/mtg
        After=network.target

        [Service]
        ExecStart=/usr/local/bin/mtg run /etc/mtg.toml
        Restart=always
        RestartSec=3
        DynamicUser=true
        LimitNOFILE=65536
        AmbientCapabilities=CAP_NET_BIND_SERVICE

        [Install]
        WantedBy=multi-user.target
        EOF
        echo "OK"

        FW_PORTS="${'$'}{PORT}/tcp"
${firewall()}
        echo "ВАЖНО: если у хостинга есть свой файрвол (Security Group), откройте TCP-порт ${'$'}{PORT} там."

        step "Запуск службы mtg"
        systemctl daemon-reload
        systemctl enable mtg.service >/dev/null 2>&1 || true
        systemctl restart mtg.service
        sleep 3
        if systemctl is-active --quiet mtg.service; then
            echo "Служба работает."
        else
            echo "ОШИБКА: служба не запустилась. Последние строки журнала:"
            journalctl -u mtg.service -n 25 --no-pager || true
            exit 1
        fi
        echo "${LINK_MARKER_TG}${'$'}{PORT}|${'$'}{SECRET}"
        step "Готово"
    """.trimIndent()

    const val XPRIV_MARKER = "##XPRIV="
    const val TGFULL_MARKER = "##TGFULL|"

    /**
     * Всё, что нужно для постоянного профиля уже работающего сервера: ссылки ([exportLinks]),
     * приватный ключ Reality (из конфигурации Xray) и домен маскировки mtg.
     */
    fun exportProfile(): String = exportLinks() + "\n" + """
        X=/usr/local/etc/xray/config.json
        if [ -f "${'$'}{X}" ]; then
            XP=${'$'}(grep -o '"privateKey" *: *"[^"]*"' "${'$'}{X}" | head -1 | sed 's/.*: *"\(.*\)"/\1/')
            [ -n "${'$'}{XP}" ] && echo "$XPRIV_MARKER${'$'}{XP}"
        fi
        if [ -f /etc/mtg.toml ]; then
            S=${'$'}(sed -n 's/^secret *= *"\(.*\)".*/\1/p' /etc/mtg.toml | head -1)
            P=${'$'}(sed -n 's/^bind-to *= *".*:\([0-9][0-9]*\)".*/\1/p' /etc/mtg.toml | head -1)
            echo "$TGFULL_MARKER${'$'}{P:-443}|${'$'}{S}"
        fi
    """.trimIndent()

    /** Печатает маркер с портом и секретом установленного mtg. */
    fun exportTelegram(): String = HEADER + "\n" + """
        if [ -f /etc/mtg.toml ]; then
            SECRET=${'$'}(sed -n 's/^secret *= *"\(.*\)".*/\1/p' /etc/mtg.toml | head -1)
            PORT=${'$'}(sed -n 's/^bind-to *= *".*:\([0-9][0-9]*\)".*/\1/p' /etc/mtg.toml | head -1)
            echo "${LINK_MARKER_TG}${'$'}{PORT:-443}|${'$'}{SECRET}"
            echo "Telegram-прокси: найдена конфигурация (порт ${'$'}{PORT:-443}/tcp)."
        else
            echo "Telegram-прокси (mtg) на сервере не установлен."
        fi
    """.trimIndent()

    fun statusTelegram(): String = HEADER + "\n" + """
        step "Telegram-прокси (mtg)"
        if systemctl cat mtg.service >/dev/null 2>&1; then
            echo "Служба: ${'$'}(systemctl is-active mtg.service 2>/dev/null || true)"
            /usr/local/bin/mtg --version 2>/dev/null | head -1 || true
            journalctl -u mtg.service -n 10 --no-pager 2>/dev/null || true
        else
            echo "Не установлен."
        fi
    """.trimIndent()

    fun restartTelegram(): String = HEADER + "\n" + """
        step "Перезапуск mtg"
        systemctl restart mtg.service
        sleep 2
        systemctl is-active mtg.service
    """.trimIndent()

    fun removeTelegram(): String = HEADER + "\n" + """
        step "Остановка и удаление Telegram-прокси (mtg)"
        systemctl disable --now mtg.service >/dev/null 2>&1 || true
        rm -f /etc/systemd/system/mtg.service /usr/local/bin/mtg /etc/mtg.toml
        systemctl daemon-reload || true
        step "Готово. Правила файрвола (если открывали) нужно удалить вручную."
    """.trimIndent()

}
