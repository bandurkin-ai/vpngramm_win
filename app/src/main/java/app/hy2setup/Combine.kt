package app.hy2setup

enum class StepStatus { PENDING, RUNNING, DONE, WARN, FAILED, SKIPPED }

/** Шаги создания сервера и переезда на другой VPS (постоянные ссылки). */
enum class PlusStep(val title: String) {
    CREATE("Создание VPS в OneProvider"),
    BOOT("Ожидание загрузки VPS"),
    DNS("A-запись в name.com → новый IP"),
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
