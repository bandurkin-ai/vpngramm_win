package app.hy2setup

import org.json.JSONObject

/** Состояние служб и трафик сервера: скрипт и разбор его вывода. */
object ServerCheck {
    const val SVC_MARKER = "##SVC|"
    const val VNSTAT_MARKER = "##VNSTAT="
    /** Hysteria2 уже отдаёт сайт из каталога — подписка работает. */
    const val SITE_MARKER = "##SITE=file"

    /** Служба systemd каждого протокола. */
    val SERVICES = linkedMapOf(
        Protocol.VLESS.title to "xray",
        Protocol.HY2.title to "hysteria-server",
        Awg.TITLE to "awg-quick@awg0",
        TgParams.TITLE to "mtg",
    )

    /**
     * Печатает состояние служб и трафик за месяц (vnstat). vnstat ставится при первом запуске —
     * он считает трафик с этого момента.
     */
    fun script(): String = """
        #!/bin/bash
        trap 'rm -f "${'$'}0"' EXIT
        exec </dev/null
        export DEBIAN_FRONTEND=noninteractive
        for S in ${SERVICES.values.joinToString(" ")}; do
            if systemctl cat "${'$'}S" >/dev/null 2>&1; then
                echo "$SVC_MARKER${'$'}S|${'$'}(systemctl is-active "${'$'}S" 2>/dev/null)"
            fi
        done
        grep -q '^ *type: file' /etc/hysteria/config.yaml 2>/dev/null && echo "$SITE_MARKER"
        if ! command -v vnstat >/dev/null 2>&1 && command -v apt-get >/dev/null 2>&1; then
            echo "Ставлю vnstat для учёта трафика…"
            apt-get -o DPkg::Lock::Timeout=120 install -y -qq vnstat >/dev/null 2>&1 && systemctl enable --now vnstat >/dev/null 2>&1 || true
        fi
        if command -v vnstat >/dev/null 2>&1; then
            echo "$VNSTAT_MARKER${'$'}(vnstat --json m 2>/dev/null | tr -d '\n' | base64 -w0)"
        fi
    """.trimIndent()

    /** Состояние служб: название протокола → работает ли (только те, что стоят на сервере). */
    fun services(lines: List<String>): Map<String, Boolean> {
        val byUnit = lines.filter { it.startsWith(SVC_MARKER) }.associate {
            val f = it.removePrefix(SVC_MARKER).split("|")
            f[0] to (f.getOrNull(1)?.trim() == "active")
        }
        return SERVICES.entries.mapNotNull { (title, unit) -> byUnit[unit]?.let { title to it } }.toMap()
    }

    /** Трафик за текущий месяц, ГБ (вход + выход по всем интерфейсам), или null. vnstat 2.x — байты, 1.x — КиБ. */
    fun monthTrafficGb(lines: List<String>): Double? {
        val b64 = lines.firstOrNull { it.startsWith(VNSTAT_MARKER) }?.removePrefix(VNSTAT_MARKER)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { parseVnstat(String(java.util.Base64.getDecoder().decode(b64))) }.getOrNull()
    }

    fun parseVnstat(json: String): Double? {
        val o = JSONObject(json)
        val v1 = o.optString("jsonversion") == "1"
        val ifs = o.optJSONArray("interfaces") ?: return null
        var total = 0.0
        var any = false
        for (i in 0 until ifs.length()) {
            val itf = ifs.getJSONObject(i)
            val name = itf.optString("name", itf.optString("id"))
            if (name == "lo" || name.startsWith("awg") || name.startsWith("wg")) continue
            val months = itf.optJSONObject("traffic")?.let { it.optJSONArray("month") ?: it.optJSONArray("months") } ?: continue
            if (months.length() == 0) continue
            // самый свежий месяц — с наибольшими годом и месяцем
            var best: JSONObject? = null
            var bestKey = -1
            for (m in 0 until months.length()) {
                val e = months.getJSONObject(m)
                val d = e.optJSONObject("date") ?: continue
                val key = d.optInt("year") * 100 + d.optInt("month")
                if (key > bestKey) { bestKey = key; best = e }
            }
            val e = best ?: continue
            val bytes = (e.optDouble("rx", 0.0) + e.optDouble("tx", 0.0)) * if (v1) 1024.0 else 1.0
            total += bytes
            any = true
        }
        return if (any) total / 1e9 else null
    }
}
