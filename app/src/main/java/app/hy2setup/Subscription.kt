package app.hy2setup

import java.util.Base64

/**
 * Подписка для Happ: текстовый файл со ссылками человека, который Hysteria2 отдаёт с сайта домена
 * по адресу https://домен/s/<токен>. Happ сам обновляет её, поэтому новые протоколы и ключи доходят до клиентов без участия.
 */
object Subscription {
    /** Протоколы, которые понимает Happ (AmneziaWG и Telegram — в своих приложениях). */
    private val HAPP = setOf(Protocol.VLESS.title, Links.XHTTP_TITLE, Protocol.HY2.title)

    fun content(p: StableProfile, user: ProfileUser?): String {
        val title = "vpngramm · ${p.fqdn}" + (user?.let { " · ${it.name}" } ?: "")
        val links = p.linksFor(user).filter { it.title in HAPP }.joinToString("\n") { it.uri }
        return "#profile-title: base64:" + Base64.getEncoder().encodeToString(title.toByteArray()) + "\n" +
            "#profile-update-interval: 12\n" + links + "\n"
    }

    /** Все файлы сайта: страница, пустая страница каталога подписок (чтобы не было списка файлов) и подписки включённых людей. */
    fun siteFiles(p: StableProfile): Map<String, String> {
        val files = linkedMapOf(
            "index.html" to page(p.fqdn),
            "s/index.html" to page(p.fqdn),
            "s/${p.subToken}" to content(p, null),
        )
        p.activeUsers.forEach { files["s/${it.subToken}"] = content(p, it) }
        return files
    }

    private fun page(fqdn: String) = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$fqdn</title><style>body{font-family:system-ui,sans-serif;max-width:40rem;margin:4rem auto;padding:0 1rem;color:#333}</style>
</head><body><h1>$fqdn</h1><p>This site is under construction.</p></body></html>
"""
}
