package app.hy2setup

/** Шаги «Комбайна». */
enum class CombineStep(val title: String) {
    CREATE("Создание VPS в OneProvider"),
    BOOT("Ожидание загрузки VPS"),
    DNS("A-запись в name.com"),
    DNS_CHECK("Проверка обновления DNS"),
    INSTALL("Установка Hysteria2 + Salamander"),
    VLESS("VLESS + Reality"),
    AWG("AmneziaWG 3.1"),
    TG("Telegram-прокси (mtg)"),
    CERT("Копия сертификата Let's Encrypt"),
    SECURE("Безопасность: обновления, автообновления, вход только по ключу"),
    LINK("Ссылка и QR-код"),
}

enum class StepStatus { PENDING, RUNNING, DONE, WARN, FAILED, SKIPPED }

/** Шаги «Комбайна+» (постоянные ссылки). */
enum class PlusStep(val title: String) {
    CREATE("Создание VPS в OneProvider"),
    BOOT("Ожидание загрузки VPS"),
    DNS("A-запись в name.com → новый IP"),
    DNS_CHECK("Проверка обновления DNS"),
    HY2("Hysteria2 + Salamander (ключи профиля)"),
    VLESS("VLESS + Reality (ключи профиля)"),
    AWG("AmneziaWG 3.1 (ключи профиля)"),
    TG("Telegram-прокси (прежний секрет)"),
    CERT("Копия сертификата Let's Encrypt"),
    SECURE("Безопасность: обновления, вход только по ключу"),
    VERIFY("Проверка сервера и DNS"),
    LINK("Ссылки и QR-коды (прежние)"),
    OLD_VPS("Старый VPS"),
}
