package app.hy2setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.hy2setup.platform.BaseViewModel
import app.hy2setup.platform.Doc
import app.hy2setup.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import app.hy2setup.platform.Device

/** Всё, что нужно для создания A-записи. */
/** Что настраивается в настройках установки. */
enum class ServerMode(val title: String) { HY2("Hysteria2"), VLESS("VLESS"), XHTTP("VLESS XHTTP"), AWG("AmneziaWG"), TG("Telegram") }

/** Режим «Доступа к серверам» для протокола. */
fun modeOf(p: Protocol) = when (p) { Protocol.HY2 -> ServerMode.HY2; Protocol.VLESS -> ServerMode.VLESS; Protocol.AWG -> ServerMode.AWG }

/** Разделы нижней навигации. */
const val TAB_HOME = 0
const val TAB_CLOUD = 1
const val TAB_TOOLS = 2

/** Сколько помнить отказ локации «нет мест», мс. */
private const val BAD_LOCATION_MS = 24 * 3600_000L

/** Длительность «живой проверки», секунд. */
private const val LIVE_SECONDS = 90

/** Ключ постоянного профиля «Комбайна+» в SecretStore. */
private const val PLUS_PROFILE = "plusProfile"
private const val STABLE_PROFILES = "stableProfiles"
private const val ROLLBACK_KEY = "plusRollback"

/** Запись, которая при переезде на новый IP переключается вместе с основной (зона, имя). */
private const val MCP_ZONE = "aeromike.org"
private const val MCP_HOST = "mcp"

data class DnsPlan(
    val username: String,
    val token: String,
    val zone: String,
    val subdomain: String,
    val ttl: Int,
) {
    val fqdn: String get() = if (subdomain.isEmpty()) zone else "$subdomain.$zone"
}

class MainViewModel(private val platform: Platform) : BaseViewModel() {

    private val prefs = platform.prefs
    private val secrets = platform.secrets

    // ---------- Сервер (SSH). Пароль, ключ и токен хранятся зашифрованными (SecretStore) ----------
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var sshPort by mutableStateOf(prefs.getString("sshPort", "22") ?: "22")
    var user by mutableStateOf(prefs.getString("user", "root") ?: "root")
    var useKey by mutableStateOf(prefs.getBoolean("useKey", false))
    var password by mutableStateOf(secrets.get("password"))
    var privateKey by mutableStateOf(secrets.get("privateKey"))
    var passphrase by mutableStateOf(secrets.get("passphrase"))

    /** Сохранённые серверы для выпадающего списка (последний использованный — первым). */
    val profiles = mutableStateListOf<ServerProfile>().apply { addAll(ServerProfiles.decode(prefs.getString("profiles", null))) }

    /** Перед установкой: обновления, автообновления, вход только по ключу приложения. */
    var hardenBeforeInstall by mutableStateOf(prefs.getBoolean("harden", false))

    // ---------- Hysteria2 ----------
    var protocol by mutableStateOf(
        runCatching { Protocol.valueOf(prefs.getString("protocol", "HY2") ?: "HY2") }.getOrDefault(Protocol.HY2),
    )
    var hyPort by mutableStateOf(prefs.getString("hyPort", "443") ?: "443")

    // ---------- VLESS + Reality ----------
    var vlessPort by mutableStateOf(prefs.getString("vlessPort", "443") ?: "443")
    /** Порт AmneziaWG: случайный при первом запуске, дальше постоянный. */
    var awgPort by mutableStateOf(prefs.getString("awgPort", null) ?: Awg.randomPort().toString())
    /** Режим RandomTrailers для новых установок AmneziaWG (по умолчанию выключен: сильно режет скорость). */
    var awgTrailers by mutableStateOf(prefs.getBoolean("awgTrailers", false))
    var realitySni by mutableStateOf(prefs.getString("realitySni", "www.microsoft.com") ?: "www.microsoft.com")
    /** VLESS маскируется под свой домен (вместе ставится Hysteria2 с сертификатом и сайтом). */
    var vlessSelf by mutableStateOf(prefs.getBoolean("vlessSelf", true))
    var domain by mutableStateOf(prefs.getString("domain", "") ?: "")
    var email by mutableStateOf(prefs.getString("email", "") ?: "")
    var sni by mutableStateOf(prefs.getString("sni", "www.bing.com") ?: "www.bing.com")
    var masquerade by mutableStateOf(prefs.getString("masq", "https://www.bing.com/") ?: "https://www.bing.com/")

    // ---------- name.com ----------
    var useDns by mutableStateOf(prefs.getBoolean("useDns", false))
    var ncUser by mutableStateOf(prefs.getString("ncUser", "") ?: "")
    var ncToken by mutableStateOf(secrets.get("ncToken"))
    var zone by mutableStateOf(prefs.getString("zone", "") ?: "")
    var subdomain by mutableStateOf(prefs.getString("sub", "vpn") ?: "vpn")
    var ttl by mutableStateOf(prefs.getString("ttl", "300") ?: "300")
    var overwrite by mutableStateOf(false)

    // ---------- Состояние ----------
    var running by mutableStateOf(false)
        internal set
    /** Журнал «Инструментов». */
    val toolsLog = mutableStateListOf<String>()
    /** Журнал блока «Произвольный VPS». */
    val vpsLog = mutableStateListOf<String>()
    /** Журнал текущей цели: пока идёт действие блока «Произвольный VPS» — её журнал, иначе «Инструментов». */
    val log: SnapshotStateList<String> get() = if (vpsMode) vpsLog else toolsLog
    /** Ссылки для клиента (после установки или по кнопке «Получить ссылку»). */
    private val toolsLinks = mutableStateListOf<ShareLink>()
    /** Ссылки блока «Произвольный VPS». */
    val vpsResultLinks = mutableStateListOf<ShareLink>()
    /** Ссылки текущей цели (см. [log]). */
    val resultLinks: SnapshotStateList<ShareLink> get() = if (vpsMode) vpsResultLinks else toolsLinks
    var errorMessage by mutableStateOf<String?>(null)
    var hostKeyPrompt by mutableStateOf<String?>(null)
        private set
    private var hostKeyAnswer: CompletableDeferred<Boolean>? = null

    // ---------- Вкладка DNS ----------
    val dnsRecords = mutableStateListOf<DnsRecord>()
    val selected = mutableStateListOf<Int>()
    val dnsLog = mutableStateListOf<String>()
    var onlyAddress by mutableStateOf(false)
    var dnsLoaded by mutableStateOf(false)
        private set
    var loadedZone by mutableStateOf("")
        private set
    var dnsError by mutableStateOf<String?>(null)
    var confirmDelete by mutableStateOf(false)

    // ---------- Раздел нижней навигации (TAB_HOME … TAB_TOOLS) ----------
    var selectedTab by mutableStateOf(TAB_HOME)
    /** Протоколы, выбранные в «Доступе к серверам» (Инструменты): «Установить» ставит их все по очереди. */
    var serverModes by mutableStateOf(
        prefs.getString("serverModes", null)?.split(',')?.mapNotNull { n -> ServerMode.entries.firstOrNull { it.name == n } }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: setOf(modeOf(protocol)),
    )
        private set
    /** Первый из выбранных протоколов; присваивание выбирает только его. */
    var serverMode: ServerMode
        get() = ServerMode.entries.first { it in serverModes }
        set(v) { applyServerModes(setOf(v)) }

    /** Включает или выключает протокол в «Доступе к серверам» (Инструменты) (хотя бы один остаётся выбранным). */
    fun toggleServerMode(m: ServerMode) {
        val next = if (m in serverModes) serverModes - m else serverModes + m
        if (next.isNotEmpty()) applyServerModes(next)
    }

    private fun applyServerModes(modes: Set<ServerMode>) {
        serverModes = modes
        when (ServerMode.entries.firstOrNull { it in modes && it != ServerMode.TG }) {
            ServerMode.HY2 -> protocol = Protocol.HY2
            ServerMode.VLESS, ServerMode.XHTTP -> protocol = Protocol.VLESS
            ServerMode.AWG -> protocol = Protocol.AWG
            else -> Unit
        }
        prefs.edit().putString("serverModes", modes.joinToString(",") { it.name }).apply()
    }

    /** Выбранные протоколы по порядку вкладок. */
    val serverModeList: List<ServerMode> get() = ServerMode.entries.filter { it in serverModes }
    /** Подраздел «Облака»: false — VPS (OneProvider), true — DNS (name.com). */
    var cloudDns by mutableStateOf(false)

    // ---------- OneProvider ----------
    var opApiKey by mutableStateOf(secrets.get("opApiKey"))
    var opClientKey by mutableStateOf(secrets.get("opClientKey"))
    val opVms = mutableStateListOf<OpVm>()
    val opSelected = mutableStateListOf<String>()
    var opLoaded by mutableStateOf(false)
        internal set
    val opLog = mutableStateListOf<String>()
    var opError by mutableStateOf<String?>(null)
    val opLocations = mutableStateListOf<OpLocation>()
    val opSizes = mutableStateListOf<OpSize>()
    val opTemplates = mutableStateListOf<OpTemplate>()
    val opKeys = mutableStateListOf<OpSshKey>()
    var opCatalogLoaded by mutableStateOf(false)
        internal set
    var opLocationId by mutableStateOf(prefs.getString("opLoc", "") ?: "")
    /** Выбранный регион (первый шаг выбора локации). */
    var opRegion by mutableStateOf(prefs.getString("opRegion", "") ?: "")
    /** Локации, где провайдер недавно отказал «нет мест»: id → время отказа (мс). Живут сутки. */
    val opBad = mutableStateMapOf<String, Long>().apply {
        val now = System.currentTimeMillis()
        runCatching {
            val o = org.json.JSONObject(prefs.getString("opBadLocs", "{}") ?: "{}")
            o.keys().forEach { k -> val t = o.optLong(k); if (now - t < BAD_LOCATION_MS) put(k, t) }
        }
    }
    var opSizeId by mutableStateOf(prefs.getString("opSize", "") ?: "")
    /** Фильтр тарифов при заказе VPS: цена до и трафик от (0 — без ограничения). */
    var opFilter by mutableStateOf(OpFilter(prefs.getInt("opMaxPrice", 0), prefs.getInt("opMinTraffic", 0)))
        private set
    var opTemplateId by mutableStateOf(prefs.getString("opTpl", "") ?: "")
    var opHostname by mutableStateOf("")
    val opKeySel = mutableStateListOf<String>()
    var opKeysLoaded by mutableStateOf(false)
        private set
    var opNewKeyName by mutableStateOf("")
    var opNewKeyValue by mutableStateOf("")
    /** Ключ кабинета, ожидающий подтверждения удаления. */
    var opKeyToDelete by mutableStateOf<OpSshKey?>(null)
    var opConfirmCreate by mutableStateOf(false)
    var opConfirmDelete by mutableStateOf(false)
    var opCreated by mutableStateOf<OpCreated?>(null)
    /** VPS из карточки создан с SSH-ключами из кабинета. */
    var opCreatedKeyed by mutableStateOf(false)
    /** VPS, удаление которых требует подтверждения оплаты перерасхода трафика. */
    val opOverage = mutableStateListOf<Pair<OpVm, OpDestroyResult>>()

    // ---------- Telegram-прокси (mtg) ----------
    var tgPort by mutableStateOf(prefs.getString("tgPort", "443") ?: "443")
    var tgDomain by mutableStateOf(prefs.getString("tgDomain", "www.microsoft.com") ?: "www.microsoft.com")
    val tgLog = mutableStateListOf<String>()
    val tgLinks = mutableStateListOf<ShareLink>()
    var tgError by mutableStateOf<String?>(null)

    // ---------- Свободные серверы ----------
    /** Свободные серверы (например, прежние VPS после переезда): на любой из них можно перевести постоянные ссылки. */
    val combineServers = mutableStateListOf<CombineServer>().apply { addAll(CombineServers.decode(prefs.getString("combineServers", null))) }

    // ---------------- Серверы с постоянными ссылками (Главная) ----------------
    /** Серверы с постоянными ссылками (Главная). У каждого свой домен и свои ключи. */
    val stableProfiles = mutableStateListOf<StableProfile>().apply {
        val stored = secrets.get(STABLE_PROFILES)
        addAll(ProfileImport.load(stored, secrets.get(PLUS_PROFILE)))
        // 1.6 и раньше: один профиль под ключом plusProfile — переносим в список; заодно сохраняем
        // созданные при чтении поля (токен подписки), чтобы адрес подписки не менялся от запуска к запуску
        if (isNotEmpty()) {
            val encoded = ProfileImport.encodeList(this)
            if (encoded != stored) secrets.put(STABLE_PROFILES, encoded)
        }
    }
    /** Домен выбранного на Главной сервера. */
    var stableSelected by mutableStateOf(prefs.getString("stableSelected", "") ?: "")
        private set
    /** Шторка «Создать новый сервер»: профиль ещё не выбран, запуск создаст новый. */
    var plusNew by mutableStateOf(false)

    /** Выбранный сервер с постоянными ссылками. Присваивание добавляет (или заменяет) профиль и выбирает его. */
    var plusProfile: StableProfile?
        get() = stableProfiles.firstOrNull { it.fqdn == stableSelected } ?: stableProfiles.firstOrNull()
        internal set(v) {
            if (v == null) return
            storeStable(v)
            selectStableQuiet(v.fqdn)
        }
    var plusSecure by mutableStateOf(prefs.getBoolean("plusSecure", true))
    // Протоколы нового сервера (до 3.3 так настраивался комбайн — его выбор переносится сюда при первом запуске)
    /** Hysteria2 + Salamander (без неё VLESS маскируется под чужой сайт). */
    var plusHy2 by mutableStateOf(prefs.getBoolean("plusHy2", prefs.getBoolean("combineHy2", true)))
    /** VLESS + Reality с маскировкой под свой домен (или под [realitySni] без Hysteria2). */
    var plusVless by mutableStateOf(prefs.getBoolean("plusVless", prefs.getBoolean("combineVless", true)))
    /** Telegram-прокси (mtg); порт и домен — общие с прежней вкладкой «Сервер». */
    var plusTg by mutableStateOf(prefs.getBoolean("plusTg", prefs.getBoolean("combineTg", false)))
    /** Ставить при замене сервера ещё и AmneziaWG 3.1 (ключи — в профиле, ссылка постоянная). */
    var plusAwg by mutableStateOf(prefs.getBoolean("plusAwg", true))
    /** Ставить на сервер с постоянными ссылками ещё и VLESS XHTTP (путь — постоянный, из профиля). */
    var plusXhttp by mutableStateOf(prefs.getBoolean("plusXhttp", prefs.getBoolean("combineXhttp", false)))
    /** RandomTrailers для AmneziaWG профиля (своя настройка: смена меняет постоянную ссылку). */
    var plusTrailers by mutableStateOf(prefs.getBoolean("plusTrailers", false))
    val plusLog = mutableStateListOf<String>()
    val plusLinks = mutableStateListOf<ShareLink>()
    val plusSteps = mutableStateMapOf<PlusStep, StepStatus>()
    var plusConfirm by mutableStateOf(false)
    var plusConfirmReset by mutableStateOf(false)
    var plusError by mutableStateOf<String?>(null)
    var plusActive by mutableStateOf(false)
        internal set
    /** Старый VPS, который можно удалить после переезда. */
    var plusOldVps by mutableStateOf<ProfileVps?>(null)
    /** Стоимость перерасхода трафика старого VPS — нужно согласие пользователя. */
    var plusOldOverage by mutableStateOf<OpDestroyResult?>(null)
    /** Профиль из файла, ожидающий подтверждения замены текущего. */
    var plusPendingImport by mutableStateOf<StableProfile?>(null)
    var plusConfirmSelf by mutableStateOf(false)
    /** Куда переезжает профиль: пусто — новый VPS, иначе VPS из [combineServers]. */
    var plusTargetId by mutableStateOf("")
    /** Свободный сервер, на который идёт текущий переезд (для подписей шагов). */
    var plusRunTarget by mutableStateOf<CombineServer?>(null)
        private set
    private var plusJob: Job? = null

    // ---------- Вкладка DNS: новая запись ----------
    var newType by mutableStateOf("A")
    var newHost by mutableStateOf("")
    var newAnswer by mutableStateOf("")
    var newTtl by mutableStateOf("300")
    var newPriority by mutableStateOf("10")

    private val runner = SshRunner(File(platform.dataDir, "known_hosts"), hwTransport = ::hwTransport, confirmHostKey = { message ->
        val answer = CompletableDeferred<Boolean>()
        hostKeyAnswer = answer
        hostKeyPrompt = message
        answer.await()
    })

    // ---------- Аппаратные ключи (Рутокен MFA, YubiKey) для входа по SSH ----------
    val hwKeySupported: Boolean get() = platform.hwKeySupported
    /** Ключи на токенах (открытые части и credential id); закрытые ключи — только в токенах. */
    val hwKeys = mutableStateListOf<app.hy2setup.hwkey.HwKey>().apply {
        addAll(app.hy2setup.hwkey.HwKey.listFromJson(secrets.get("hwKeys").ifEmpty { secrets.get("hwKey") }))
    }
    /** Отпечаток ключа для входа (вставлен может быть только один токен — выбираем явно). */
    private var hwSelected by mutableStateOf(prefs.getString("hwSelected", "") ?: "")
    /** Ключ для входа: выбранный, иначе первый. */
    val hwKey: app.hy2setup.hwkey.HwKey? get() = hwKeys.firstOrNull { it.fingerprint == hwSelected } ?: hwKeys.firstOrNull()
    /** Ключи, вход по которым уже проверен на сервере (отпечатки). */
    private var hwVerifiedSet by mutableStateOf(prefs.getString("hwVerifiedKeys", "").orEmpty().split(",").filter { it.isNotBlank() }.toSet())
    /** Вход по выбранному ключу уже проверен — только после этого его можно включить для всех входов. */
    val hwVerified: Boolean get() = hwKey?.fingerprint in hwVerifiedSet
    /** Входить на серверы по аппаратному ключу («Доступ к серверам» и серверы Главной). */
    var hwLogin by mutableStateOf(platform.hwKeySupported && prefs.getBoolean("hwLogin", false) && hwKeys.isNotEmpty())
        private set
    /** Текст окна «Коснитесь токена»; null — окно закрыто. */
    var hwPrompt by mutableStateOf<String?>(null)
        private set
    /** Запрос PIN токена (текст); null — не спрашиваем. */
    var pinPrompt by mutableStateOf<String?>(null)
        private set
    private var pinAnswer: CompletableDeferred<String?>? = null

    /** Тестам: подменить список ключей. */
    internal fun setHwKeysForTest(keys: List<app.hy2setup.hwkey.HwKey>) { hwKeys.clear(); hwKeys.addAll(keys) }

    private fun saveHwKeys() {
        secrets.put("hwKeys", app.hy2setup.hwkey.HwKey.listToJson(hwKeys))
        secrets.put("hwKey", "")
        prefs.edit().putString("hwSelected", hwSelected).putString("hwVerifiedKeys", hwVerifiedSet.joinToString(",")).putBoolean("hwLogin", hwLogin).apply()
    }

    /** Канал к токену: открывается, показывая «вставьте токен и коснитесь», закрывается через close(). */
    private inner class HwSession(private val t: app.hy2setup.hwkey.HwTransport) : app.hy2setup.hwkey.HwTransport, java.io.Closeable {
        override val name: String get() = t.name
        override fun send(ins: Int, p1: Int, data: ByteArray): ByteArray = t.send(ins, p1, data)
        override fun cbor(request: ByteArray): ByteArray = t.cbor(request)
        override fun close() { hwPrompt = null; platform.hwKeyClose() }
    }

    /** Ждёт токен (USB-C или NFC) и возвращает канал к нему; окно «Коснитесь токена» висит до close(). */
    private fun hwTransport(): HwSession {
        hwPrompt = "Вставьте токен (Рутокен MFA или YubiKey) в разъём USB-C и коснитесь его, когда он замигает."
        val t = try {
            platform.hwKeyOpen(60_000)
        } catch (e: Exception) {
            hwPrompt = null; platform.hwKeyClose(); throw e
        }
        hwPrompt = "Коснитесь токена${t.name.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}."
        return HwSession(t)
    }

    /** Спрашивает PIN токена у человека (из фонового потока): ждёт ответ в окне; null — отмена. PIN нигде не хранится. */
    private fun askPin(): String? {
        val answer = CompletableDeferred<String?>()
        pinAnswer = answer
        val shown = hwPrompt
        hwPrompt = null
        pinPrompt = "Токен просит PIN — он нужен только сейчас, при создании или загрузке резидентного ключа. Вход на серверы будет без PIN."
        val pin = kotlinx.coroutines.runBlocking { answer.await() }
        pinPrompt = null
        hwPrompt = shown
        return pin
    }

    fun answerPin(pin: String?) {
        pinAnswer?.complete(pin?.takeIf { it.isNotEmpty() })
        pinAnswer = null
        pinPrompt = null
    }

    fun hwCancel() {
        platform.hwKeyCancel()
        hwPrompt = null
        answerPin(null)
    }

    /** Создаёт новый ключ на токене (касание; для резидентного токен может попросить PIN один раз). */
    fun hwCreate(resident: Boolean) {
        launchTask(log) {
            addLog(log, "Создаю " + (if (resident) "резидентный" else "нерезидентный") + " ключ SSH на токене…")
            val key = withContext(Dispatchers.IO) { hwTransport().use { app.hy2setup.hwkey.Hw.create(it, resident, ::askPin) } }
            hwKeys.removeAll { it.fingerprint == key.fingerprint }
            hwKeys.add(key)
            hwSelected = key.fingerprint
            hwLogin = false
            saveHwKeys()
            addLog(log, "✓ Ключ создан: ${key.title} (${key.fingerprint}). Закрытая часть не покидает токен.")
            addLog(log, "Теперь «Добавить ключ на сервер», затем «Проверить вход».")
        }
    }

    /** Загружает с токена резидентные ключи «ssh:» (новый телефон): нужен PIN токена. */
    fun hwLoadResident() {
        launchTask(log) {
            addLog(log, "Читаю резидентные ключи с токена…")
            val keys = withContext(Dispatchers.IO) { hwTransport().use { app.hy2setup.hwkey.Hw.loadResident(it, ::askPin) } }
            if (keys.isEmpty()) { addLog(log, "На токене нет резидентных ключей SSH."); return@launchTask }
            var added = 0
            keys.forEach { k -> if (hwKeys.none { it.fingerprint == k.fingerprint }) { hwKeys.add(k); added++ } }
            if (hwSelected.isEmpty() || hwKeys.none { it.fingerprint == hwSelected }) hwSelected = keys.first().fingerprint
            saveHwKeys()
            addLog(log, "✓ На токене ключей: ${keys.size}, добавлено в приложение: $added.")
        }
    }

    fun hwSelect(fingerprint: String) {
        if (hwKeys.none { it.fingerprint == fingerprint }) return
        hwSelected = fingerprint
        if (fingerprint !in hwVerifiedSet) hwLogin = false
        saveHwKeys()
    }

    /** Убирает ключ из приложения (на токене и на серверах он остаётся). */
    fun hwRemove(fingerprint: String) {
        hwKeys.removeAll { it.fingerprint == fingerprint }
        hwVerifiedSet = hwVerifiedSet - fingerprint
        if (hwSelected == fingerprint) hwSelected = hwKeys.firstOrNull()?.fingerprint.orEmpty()
        if (hwKeys.isEmpty() || !hwVerified) hwLogin = false
        saveHwKeys()
    }

    /** Включает или выключает вход по аппаратному ключу; включить можно только после успешной проверки входа. */
    fun setHwLoginEnabled(on: Boolean) {
        if (on && (hwKey == null || !hwVerified)) { fail("Сначала проверьте вход по аппаратному ключу на сервере."); return }
        hwLogin = on
        saveHwKeys()
    }

    /** Добавляет выбранный ключ токена в authorized_keys сервера из «Доступа к серверам» (вход — по паролю или ключу). */
    fun hwInstall() {
        val key = hwKey ?: run { fail("Сначала создайте ключ на токене."); return }
        val ssh = buildSshConfig(allowHw = false) ?: return
        launchTask(log) {
            addLog(log, "Добавляю ключ (${key.title}) на ${ssh.host}…")
            val code = runner.runScript(ssh, Scripts.addAuthorizedKey(key.authorizedKeysLine())) { postLog(log, it) }
            if (code != 0) throw IllegalStateException("Ключ добавить не удалось (код $code).")
        }
    }

    /** Ставит выбранный ключ токена на все серверы Главной (вход — по ключу приложения или паролю root). */
    fun hwInstallAll() {
        val key = hwKey ?: run { fail("Сначала создайте ключ на токене."); return }
        val targets = stableProfiles.filter { !it.vps?.ip.isNullOrBlank() }.map { p -> Triple(p.fqdn, p.vps!!.ip, accessOf(p)) }
        if (targets.isEmpty()) { fail("На Главной нет серверов."); return }
        launchTask(log) {
            for ((fqdn, ip, a) in targets) {
                val (pass, pk, phrase) = credsOf(a)
                val ssh = when {
                    pk.isNotBlank() -> SshConfig(ip, a.port, a.user, pass, pk, phrase)
                    pass.isNotEmpty() -> SshConfig(ip, a.port, a.user, pass, "", "")
                    else -> { addLog(log, "$fqdn ($ip): ✗ нет пароля и ключа — задайте их в «Доступе по SSH»."); continue }
                }
                addLog(log, "$fqdn ($ip):")
                try {
                    runner.runScript(ssh, Scripts.addAuthorizedKey(key.authorizedKeysLine())) { postLog(log, it) }
                } catch (e: SshException) {
                    addLog(log, "✗ $fqdn: ${e.message}")
                }
            }
        }
    }

    /** Пробный вход по выбранному ключу на сервер из «Доступа к серверам»: касание токена, затем короткий скрипт. */
    fun hwTest() {
        val key = hwKey ?: run { fail("Сначала создайте ключ на токене."); return }
        val ssh = buildSshConfig(allowHw = false, needSecret = false)?.copy(privateKey = "", hwKey = key) ?: return
        launchTask(log) {
            addLog(log, "Вхожу на ${ssh.host} по ключу ${key.title} — коснитесь токена…")
            val seen = AtomicBoolean(false)
            runner.runScript(ssh, Scripts.ping()) { if (it.trim() == Scripts.PING_MARKER) seen.set(true) }
            if (!seen.get()) throw IllegalStateException("Сервер не ответил после входа.")
            hwVerifiedSet = hwVerifiedSet + key.fingerprint
            saveHwKeys()
            addLog(log, "✓ Вход по ключу ${key.title} на ${ssh.host} работает. Включите «Входить по аппаратному ключу», чтобы им пользовались все входы.")
        }
    }

    fun answerHostKey(trust: Boolean) {
        hostKeyPrompt = null
        hostKeyAnswer?.complete(trust)
        hostKeyAnswer = null
    }

    fun loadKeyFrom(doc: Doc) {
        try {
            privateKey = doc.read().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            errorMessage = "Не удалось прочитать файл ключа: ${e.message}"
        }
    }

    fun forgetHosts() {
        runner.forgetHosts()
        log.add("Список доверенных серверов очищен.")
    }

    // =====================================================================
    //  Установка Hysteria2 (при необходимости — сначала DNS)
    // =====================================================================

    /** Имя сервера, которое получится при включённом name.com (для подсказки в интерфейсе). */
    val plannedFqdn: String
        get() {
            val z = zone.trim().lowercase()
            val s = subdomain.trim().lowercase()
            return if (z.isEmpty()) "" else if (s.isEmpty()) z else "$s.$z"
        }

    /**
     * Защищает сервер в самом конце, после установки, если включён переключатель.
     * Ошибка защиты не отменяет установку: ссылки остаются, сервер просто не изменён.
     */
    private suspend fun secureIfNeeded(ssh: SshConfig, target: SnapshotStateList<String>): SshConfig {
        if (!hardenEff) return ssh
        if (vpsMode && !keyUsable()) return ssh   // в «Произвольном VPS» без ключа защита пропускается (см. install)
        addLog(target, "")
        addLog(target, "БЕЗОПАСНОСТЬ: вход по SSH-ключу, обновления, автообновления, отключение входа по паролю")
        val keyCfg = ssh.copy(privateKey = curKey, keyPassphrase = curPhrase, password = curPass)
        return try {
            val secured = Hardener(runner) { postLog(target, it) }.harden(keyCfg, curPass)
            markKeyLogin()
            rememberProfile(secured)
            addLog(target, "Сервер защищён: вход только по ключу.")
            secured
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog(target, "ОШИБКА БЕЗОПАСНОСТИ: ${e.message ?: e.javaClass.simpleName}")
            addLog(target, "Установка выполнена, но защита не включена. Ссылка рабочая; защиту можно включить позже: " +
                if (vpsMode) "«Инструменты» → «Произвольный VPS» → «Защитить»." else "«Инструменты» → «Доступ к серверам» → «Защитить сервер».")
            ssh
        }
    }

    /** Проверка перед запуском режима безопасности в «Доступе к серверам» (Инструменты). */
    /** Приватный ключ текущей цели есть и читается. */
    private fun keyUsable(): Boolean = curKey.isNotBlank() && SshKeys.publicOf(curKey, curPhrase) != null

    private fun securityProblem(): String? = when {
        !hardenEff -> null
        curKey.isBlank() && vpsMode ->
            "Защита сервера: нужен SSH-ключ — выберите «SSH-ключ» в блоке «Сервер» («Произвольный VPS») и вставьте его."
        curKey.isBlank() ->
            "Режим безопасности: вставьте приватный SSH-ключ (тот, что добавлен на сервер / в кабинете OneProvider) — " +
                "переключатель «SSH-ключ» в блоке доступа."
        SshKeys.publicOf(curKey, curPhrase) == null ->
            "Режим безопасности: приватный ключ не читается или неверен пароль от ключа."
        else -> null
    }

    /** DNS уже настроен для установки: имя и IPv4 сервера. */
    private data class DnsDone(val fqdn: String, val ip: String)

    /** Установка одного протокола (после проверки полей): true — получилось. */
    private fun interface ProtoJob {
        suspend fun run(ssh: SshConfig, dns: DnsDone?): Boolean
    }

    /**
     * «Установить» в «Доступе к серверам» (Инструменты): все выбранные протоколы по очереди. Поля проверяются заранее, DNS
     * настраивается один раз; ошибка одного протокола не мешает остальным. Защита сервера — в самом конце.
     */
    fun install() {
        val modes = serverModeList
        // блок «Произвольный VPS»: без SSH-ключа защита невозможна, но VPN по паролю ставится — защиту просто пропускаем
        val skipHarden = vpsMode && hardenEff && !keyUsable()
        if (!skipHarden) securityProblem()?.let { return fail(it) }
        val ssh = buildSshConfig() ?: return
        val withDns = useDns && !vpsMode && modes.any { it != ServerMode.TG }
        val dnsPlan: DnsPlan? = if (withDns) buildDnsPlan(withSubdomain = true) ?: return else null
        // VLESS «под свой домен» сам ставит Hysteria2 (с сертификатом и сайтом) — вторая установка затёрла бы её
        // XHTTP ставится вместе с VLESS (тот же порт и ключи) — одной установкой
        val withXhttp = ServerMode.XHTTP in modes
        val withVless = ServerMode.VLESS in modes || withXhttp
        val hy2InVless = withVless && vlessSelfEff && ServerMode.HY2 in modes
        if (ServerMode.TG in modes && withVless && tgPort.trim() == vlessPort.trim()) {
            return fail("Telegram-прокси и VLESS не могут работать на одном порту TCP (${vlessPort.trim()}) — смените порт одного из них.")
        }
        val jobs = modes.filterNot { (hy2InVless && it == ServerMode.HY2) || (it == ServerMode.XHTTP && ServerMode.VLESS in modes) }.map { m ->
            m to (when (m) {
                ServerMode.HY2 -> planHy2(dnsPlan)
                ServerMode.VLESS, ServerMode.XHTTP -> planVless(dnsPlan, withXhttp)
                ServerMode.AWG -> planAwg()
                ServerMode.TG -> planTg()
            } ?: return)
        }
        persist()
        launchTask(log) {
            resultLinks.clear()
            if (skipHarden) addLog(log, "Защита пропущена: для неё нужен SSH-ключ (блок «Сервер» → «SSH-ключ»). VPN ставится, вход по паролю остаётся.")
            val total = jobs.size + if (dnsPlan != null) 1 else 0
            var n = 1
            var dns: DnsDone? = null
            if (dnsPlan != null) {
                addLog(log, "ШАГ ${n++} из $total. DNS на name.com")
                dns = DnsDone(dnsPlan.fqdn, setupDnsRecord(ssh.host, dnsPlan, overwrite))
            }
            if (hy2InVless) addLog(log, "Hysteria2 ставится вместе с VLESS (маскировка под свой домен) — отдельно её ставить не нужно.")
            val failed = mutableListOf<String>()
            for ((m, job) in jobs) {
                if (total > 1) { addLog(log, ""); addLog(log, "ШАГ ${n++} из $total. ${m.title}") }
                val ok = try {
                    job.run(ssh, dns)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addLog(log, "ОШИБКА: ${e.message ?: e.javaClass.simpleName}")
                    false
                }
                rememberProfile(ssh)
                if (!ok) failed += m.title
            }
            addLog(log, "")
            when {
                failed.isEmpty() -> addLog(log, if (resultLinks.size > 1) "Готово! Ссылки ниже." else "Готово! Ссылка ниже.")
                failed.size < jobs.size -> addLog(log, "Не установилось: ${failed.joinToString()}. Остальное готово — ссылки ниже.")
                else -> {
                    addLog(log, "Установка не удалась.")
                    if (dnsPlan != null) addLog(log, "A-запись ${dnsPlan.fqdn} осталась в name.com. Её можно удалить во вкладке «VPS/DNS».")
                }
            }
            if (failed.size < jobs.size) secureIfNeeded(ssh, log)
        }
    }

    /** Порт AmneziaWG из поля или null с сообщением об ошибке. */
    private fun awgPortOrNull(onError: (String) -> Unit): Int? {
        val n = awgPort.trim().toIntOrNull()
        if (n == null || n !in 1..65535) { onError("Порт AmneziaWG должен быть числом 1–65535."); return null }
        if (n == 443) { onError("Порт 443/UDP занят Hysteria2 — выберите для AmneziaWG другой порт."); return null }
        return n
    }

    /** AmneziaWG 3.1 в «Доступе к серверам» (Инструменты): новые ключи, адрес в ссылке — домен (если есть) или IP сервера. */
    private fun planAwg(): ProtoJob? {
        val port = awgPortOrNull { fail(it) } ?: return null
        val d = if (vpsMode) "" else domain.trim().lowercase()
        if (d.isNotEmpty() && !ZONE.matches(d)) return null.also { fail("Домен указан неверно.") }
        return ProtoJob { ssh, dns ->
            val host = dns?.fqdn ?: d.ifEmpty { ssh.host }
            val p = Awg.generate(host, port, awgTrailers)
            awgInstall(ssh, p, log)
            resultLinks.add(ShareLink(Awg.TITLE, p.vpnLink(host)))
            addLog(log, "AmneziaWG установлен. Ссылку откройте в AmneziaVPN или отсканируйте QR.")
            true
        }
    }

    /**
     * Установка AmneziaWG фоновой задачей; если на сервере поставилось новое ядро — перезагрузка и повтор.
     * Бросает исключение при ошибке.
     */
    private suspend fun awgInstall(ssh: SshConfig, p: AwgParams, target: SnapshotStateList<String>) {
        repeat(2) {
            val reboot = AtomicBoolean(false)
            val code = BackgroundJob(runner) { postLog(target, it) }.run(ssh, "awg", Scripts.installAwg(p), "Установка AmneziaWG") { line ->
                if (line.trim() == Scripts.AWG_REBOOT) { reboot.set(true); true } else false
            }
            if (code != 0) throw IllegalStateException("Установка AmneziaWG завершилась с ошибкой (код $code).")
            if (!reboot.get()) return
            addLog(target, "Перезагружаю сервер, чтобы заработало новое ядро…")
            Hardener(runner) { postLog(target, it) }.rebootAndWait(ssh)
        }
        throw IllegalStateException("После перезагрузки ядро не сменилось — AmneziaWG не установлен.")
    }

    private fun planHy2(dnsPlan: DnsPlan?): ProtoJob? {
        val hyPortNum = hyPort.toIntOrNull()
        if (hyPortNum == null || hyPortNum !in 1..65535) return null.also { fail("Порт Hysteria2 должен быть числом 1–65535.") }
        val e = email.trim()
        val s = sni.trim()
        val m = masquerade.trim()
        if (!SAFE_NAME.matches(e) || !SAFE_NAME.matches(s)) {
            return null.also { fail("E-mail и SNI могут содержать только латиницу, цифры и символы . _ @ + -") }
        }
        if (!Regex("^https://[A-Za-z0-9._~:/?@&=%+-]+$").matches(m)) {
            return null.also { fail("Сайт-маскировка должен начинаться с https:// и не содержать пробелов и кавычек.") }
        }
        val domainName: String
        if (dnsPlan != null) {
            domainName = dnsPlan.fqdn
        } else {
            domainName = if (vpsMode) "" else domain.trim().lowercase()
            if (!SAFE_NAME.matches(domainName)) return null.also { fail("Домен может содержать только латиницу, цифры и символы . _ - ") }
            if (domainName.isEmpty() && s.isEmpty()) return null.also { fail("Укажите SNI (например, www.bing.com) или домен.") }
        }
        return ProtoJob { ssh, dns ->
            val params = InstallParams(
                port = hyPortNum,
                authPassword = randomSecret(),
                obfsPassword = randomSecret(),
                domain = domainName,
                email = e,
                sni = s,
                masqueradeUrl = m,
            )
            val pin = AtomicReference<String?>(null)
            val code = runner.runScript(ssh, Scripts.install(params)) { line ->
                if (line.startsWith(Scripts.PIN_MARKER)) {
                    pin.set(line.removePrefix(Scripts.PIN_MARKER).trim())
                } else {
                    postLog(log, line)
                }
            }
            if (code == 0) {
                resultLinks.add(
                    ShareLink(
                        Protocol.HY2.title,
                        Links.hy2(ssh.host, params.port, params.authPassword, params.obfsPassword, params.domain, params.sni, pin.get()),
                    ),
                )
                true
            } else {
                addLog(log, "Установка Hysteria2 завершилась с ошибкой (код $code).")
                false
            }
        }
    }

    /** Новый путь XHTTP для установки из «Доступа к серверам». */
    private fun newXhttpPath() = "/" + randomHex(5)

    private fun planVless(dnsPlan: DnsPlan?, xhttp: Boolean = false): ProtoJob? {
        val portNum = vlessPort.trim().toIntOrNull()
        if (portNum == null || portNum !in 1..65535) return null.also { fail("Порт VLESS должен быть числом 1–65535.") }
        val xpath = if (xhttp) newXhttpPath() else ""
        if (vlessSelfEff) return planVlessSelf(dnsPlan, portNum, xpath)
        val target = realitySni.trim().lowercase()
        if (!ZONE.matches(target)) return null.also { fail("Сайт для маскировки Reality — имя хоста, например www.microsoft.com.") }
        return ProtoJob { ssh, dns ->
            val address = dns?.fqdn ?: ssh.host
            val params = VlessParams(
                port = portNum,
                uuid = UUID.randomUUID().toString(),
                sni = target,
                shortId = randomHex(8),
                address = address,
                xhttpPath = xpath,
            )
            val pbk = AtomicReference<String?>(null)
            val code = runner.runScript(ssh, Scripts.installVless(params)) { line ->
                if (line.startsWith(Scripts.PBK_MARKER)) {
                    pbk.set(line.removePrefix(Scripts.PBK_MARKER).trim())
                } else {
                    postLog(log, line)
                }
            }
            val key = pbk.get()
            if (code == 0 && !key.isNullOrEmpty()) {
                resultLinks.add(ShareLink(Protocol.VLESS.title, Links.vless(address, portNum, params.uuid, target, key, params.shortId)))
                if (xpath.isNotEmpty()) {
                    resultLinks.add(ShareLink(Links.XHTTP_TITLE, Links.vlessXhttp(address, portNum, params.uuid, target, key, params.shortId, xpath)))
                }
                true
            } else {
                addLog(log, "Установка VLESS завершилась с ошибкой (код $code).")
                false
            }
        }
    }

    /**
     * VLESS под свой домен: Hysteria2 (сертификат Let's Encrypt и сайт на 127.0.0.1:8443)
     * → VLESS + Reality с SNI = домен. Получаются две ссылки.
     */
    private fun planVlessSelf(dnsPlan: DnsPlan?, portNum: Int, xpath: String): ProtoJob? {
        val name = dnsPlan?.fqdn ?: domainEff
        if (name.isEmpty() || !ZONE.matches(name)) {
            return null.also {
                fail(
                    if (vpsMode) "Для маскировки под свой сервер впишите домен, который уже указывает на IP этого сервера (A-запись), — или выключите маскировку в «Параметрах VLESS»."
                    else "Для маскировки под свой домен укажите домен: включите name.com или впишите домен, который уже указывает на этот сервер.",
                )
            }
        }
        val hyPortNum = hyPort.trim().toIntOrNull()
        if (hyPortNum == null || hyPortNum !in 1..65535) return null.also { fail("Порт Hysteria2 должен быть числом 1–65535.") }
        val e = emailEff
        if (!SAFE_NAME.matches(e)) return null.also { fail("E-mail может содержать только латиницу, цифры и символы . _ @ + -") }
        val m = masquerade.trim()
        if (!Regex("^https://[A-Za-z0-9._~:/?@&=%+-]+$").matches(m)) {
            return null.also { fail("Сайт-маскировка должен начинаться с https:// и не содержать пробелов и кавычек.") }
        }
        val checkName = vpsMode && dnsPlan == null
        return ProtoJob { ssh, _ ->
            // блок «Произвольный VPS»: имя должно уже указывать на этот сервер, иначе Let's Encrypt не выдаст сертификат
            if (checkName) {
                val ip = resolveIpv4(ssh.host)
                val a = DnsCheck.resolveA(name)
                if (ip == null || ip !in a) {
                    addLog(log, "ОШИБКА: имя $name " + (if (a.isEmpty()) "не найдено в DNS" else "указывает на ${a.joinToString()}") +
                        ", а сервер — ${ip ?: ssh.host}. Сделайте у регистратора A-запись $name → ${ip ?: "IP сервера"} и повторите установку " +
                        "(новая запись расходится от нескольких минут до часа).")
                    return@ProtoJob false
                }
                addLog(log, "✓ $name указывает на этот сервер ($ip).")
            }
            addLog(log, "Hysteria2: сертификат Let's Encrypt на $name и сайт для маскировки VLESS")
            val hy = SelfMask.hy2(name, hyPortNum, randomSecret(), randomSecret(), e, m)
            val hyCode = runner.runScript(ssh, Scripts.install(hy)) { if (!Links.isMarker(it)) postLog(log, it) }
            if (hyCode != 0) {
                addLog(log, "Установка Hysteria2 завершилась с ошибкой (код $hyCode) — без неё VLESS не на что маскироваться, VLESS не ставится.")
                return@ProtoJob false
            }
            addLog(log, "")
            addLog(log, "VLESS + Reality с маскировкой под $name")
            val vl = SelfMask.vless(name, portNum, UUID.randomUUID().toString(), randomHex(8)).copy(xhttpPath = xpath)
            val pbk = AtomicReference<String?>(null)
            val code = runner.runScript(ssh, Scripts.installVless(vl)) { line ->
                if (line.startsWith(Scripts.PBK_MARKER)) pbk.set(line.removePrefix(Scripts.PBK_MARKER).trim()) else postLog(log, line)
            }
            val key = pbk.get()
            resultLinks.add(ShareLink(Protocol.HY2.title, Links.hy2(name, hy.port, hy.authPassword, hy.obfsPassword, name, name, null)))
            if (code == 0 && !key.isNullOrEmpty()) {
                resultLinks.add(resultLinks.size - 1, ShareLink(Protocol.VLESS.title, Links.vless(name, portNum, vl.uuid, name, key, vl.shortId)))
                if (xpath.isNotEmpty()) {
                    resultLinks.add(resultLinks.size - 1, ShareLink(Links.XHTTP_TITLE, Links.vlessXhttp(name, portNum, vl.uuid, name, key, vl.shortId, xpath)))
                }
                addLog(log, "VLESS (маскировка под $name) и Hysteria2 установлены. Если сертификат Let's Encrypt ещё выпускается, " +
                    "VLESS заработает через минуту-другую после него.")
                true
            } else {
                addLog(log, "Установка VLESS завершилась с ошибкой (код $code). Hysteria2 установлена — её ссылка ниже.")
                false
            }
        }
    }

    /** Создаёт A-запись. Возвращает IPv4 сервера. */
    private suspend fun setupDnsRecord(
        sshHost: String,
        plan: DnsPlan,
        allowOverwrite: Boolean,
        target: SnapshotStateList<String> = log,
        /** Удалить AAAA того же имени: иначе клиенты с IPv6 уходят не на этот сервер (Комбайн+). */
        dropAaaa: Boolean = false,
    ): String {
        val ip = resolveIpv4(sshHost)
            ?: throw NameComException("Для A-записи нужен IPv4-адрес сервера. Введите IPv4 (например, 203.0.113.10) в поле «Адрес» («Инструменты» → «Доступ к серверам»).")
        val client = ncApi(plan.username, plan.token)
        addLog(target, "Проверяю доступ к name.com…")
        client.hello()
        addLog(target, "Читаю записи зоны ${plan.zone}…")
        val sameName = client.listRecords(plan.zone).filter { sameFqdn(it, plan.fqdn) }

        if (sameName.any { it.type == "CNAME" }) {
            throw NameComException("Для ${plan.fqdn} уже есть запись CNAME, она несовместима с A. Удалите её во вкладке «VPS/DNS».")
        }
        if (dropAaaa) {
            for (r in sameName.filter { it.type == "AAAA" }) {
                client.deleteRecord(plan.zone, r.id)
                addLog(target, "Удалена IPv6-запись ${r.displayName} → ${r.answer}: клиенты подключались бы по ней не к этому серверу.")
                delay(100)
            }
        }
        val aRecords = sameName.filter { it.type == "A" }
        if (aRecords.any { it.answer == ip }) {
            addLog(target, "A-запись ${plan.fqdn} → $ip уже существует, создавать заново не нужно.")
            return ip
        }
        if (aRecords.isNotEmpty()) {
            val old = aRecords.joinToString { it.answer }
            if (!allowOverwrite) {
                throw NameComException(
                    "Для ${plan.fqdn} уже есть A-запись ($old). Включите «Перезаписать существующую A-запись» " +
                        "или удалите её во вкладке «VPS/DNS».",
                )
            }
            for (r in aRecords) {
                client.deleteRecord(plan.zone, r.id)
                addLog(target, "Удалена старая запись: ${r.displayName} → ${r.answer}")
                delay(100)
            }
        }
        val created = client.createRecord(plan.zone, plan.subdomain, "A", ip, plan.ttl)
        addLog(target, "Создана запись: ${created.displayName.ifEmpty { plan.fqdn }} → $ip (A, TTL ${plan.ttl})")
        return ip
    }

    /** При переезде A-запись mcp.aeromike.org тоже уходит на новый IP. Сбой не прерывает переезд — только предупреждение. */
    private suspend fun moveMcpRecord(ip: String, plan: DnsPlan, target: SnapshotStateList<String>) {
        try {
            setupDnsRecord(ip, DnsPlan(plan.username, plan.token, MCP_ZONE, MCP_HOST, plan.ttl), allowOverwrite = true, target = target, dropAaaa = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog(target, "ПРЕДУПРЕЖДЕНИЕ: запись $MCP_HOST.$MCP_ZONE не обновлена (${e.message}). Исправьте её во вкладке «VPS/DNS».")
        }
    }

    // =====================================================================
    //  Управление сервером
    // =====================================================================

    /** Общий статус уже показывает и Telegram-прокси; отдельный — только если выбран один Telegram. */
    fun status() = simpleServerScript(
        if (serverModes.any { it != ServerMode.TG }) Scripts.status() else Scripts.statusTelegram(),
    )

    /** «Подключение есть, интернета нет»: проверка сервера и DNS имени. */
    fun diagnose() {
        val ssh = buildSshConfig() ?: return
        val name = when {
            vpsMode -> if (vlessSelfEff) domainEff else ""
            useDns -> plannedFqdn
            protocol == Protocol.HY2 || vlessSelf -> domain.trim().lowercase()
            else -> plusProfile?.fqdn.orEmpty()
        }
        val sni = if (protocol == Protocol.VLESS && vlessSelfEff && name.isNotEmpty()) name else realitySni.trim()
        // с телефона проверяется порт VLESS (TCP) из параметров, а не всегда 443
        val tcpPort = vlessPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
        persist()
        launchTask(log) {
            addLog(log, "Проверяю сервер ${ssh.host}" + (if (name.isNotEmpty()) " и имя $name" else "") + " …")
            runDiagnose(ssh, name, log, sni, tcpPort)
        }
    }

    /**
     * Проверка «заморозки» (ТСПУ): большая страница сайта маскировки Reality через сервер и напрямую.
     * Возвращает true, если найдена проблема.
     */
    private suspend fun freezeCheck(serverIp: String, target: SnapshotStateList<String>, realityName: String, port: Int = 443): Boolean {
        val sni = realityName.trim().ifEmpty { "www.microsoft.com" }
        addLog(target, "Скачиваю страницу $sni через сервер (проверка «заморозки после 16 КБ»)…")
        val via = FreezeTest.download(serverIp, sni, port = port)
        if (via.bytes >= FreezeTest.ENOUGH) {
            addLog(target, "OK: через сервер скачано ${via.kb} КБ — соединение не замерзает")
            return false
        }
        val direct = FreezeTest.download(sni, sni)
        return when {
            direct.bytes >= FreezeTest.ENOUGH && !via.complete -> {
                addLog(target, "ПРОБЛЕМА: соединение ${Device.GEN} с сервером замерзает после ${via.kb} КБ (${via.error}), " +
                    "а напрямую с $sni скачалось ${direct.kb} КБ. Так провайдер (ТСПУ) режет трафик к этому хостингу: " +
                    "VPN подключается, но данные не идут")
                true
            }
            direct.bytes >= FreezeTest.ENOUGH -> {
                addLog(target, "ИНФО: через сервер получен короткий ответ (${via.kb} КБ) — проверку заморозки выполнить не удалось " +
                    "(возможно, на сервере нет VLESS)")
                false
            }
            else -> {
                addLog(target, "ИНФО: проверку заморозки выполнить не удалось: и напрямую $sni не скачался (${direct.error ?: "${direct.kb} КБ"})")
                false
            }
        }
    }

    /**
     * Диагностика: скрипт на сервере и проверки с телефона (публичный DNS, TCP 443).
     * Возвращает число найденных проблем.
     */
    private suspend fun runDiagnose(ssh: SshConfig, fqdn: String, target: SnapshotStateList<String>, realityName: String, tcpPort: Int = 443): Int {
        var problems = 0
        runner.runScript(ssh, Scripts.diagnose(fqdn)) { line ->
            if (line.startsWith("ПРОБЛЕМА:")) problems++
            postLog(target, line)
        }
        addLog(target, "")
        addLog(target, "==> Проверка с ${Device.GEN}")
        if (fqdn.isNotEmpty()) {
            val a = DnsCheck.resolveA(fqdn)
            val ip = resolveIpv4(ssh.host)
            when {
                a.isEmpty() -> { problems++; addLog(target, "ПРОБЛЕМА: публичные DNS не знают имя $fqdn") }
                ip != null && ip !in a -> { problems++; addLog(target, "ПРОБЛЕМА: публичные DNS: $fqdn → ${a.joinToString()}, а сервер — $ip") }
                else -> addLog(target, "OK: публичные DNS: $fqdn → ${a.joinToString()}")
            }
            val aaaa = DnsCheck.resolveAAAA(fqdn)
            if (aaaa.isNotEmpty()) {
                problems++
                addLog(target, "ПРОБЛЕМА: у $fqdn есть IPv6-запись (AAAA) ${aaaa.joinToString()} — удалите её во вкладке «VPS/DNS»")
            }
        }
        val host = fqdn.ifEmpty { ssh.host }
        if (tcpOpen(host, tcpPort)) {
            addLog(target, "OK: с ${Device.GEN} открывается $host:$tcpPort/TCP")
            if (freezeCheck(resolveIpv4(host) ?: host, target, realityName, tcpPort)) problems++
        } else {
            problems++
            addLog(target, "ПРОБЛЕМА: с ${Device.GEN} не открывается $host:$tcpPort/TCP — закрыт файрвол хостинга или сервер недоступен")
        }
        addLog(target, if (problems == 0) "Проверка завершена: проблем не найдено." else "Проверка завершена: найдено проблем — $problems.")
        return problems
    }
    /** Перезапуск служб всех выбранных протоколов. */
    fun restartService() = simpleServerScript(
        *serverModeList.map { if (it == ServerMode.TG) Scripts.restartTelegram() else Scripts.restart(protoOf(it)) }.distinct().toTypedArray(),
    )

    /** Получает ссылки по уже установленным на сервере протоколам. */
    fun fetchLinks() {
        val ssh = buildSshConfig() ?: return
        persist()
        launchTask(log) {
            resultLinks.clear()
            addLog(log, "Подключаюсь к ${ssh.host}:${ssh.port} и читаю настройки…")
            val markers = java.util.Collections.synchronizedList(mutableListOf<String>())
            var code = 0
            if (serverModes.any { it != ServerMode.TG }) code = runner.runScript(ssh, Scripts.exportLinks()) { line ->
                if (Links.isMarker(line)) markers += line else postLog(log, line)
            }
            // с маскировкой под свой сервер клиенты подключаются по имени, а не по IP
            val address = if (vpsMode && vlessSelfEff && domainEff.isNotEmpty()) domainEff else ssh.host
            val links = Links.fromExport(markers.toList(), address).toMutableList()
            if (ServerMode.TG in serverModes) {
                val tg = java.util.Collections.synchronizedList(mutableListOf<String>())
                runner.runScript(ssh, Scripts.exportTelegram()) { line -> if (Links.isMarker(line)) tg += line else postLog(log, line) }
                Links.telegramFromMarkers(tg.toList(), ssh.host)?.let { links += it }
            }
            rememberProfile(ssh)
            resultLinks.addAll(links)
            when {
                code != 0 -> addLog(log, "Завершено с кодом $code.")
                links.isEmpty() -> addLog(log, "Ссылки получить не удалось.")
                else -> addLog(log, "Ссылок получено: ${links.size}. Они ниже.")
            }
        }
    }

    /** Удаление всех выбранных протоколов с сервера. */
    fun remove() {
        resultLinks.clear()
        // XHTTP без VLESS — убрать только XHTTP; вместе с VLESS он уйдёт и так
        simpleServerScript(
            *serverModeList.mapNotNull {
                when (it) {
                    ServerMode.TG -> Scripts.removeTelegram()
                    ServerMode.XHTTP -> Scripts.removeXhttp().takeIf { ServerMode.VLESS !in serverModes }
                    else -> Scripts.remove(protoOf(it))
                }
            }.toTypedArray(),
        )
    }

    private fun protoOf(m: ServerMode) = when (m) {
        ServerMode.VLESS, ServerMode.XHTTP -> Protocol.VLESS
        ServerMode.AWG -> Protocol.AWG
        else -> Protocol.HY2
    }

    private fun simpleServerScript(vararg scripts: String) {
        val ssh = buildSshConfig() ?: return
        persist()
        launchTask(log) {
            addLog(log, "Подключаюсь к ${ssh.host}:${ssh.port} …")
            for (script in scripts) {
                val code = runner.runScript(ssh, script) { postLog(log, it) }
                if (code != 0) addLog(log, "Завершено с кодом $code.")
            }
            rememberProfile(ssh)
        }
    }

    // =====================================================================
    //  Вкладка DNS: список и удаление записей
    // =====================================================================

    /** Записи с учётом фильтра. */
    val visibleRecords: List<DnsRecord>
        get() = dnsRecords.filter { !onlyAddress || it.type == "A" || it.type == "AAAA" }

    /** Корневые NS-записи не удаляем: без них домен перестанет работать. */
    fun isProtected(r: DnsRecord) = r.type == "NS" && r.host.isEmpty()

    fun loadDns() {
        val plan = buildDnsPlan(withSubdomain = false, forDnsTab = true) ?: return
        persist()
        launchTask(dnsLog, clear = true) {
            addLog(dnsLog, "Загружаю записи зоны ${plan.zone}…")
            val list = NameComClient(plan.username, plan.token).listRecords(plan.zone)
            dnsRecords.clear()
            dnsRecords.addAll(list.sortedWith(compareBy({ it.type }, { it.fqdn })))
            selected.clear()
            loadedZone = plan.zone
            dnsLoaded = true
            addLog(dnsLog, "Найдено записей: ${list.size}")
        }
    }

    fun toggle(id: Int) {
        if (!selected.remove(id)) selected.add(id)
    }

    fun selectAllVisible() {
        selected.clear()
        selected.addAll(visibleRecords.filter { !isProtected(it) }.map { it.id })
    }

    fun clearSelection() = selected.clear()

    val selectedRecords: List<DnsRecord>
        get() = dnsRecords.filter { it.id in selected }

    fun deleteSelected() {
        val plan = buildDnsPlan(withSubdomain = false, forDnsTab = true) ?: return
        val zoneName = loadedZone.ifEmpty { plan.zone }
        val targets = selectedRecords.filter { !isProtected(it) }
        if (targets.isEmpty()) return
        launchTask(dnsLog, clear = true) {
            val client = NameComClient(plan.username, plan.token)
            var ok = 0
            var failed = 0
            for (r in targets) {
                try {
                    client.deleteRecord(zoneName, r.id)
                    ok++
                    addLog(dnsLog, "✓ Удалено: ${r.displayName} ${r.type} ${r.answer}")
                } catch (e: NameComException) {
                    failed++
                    addLog(dnsLog, "✗ ${r.displayName} ${r.type}: ${e.message}")
                }
                delay(100)
            }
            addLog(dnsLog, "Итого: удалено $ok, с ошибкой $failed.")
            val list = client.listRecords(zoneName)
            dnsRecords.clear()
            dnsRecords.addAll(list.sortedWith(compareBy({ it.type }, { it.fqdn })))
            selected.clear()
        }
    }

    /** Подставить IPv4 сервера из «Доступа к серверам» в значение новой записи. */
    fun fillServerIp() {
        val h = host.trim()
        if (h.isEmpty()) { failDns("В «Доступе к серверам» (Инструменты) не указан адрес сервера."); return }
        scope.launch {
            val ip = resolveIpv4(h)
            if (ip == null) failDns("Не удалось определить IPv4 для «$h».") else { newType = "A"; newAnswer = ip; dnsError = null }
        }
    }

    /** Полное имя новой записи для подсказки. */
    val newRecordFqdn: String
        get() {
            val z = zone.trim().lowercase()
            val h = newHost.trim().lowercase().removeSuffix(".").let { if (it == "@") "" else it }
            return if (z.isEmpty()) h else if (h.isEmpty()) z else "$h.$z"
        }

    fun addRecord() {
        val plan = buildDnsPlan(withSubdomain = false, forDnsTab = true) ?: return
        val type = newType
        val hostPart = newHost.trim().lowercase().removeSuffix(".").let { if (it == "@") "" else it }
        if (hostPart.isNotEmpty() && !RECORD_HOST.matches(hostPart)) {
            return failDns("Имя: латиница, цифры, точка, дефис, подчёркивание; можно «*» для wildcard. Пусто или @ — сам домен.")
        }
        var answer = newAnswer.trim()
        when (type) {
            "A" -> if (!IPV4.matches(answer)) return failDns("Для записи A нужен IPv4-адрес, например 203.0.113.10.")
            "AAAA" -> if (!IPV6.matches(answer) || !answer.contains(':')) return failDns("Для записи AAAA нужен IPv6-адрес, например 2001:db8::1.")
            "CNAME", "MX" -> {
                answer = answer.lowercase().removeSuffix(".")
                if (!ZONE.matches(answer)) return failDns("Укажите целевое имя хоста, например mail.example.com.")
                if (type == "CNAME" && hostPart.isEmpty()) return failDns("CNAME нельзя создать для самого домена — укажите поддомен.")
            }
            "TXT" -> if (answer.isEmpty() || answer.contains('\n')) return failDns("Введите текст записи TXT одной строкой.")
        }
        val ttlNum = newTtl.trim().toIntOrNull()
        if (ttlNum == null || ttlNum !in 300..86400) return failDns("TTL должен быть числом от 300 до 86400 секунд.")
        val priority = if (type == "MX") {
            newPriority.trim().toIntOrNull()?.takeIf { it in 0..65535 } ?: return failDns("Приоритет MX — число от 0 до 65535.")
        } else null
        persist()
        launchTask(dnsLog, clear = true) {
            val client = NameComClient(plan.username, plan.token)
            val fqdn = if (hostPart.isEmpty()) plan.zone else "$hostPart.${plan.zone}"
            addLog(dnsLog, "Создаю запись: $fqdn $type $answer (TTL $ttlNum)…")
            client.createRecord(plan.zone, hostPart, type, answer, ttlNum, priority)
            addLog(dnsLog, "✓ Запись создана.")
            newHost = ""
            newAnswer = ""
            val list = client.listRecords(plan.zone)
            dnsRecords.clear()
            dnsRecords.addAll(list.sortedWith(compareBy({ it.type }, { it.fqdn })))
            selected.clear()
            loadedZone = plan.zone
            dnsLoaded = true
        }
    }

    // ---------- Сохранённые серверы ----------

    private fun secretKey(p: ServerProfile, field: String) = "p:${p.id}:$field"

    /** Вызывается после успешного SSH-подключения: сервер попадает в список (или поднимается наверх). */
    private fun rememberProfile(cfg: SshConfig) {
        if (vpsMode) return rememberVps(cfg)
        val p = ServerProfile(cfg.host, cfg.port, cfg.user, cfg.privateKey.isNotBlank())
        val updated = ServerProfiles.upsert(profiles, p)
        profiles.clear()
        profiles.addAll(updated)
        secrets.put(secretKey(p, "password"), cfg.password)
        secrets.put(secretKey(p, "privateKey"), cfg.privateKey)
        secrets.put(secretKey(p, "passphrase"), cfg.keyPassphrase)
        prefs.edit().putString("profiles", ServerProfiles.encode(profiles)).apply()
    }

    /** Подставляет сохранённый сервер в поля. */
    fun selectProfile(p: ServerProfile) {
        host = p.host
        sshPort = p.port.toString()
        user = p.user
        useKey = p.useKey
        password = secrets.get(secretKey(p, "password"))
        privateKey = secrets.get(secretKey(p, "privateKey"))
        passphrase = secrets.get(secretKey(p, "passphrase"))
        errorMessage = null
        persist()
        accessSaved = draft()
    }

    /** Удаляет сервер из списка вместе с его сохранёнными паролем и ключом. */
    fun deleteProfile(p: ServerProfile) {
        profiles.removeAll { it.id == p.id }
        secrets.put(secretKey(p, "password"), "")
        secrets.put(secretKey(p, "privateKey"), "")
        secrets.put(secretKey(p, "passphrase"), "")
        prefs.edit().putString("profiles", ServerProfiles.encode(profiles)).apply()
    }

    // ---------- «Инструменты → Доступ по SSH»: данные входа на серверы, правка и «Применить» ----------

    /** Чей доступ правится: домен сервера Главной или "" — «Другой сервер» (по адресу). */
    var accessTarget by mutableStateOf(prefs.getString("accessTarget", null) ?: stableProfiles.firstOrNull { it.vps?.ip == host.trim() }?.fqdn.orEmpty())
        private set

    /** Поля формы «Доступ по SSH» — черновик; сохраняются кнопкой «Применить». */
    private data class AccessDraft(val host: String, val port: String, val user: String, val useKey: Boolean, val password: String, val key: String, val phrase: String)

    private fun draft() = AccessDraft(host.trim(), sshPort.trim(), user.trim(), useKey, password, privateKey, passphrase)
    private var accessSaved by mutableStateOf<AccessDraft?>(null)

    /** Подставляет в поля сохранённые данные входа на сервер Главной. */
    private fun loadAccess(p: StableProfile) {
        val a = accessOf(p)
        val (pass, key, phrase) = credsOf(a)
        host = a.host; sshPort = a.port.toString(); user = a.user
        password = pass; privateKey = key; passphrase = phrase
        useKey = if (key.isBlank()) false else a.useKey || pass.isEmpty()
        accessSaved = draft()
    }

    init {
        stableProfiles.firstOrNull { it.fqdn == accessTarget }?.let { loadAccess(it) } ?: run { accessTarget = "" }
        if (accessSaved == null) accessSaved = draft()
    }

    /** В форме есть неприменённые изменения. */
    val accessDirty: Boolean get() = accessSaved?.let { it != draft() } ?: false

    /** На Главной не удалось войти на сервер — показать кнопку «Исправить доступ по SSH». */
    var plusAccessProblem by mutableStateOf(false)

    /** Сохранённые данные входа на сервер Главной: адрес — IP его VPS, порт и пользователь — из списка серверов. */
    private fun accessOf(p: StableProfile): ServerProfile {
        val ip = p.vps?.ip.orEmpty()
        return profiles.firstOrNull { it.host == ip } ?: ServerProfile(ip, 22, "root", privateKey.isNotBlank())
    }

    /** Пароль, ключ и пароль ключа для сервера; если для него ничего не сохранено — общие (как до 4.1). */
    private fun credsOf(a: ServerProfile): Triple<String, String, String> {
        val pass = secrets.get(secretKey(a, "password"))
        val key = secrets.get(secretKey(a, "privateKey"))
        if (pass.isEmpty() && key.isBlank()) return Triple(password, privateKey, passphrase)
        return Triple(pass, key, secrets.get(secretKey(a, "passphrase")))
    }

    /** Подпись сервера в списке «Сервер». */
    fun accessTitle(fqdn: String): String = stableProfiles.firstOrNull { it.fqdn == fqdn }?.let { p ->
        p.fqdn + (p.vps?.ip?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    } ?: "Другой сервер"

    /** Выбирает, чей доступ править, и подставляет его сохранённые данные в поля. */
    fun accessSelect(fqdn: String) {
        if (running) return
        val p = stableProfiles.firstOrNull { it.fqdn == fqdn }
        accessTarget = p?.fqdn.orEmpty()
        prefs.edit().putString("accessTarget", accessTarget).apply()
        if (p != null) {
            loadAccess(p)
        } else {
            host = prefs.getString("host", "") ?: ""
            sshPort = prefs.getString("sshPort", "22") ?: "22"
            user = prefs.getString("user", "root") ?: "root"
            useKey = prefs.getBoolean("useKey", false)
            password = secrets.get("password"); privateKey = secrets.get("privateKey"); passphrase = secrets.get("passphrase")
        }
        errorMessage = null
        accessSaved = draft()
    }

    /** При открытии «Инструментов»: если правок нет — подтянуть свежие данные (сервер мог переехать на новый IP). */
    fun accessRefresh() {
        if (!running && !accessDirty) accessSelect(accessTarget)
    }

    /** Отменяет неприменённые изменения в форме. */
    fun accessRevert() = accessSelect(accessTarget)

    /** С Главной: открыть «Инструменты → Доступ по SSH» для этого сервера. */
    fun editAccess(fqdn: String) {
        if (running) return
        accessSelect(fqdn)
        selectedTab = TAB_TOOLS
    }

    /**
     * «Применить»: сохраняет адрес, порт, пользователя, пароль и ключ. Для сервера Главной новый IP записывается
     * в его профиль (A-запись домена не меняется), данные входа — в список серверов.
     */
    fun accessApply() {
        if (running) return
        errorMessage = null
        val h = host.trim()
        val port = sshPort.trim().toIntOrNull()
        val u = user.trim()
        val p = stableProfiles.firstOrNull { it.fqdn == accessTarget }
        val problem = when {
            p != null && !IPV4.matches(h) -> "Укажите IP-адрес сервера (например, 203.0.113.10)."
            h.isEmpty() || !HOSTNAME.matches(h) -> "Укажите IP-адрес или домен сервера."
            port == null || port !in 1..65535 -> "SSH-порт должен быть числом 1–65535."
            u.isEmpty() -> "Укажите пользователя (обычно root)."
            useKey && privateKey.isBlank() -> "Вставьте приватный SSH-ключ или выберите файл ключа."
            useKey && SshKeys.publicOf(privateKey, passphrase) == null -> "Приватный ключ не читается или неверен пароль от ключа."
            !useKey && password.isEmpty() -> "Введите пароль."
            else -> null
        }
        if (problem != null) { errorMessage = problem; return }
        val a = ServerProfile(h, port!!, u, useKey)
        if (p != null) {
            val oldIp = p.vps?.ip.orEmpty()
            if (oldIp != h) {
                storeStable(p.copy(vps = (p.vps ?: ProfileVps("", h, "")).copy(ip = h)))
                profiles.removeAll { it.host == oldIp }
            }
            profiles.removeAll { it.host == h && it.id != a.id }
        }
        val updated = ServerProfiles.upsert(profiles, a)
        profiles.clear(); profiles.addAll(updated)
        secrets.put(secretKey(a, "password"), password)
        secrets.put(secretKey(a, "privateKey"), if (useKey) privateKey else "")
        secrets.put(secretKey(a, "passphrase"), if (useKey) passphrase else "")
        prefs.edit().putString("profiles", ServerProfiles.encode(profiles)).apply()
        if (p == null) persist()
        accessSaved = draft()
        plusAccessProblem = false
        log.add("✓ Доступ ${if (p != null) "к ${p.fqdn} " else ""}сохранён: $u@$h:$port · " + if (useKey) "SSH-ключ" else "пароль")
        if (p != null && p.vps?.ip != h) {
            log.add("IP сервера ${p.fqdn} изменён на $h. A-запись домена не менялась — если она указывает на старый адрес, исправьте её во вкладке «VPS/DNS».")
        }
    }

    /** «Проверить подключение»: входит на сервер с данными из формы. */
    fun accessTest() {
        val ssh = buildSshConfig() ?: return
        launchTask(log) {
            addLog(log, "Подключаюсь к ${ssh.user}@${ssh.host}:${ssh.port}…")
            val seen = AtomicBoolean(false)
            runner.runScript(ssh, Scripts.ping()) { if (it.trim() == Scripts.PING_MARKER) seen.set(true) }
            if (!seen.get()) throw IllegalStateException("Сервер не ответил после входа.")
            addLog(log, "✓ Вход на ${ssh.host} работает." + if (accessDirty) " Нажмите «Применить», чтобы сохранить данные." else "")
        }
    }

    // ---------- «Ключи для всех серверов»: открытые ключи, которые ставятся на каждый создаваемый сервер ----------

    /** Открытые ключи (например, YubiKey), которые приложение дописывает в authorized_keys новых серверов. */
    val defaultKeys = mutableStateListOf<PublicKeyLine>().apply {
        addAll(DefaultKeys.decode(prefs.getString("defaultKeys", null)))
        // первый запуск: начальный список из сборки (локальный default-keys.pub); удалённые ключи не возвращаются
        if (!prefs.getBoolean("defaultKeysSeeded", false)) {
            val seed = runCatching {
                MainViewModel::class.java.getResourceAsStream("/hy2setup/default-keys.txt")?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            val merged = DefaultKeys.merge(this, DefaultKeys.parseAll(seed).first)
            clear(); addAll(merged)
            prefs.edit().putString("defaultKeys", DefaultKeys.encode(merged)).putBoolean("defaultKeysSeeded", true).apply()
        }
    }

    /** Ставить ли ключи из списка на новые серверы (создание и замена сервера). */
    var defaultKeysOn by mutableStateOf(prefs.getBoolean("defaultKeysOn", true))
        private set

    /** Ошибка добавления ключа (разбор строки или файла). */
    var defaultKeysError by mutableStateOf<String?>(null)

    private fun setDefaultKeys(list: List<PublicKeyLine>) {
        defaultKeys.clear(); defaultKeys.addAll(list)
        prefs.edit().putString("defaultKeys", DefaultKeys.encode(list)).apply()
    }

    fun setDefaultKeysEnabled(on: Boolean) {
        defaultKeysOn = on
        prefs.edit().putBoolean("defaultKeysOn", on).apply()
    }

    /** Добавляет ключи из вставленного текста или файла .pub (в файле может быть несколько строк). true — что-то добавлено. */
    fun addDefaultKeys(text: String): Boolean {
        defaultKeysError = null
        val (keys, bad) = DefaultKeys.parseAll(text)
        if (keys.isEmpty()) {
            defaultKeysError = "Это не открытый ключ SSH. Нужна строка вида «ssh-ed25519 AAAA… комментарий» или «sk-ssh-ed25519@openssh.com AAAA…»."
            return false
        }
        val fresh = keys.filter { k -> defaultKeys.none { it.blob == k.blob } }
        setDefaultKeys(DefaultKeys.merge(defaultKeys, keys))
        log.add(if (fresh.isEmpty()) "Эти ключи уже есть в списке." else "✓ В «Ключи для всех серверов» добавлено: ${fresh.joinToString { it.title }}.")
        if (bad > 0) defaultKeysError = "Строк, которые не являются ключом, пропущено: $bad."
        return true
    }

    fun addDefaultKeysFrom(doc: Doc) {
        val text = try { doc.read().toString(Charsets.UTF_8) } catch (e: Exception) { defaultKeysError = "Не удалось прочитать файл: ${e.message}"; return }
        addDefaultKeys(text)
    }

    fun removeDefaultKey(blob: String) {
        val k = defaultKeys.firstOrNull { it.blob == blob } ?: return
        setDefaultKeys(defaultKeys.filter { it.blob != blob })
        log.add("Ключ ${k.title} убран из списка. На серверах, где он уже стоит, он остаётся.")
    }

    /** Ставит ключи из списка на сервер из «Доступа по SSH». */
    fun defaultKeysInstall() {
        if (defaultKeys.isEmpty()) { fail("Список ключей пуст."); return }
        val ssh = buildSshConfig() ?: return
        val lines = defaultKeys.map { it.line }
        launchTask(log) {
            addLog(log, "Добавляю ключи (${lines.size}) на ${ssh.host}…")
            val code = runner.runScript(ssh, Scripts.addAuthorizedKeys(lines)) { postLog(log, it) }
            if (code != 0) throw IllegalStateException("Ключи не добавлены (код $code).")
        }
    }

    /** Ставит ключи из списка на все серверы Главной (данные входа — из «Доступа по SSH»). */
    fun defaultKeysInstallAll() {
        if (defaultKeys.isEmpty()) { fail("Список ключей пуст."); return }
        val targets = stableProfiles.filter { !it.vps?.ip.isNullOrBlank() }.map { p -> p to accessOf(p) }
        if (targets.isEmpty()) { fail("На Главной нет серверов."); return }
        val lines = defaultKeys.map { it.line }
        launchTask(log) {
            var ok = 0
            for ((p, a) in targets) {
                val ip = p.vps!!.ip
                addLog(log, "${p.fqdn} ($ip):")
                val (pass, pk, phrase) = credsOf(a)
                val ssh = when {
                    hwLogin && hwKey != null -> SshConfig(ip, a.port, a.user, pass, "", "", hwKey)
                    pk.isNotBlank() -> SshConfig(ip, a.port, a.user, pass, pk, phrase)
                    pass.isNotEmpty() -> SshConfig(ip, a.port, a.user, pass, "", "")
                    else -> { addLog(log, "✗ нет пароля и ключа — задайте их в «Доступе по SSH»."); continue }
                }
                try {
                    if (runner.runScript(ssh, Scripts.addAuthorizedKeys(lines)) { postLog(log, it) } == 0) ok++
                } catch (e: SshException) {
                    addLog(log, "✗ ${e.message}")
                }
            }
            addLog(log, if (ok == targets.size) "✓ Ключи стоят на всех серверах Главной (${targets.size})." else "Ключи поставлены на $ok из ${targets.size} серверов — для остальных см. сообщения выше.")
        }
    }

    /** При создании или замене сервера: дописывает ключи из списка; ошибка — только предупреждение. */
    private suspend fun installDefaultKeys(ssh: SshConfig, target: SnapshotStateList<String>) {
        if (!defaultKeysOn || defaultKeys.isEmpty()) return
        addLog(target, "")
        addLog(target, "Ключи для всех серверов (${defaultKeys.size}): ${defaultKeys.joinToString { it.title }}")
        try {
            val code = runner.runScript(ssh, Scripts.addAuthorizedKeys(defaultKeys.map { it.line })) { postLog(target, it) }
            if (code != 0) addLog(target, "ПРЕДУПРЕЖДЕНИЕ: ключи не добавлены (код $code). Их можно добавить позже: «Инструменты» → «Ключи для всех серверов».")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog(target, "ПРЕДУПРЕЖДЕНИЕ: ключи не добавлены: ${e.message}. Их можно добавить позже: «Инструменты» → «Ключи для всех серверов».")
        }
    }

    // ---------- Блок «Произвольный VPS» (Инструменты): установка на любой (чужой) сервер по паролю или ключу, без домена ----------

    /**
     * Пока true, общие серверные действия (установка, статус, ссылки, проверка, удаление, защита) работают с сервером
     * блока «Произвольный VPS»: её поля входа, журнал и ссылки, свой список серверов; без DNS и своего домена. Сбрасывается в конце задачи.
     */
    private var vpsMode by mutableStateOf(false)
    /** Идёт действие блока «Произвольный VPS». */
    val vpsBusy: Boolean get() = running && vpsMode
    /** Ошибка в полях блока «Произвольный VPS» (показывается в самом блоке). */
    var vpsError by mutableStateOf<String?>(null)
        private set

    var vpsHost by mutableStateOf(prefs.getString("vpsHost", "") ?: "")
    var vpsPort by mutableStateOf(prefs.getString("vpsPort", "22") ?: "22")
    var vpsUser by mutableStateOf(prefs.getString("vpsUser", "root") ?: "root")
    var vpsUseKey by mutableStateOf(prefs.getBoolean("vpsUseKey", false))
    var vpsPassword by mutableStateOf(secrets.get("vpsPassword"))
    var vpsKey by mutableStateOf(secrets.get("vpsKey"))
    var vpsPassphrase by mutableStateOf(secrets.get("vpsPassphrase"))
    /** Защитить чужой сервер после установки (вход только по ключу). */
    var vpsHarden by mutableStateOf(prefs.getBoolean("vpsHarden", false))
    /** VLESS / XHTTP маскируются под свой сервер (Hysteria2 с сертификатом Let's Encrypt на [vpsDomain]), как на Главной. */
    var vpsSelf by mutableStateOf(prefs.getBoolean("vpsSelf", true))
    /** Домен чужого сервера — пользователь сам направил его на IP сервера; приложение DNS не меняет. */
    var vpsDomain by mutableStateOf(prefs.getString("vpsDomain", "") ?: "")
    var vpsEmail by mutableStateOf(prefs.getString("vpsEmail", "") ?: "")

    /** Запомненные чужие серверы (не Главная и не «Доступ по SSH»); пароли — в зашифрованном хранилище. */
    val vpsServers = mutableStateListOf<ServerProfile>().apply { addAll(ServerProfiles.decode(prefs.getString("vpsServers", null))) }

    private fun vpsSecret(p: ServerProfile, field: String) = "v:${p.id}:$field"

    // данные входа текущей цели: блока «Произвольный VPS» или «Инструментов»
    private val curKey: String get() = if (vpsMode) vpsKey else privateKey
    private val curPhrase: String get() = if (vpsMode) vpsPassphrase else passphrase
    private val curPass: String get() = if (vpsMode) vpsPassword else password
    private val curUseKey: Boolean get() = if (vpsMode) vpsUseKey else useKey
    private val hardenEff: Boolean get() = if (vpsMode) vpsHarden else hardenBeforeInstall
    /** Маскировка VLESS под свой домен: в «Произвольном VPS» — свой переключатель и домен. */
    private val vlessSelfEff: Boolean get() = if (vpsMode) vpsSelf else vlessSelf
    private val domainEff: String get() = (if (vpsMode) vpsDomain else domain).trim().lowercase().trimEnd('.')
    private val emailEff: String get() = (if (vpsMode) vpsEmail else email).trim()

    /** После защиты сервера вход идёт по ключу. */
    private fun markKeyLogin() {
        if (vpsMode) vpsUseKey = true else useKey = true
        persist()
    }

    private fun persistVps() {
        prefs.edit().putString("vpsHost", vpsHost.trim()).putString("vpsPort", vpsPort.trim()).putString("vpsUser", vpsUser.trim())
            .putBoolean("vpsUseKey", vpsUseKey).putBoolean("vpsHarden", vpsHarden)
            .putBoolean("vpsSelf", vpsSelf).putString("vpsDomain", vpsDomain.trim()).putString("vpsEmail", vpsEmail.trim()).apply()
        secrets.put("vpsPassword", vpsPassword); secrets.put("vpsKey", vpsKey); secrets.put("vpsPassphrase", vpsPassphrase)
    }

    /** SSH к серверу блока «Произвольный VPS» (проверка полей — как в «Доступе по SSH»). */
    private fun vpsSshConfig(): SshConfig? {
        val h = vpsHost.trim()
        val p = vpsPort.trim().toIntOrNull()
        val u = vpsUser.trim()
        val problem = when {
            h.isEmpty() || !Regex("^[A-Za-z0-9.:\\-\\[\\]]+$").matches(h) -> "Укажите IP-адрес или домен сервера."
            p == null || p !in 1..65535 -> "SSH-порт должен быть числом 1–65535."
            u.isEmpty() -> "Укажите имя пользователя."
            vpsUseKey && vpsKey.isBlank() -> "Вставьте приватный ключ или выберите файл."
            !vpsUseKey && vpsPassword.isEmpty() -> "Введите пароль."
            vpsUseKey && u != "root" && vpsPassword.isEmpty() -> "Пользователь «$u» не root: введите пароль для sudo (под полем ключа)."
            else -> null
        }
        if (problem != null) { fail(problem); return null }
        return SshConfig(h, p!!, u, vpsPassword, if (vpsUseKey) vpsKey else "", vpsPassphrase)
    }

    /** После входа: сервер — наверх списка чужих серверов, с паролем и ключом. */
    private fun rememberVps(cfg: SshConfig) {
        val p = ServerProfile(cfg.host, cfg.port, cfg.user, cfg.privateKey.isNotBlank())
        val updated = ServerProfiles.upsert(vpsServers, p)
        vpsServers.clear(); vpsServers.addAll(updated)
        secrets.put(vpsSecret(p, "password"), cfg.password)
        secrets.put(vpsSecret(p, "privateKey"), cfg.privateKey)
        secrets.put(vpsSecret(p, "passphrase"), cfg.keyPassphrase)
        prefs.edit().putString("vpsServers", ServerProfiles.encode(vpsServers))
            .putString("vpsDomain:${p.id}", vpsDomain.trim()).putBoolean("vpsSelf:${p.id}", vpsSelf).apply()
    }

    /** Подставляет запомненный чужой сервер в поля блока «Произвольный VPS». */
    fun vpsSelect(p: ServerProfile) {
        if (running) return
        vpsHost = p.host; vpsPort = p.port.toString(); vpsUser = p.user; vpsUseKey = p.useKey
        vpsPassword = secrets.get(vpsSecret(p, "password"))
        vpsKey = secrets.get(vpsSecret(p, "privateKey"))
        vpsPassphrase = secrets.get(vpsSecret(p, "passphrase"))
        vpsDomain = prefs.getString("vpsDomain:${p.id}", "") ?: ""
        vpsSelf = prefs.getBoolean("vpsSelf:${p.id}", vpsSelf)
        errorMessage = null
        vpsLog.clear(); vpsResultLinks.clear()
        persistVps()
    }

    /** Чистые поля для нового сервера. */
    fun vpsNew() {
        if (running) return
        vpsHost = ""; vpsPort = "22"; vpsUser = "root"; vpsUseKey = false
        vpsPassword = ""; vpsKey = ""; vpsPassphrase = ""; vpsDomain = ""
        errorMessage = null
        vpsLog.clear(); vpsResultLinks.clear()
        persistVps()
    }

    /** Убирает чужой сервер из списка вместе с паролем и ключом (сам сервер не затрагивается). */
    fun vpsForget(p: ServerProfile) {
        vpsServers.removeAll { it.id == p.id }
        listOf("password", "privateKey", "passphrase").forEach { secrets.put(vpsSecret(p, it), "") }
        prefs.edit().putString("vpsServers", ServerProfiles.encode(vpsServers)).apply()
    }

    fun vpsLoadKeyFrom(doc: Doc) {
        try {
            vpsKey = doc.read().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            errorMessage = "Не удалось прочитать файл ключа: ${e.message}"
        }
    }

    /** Выполняет серверное действие для сервера блока «Произвольный VPS». */
    private fun asVps(action: () -> Unit) {
        if (running) return
        persistVps()
        vpsError = null
        vpsMode = true
        try {
            action()
        } finally {
            if (!running) vpsMode = false   // задача не запустилась (ошибка в полях) — режим не держим
            errorMessage?.let { vpsError = it; errorMessage = null }
        }
    }

    fun vpsInstall() = asVps { install() }
    fun vpsStatus() = asVps { status() }
    fun vpsFetchLinks() = asVps { fetchLinks() }
    fun vpsDiagnose() = asVps { diagnose() }
    fun vpsLiveCheck() = asVps { liveCheck() }
    fun vpsRestart() = asVps { restartService() }
    fun vpsRemove() = asVps { remove() }
    fun vpsSecure() = asVps { secureNow() }

    // =====================================================================
    //  OneProvider
    // =====================================================================

    private fun opClient(): OneProviderClient? {
        val a = opApiKey.trim()
        val c = opClientKey.trim()
        if (a.isEmpty() || c.isEmpty()) {
            opError = "Введите Api-Key и Client-Key из кабинета OneProvider (раздел API)."
            return null
        }
        return OneProviderClient(a, c)
    }

    /** Локация недавно отказала «нет мест» (пометка на сутки). */
    fun opIsBad(id: String): Boolean = opBad[id]?.let { System.currentTimeMillis() - it < BAD_LOCATION_MS } ?: false

    /** Локации, где есть тарифы, доступные к заказу, с IPv4 и подходящие под фильтр; помеченные — в конце. */
    val opLocationsOrderable: List<OpLocation>
        get() = OneProviderClient.orderableLocations(opLocations, opSizes, opFilter).sortedBy { opIsBad(it.id) }

    // ---------- Пинг до локаций OneProvider ----------
    /** Пинг по id локации: замер (до тестового адреса или вашего VPS в локации) или оценка по расстоянию. */
    val opPing = mutableStateMapOf<String, PingResult>()
    var opPinging by mutableStateOf(false)
        private set
    /** Почему у части локаций нет даже оценки (не удалось узнать ваше местоположение). */
    var opPingNote by mutableStateOf<String?>(null)
        private set
    private var myGeo: Pair<Double, Double>? = null

    /** Адрес для замера пинга локации: тестовый адрес от OneProvider или IP вашего VPS в этой локации. */
    private fun pingTarget(loc: OpLocation): String? = loc.testHost.ifEmpty { null }
        ?: opVms.firstOrNull { vm -> IPV4.matches(vm.ip) && (vm.location == loc.id || (loc.city.isNotEmpty() && vm.location.contains(loc.city, ignoreCase = true))) }?.ip

    /** Меряет пинг до подходящих локаций (в фоне, не блокируя интерфейс). */
    fun opMeasurePing() {
        if (opPinging) return
        opPinging = true
        scope.launch {
            try {
                if (!opLoaded && opApiKey.isNotBlank() && opClientKey.isNotBlank()) {
                    runCatching { OneProviderClient(opApiKey.trim(), opClientKey.trim()).listVms() }.getOrNull()?.let { setOpVms(it) }
                }
                if (myGeo == null) myGeo = Ping.myLocation()
                val geo = myGeo
                val locs = opLocationsOrderable
                opPingNote = if (geo == null) "Не удалось определить ваше местоположение — оценки пинга нет, только замеры." else null
                val gate = kotlinx.coroutines.sync.Semaphore(8)
                kotlinx.coroutines.coroutineScope {
                    locs.forEach { loc ->
                        launch {
                            gate.acquire()
                            try {
                                val real = pingTarget(loc)?.let { Ping.tcpRttMs(it) }
                                val est = if (real == null && geo != null) Ping.cityCoord(loc.city)?.let { Ping.estimateMs(geo, it) } else null
                                when {
                                    real != null -> opPing[loc.id] = PingResult(real, estimated = false)
                                    est != null -> opPing[loc.id] = PingResult(est, estimated = true)
                                }
                            } finally {
                                gate.release()
                            }
                        }
                    }
                }
            } finally {
                opPinging = false
            }
        }
    }

    /** Лучший пинг в регионе (для списка регионов). */
    fun opRegionPing(region: String): PingResult? =
        opLocationsOrderable.filter { it.group == region }.mapNotNull { opPing[it.id] }.minByOrNull { it.ms }

    /** Регионы с подходящими локациями: название → сколько в нём локаций без пометки «недоступна». */
    val opRegions: List<Pair<String, Int>>
        get() = opLocationsOrderable.groupBy { it.group }
            .map { (g, list) -> g to list.count { !opIsBad(it.id) } }
            .sortedWith(compareBy({ it.second == 0 }, { it.first }))

    /** Подходящие локации выбранного региона (помеченные — в конце). */
    val opLocationsInRegion: List<OpLocation>
        get() = opLocationsOrderable.filter { it.group == opRegion }
            .sortedWith(compareBy({ opIsBad(it.id) }, { opPing[it.id]?.ms ?: Int.MAX_VALUE }, { it.place }))

    fun opSelectRegion(region: String) {
        opRegion = region
        if (opLocationsInRegion.none { it.id == opLocationId }) {
            opLocationsInRegion.firstOrNull()?.let { opSelectLocation(it.id) }
        }
    }

    /** Время отказа локации для подписи «недоступна (проверено HH:MM)». */
    fun opBadSince(id: String): String? = opBad[id]?.takeIf { opIsBad(id) }?.let {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
    }

    /**
     * Создание VPS; если провайдер ответил «в локации нет мест», локация помечается на сутки,
     * выбор переключается на соседнюю в том же регионе, а ошибка объясняется понятным текстом.
     */
    private suspend fun createVmChecked(
        client: OneProviderClient, loc: OpLocation, size: OpSize, tpl: OpTemplate, host: String, keys: List<String>,
    ): OpCreated = try {
        client.createVm(loc.id, size.id, tpl.id, host, keys)
    } catch (e: OneProviderException) {
        if (!OneProviderClient.isUnavailableError(e.message.orEmpty())) throw e
        opBad[loc.id] = System.currentTimeMillis()
        saveBadLocations()
        val next = opLocationsInRegion.firstOrNull { !opIsBad(it.id) && it.id != loc.id }
        if (next != null && opLocationId == loc.id) opSelectLocation(next.id)
        throw OneProviderException(
            "Локация ${loc.place} сейчас недоступна у OneProvider и помечена на сутки (ответ: ${e.message}). " +
                (if (next != null) "Выбрана другая локация региона ${loc.group}: ${next.place} — запустите ещё раз."
                else "В регионе ${loc.group} других доступных локаций нет — выберите другой регион."),
            e.httpCode,
        )
    }

    private fun saveBadLocations() {
        val o = org.json.JSONObject()
        opBad.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("opBadLocs", o.toString()).apply()
    }

    /** Тарифы выбранной локации: доступны к заказу, IPv4 включён, подходят под фильтр. */
    val opSizesForLocation: List<OpSize>
        get() = OneProviderClient.orderableSizes(opSizes, opLocationsOrderable.firstOrNull { it.id == opLocationId }, opFilter)

    /** Сколько тарифов выбранной локации скрыл фильтр. */
    val opSizesHiddenByFilter: Int
        get() = opLocations.firstOrNull { it.id == opLocationId }
            ?.let { loc -> OneProviderClient.orderableSizes(opSizes, loc).size - opSizesForLocation.size } ?: 0

    /** Меняет фильтр тарифов; выбор локации и тарифа переходит на подходящие (по возможности в том же регионе). */
    fun opSetFilter(f: OpFilter) {
        opFilter = f
        prefs.edit().putInt("opMaxPrice", f.maxPrice).putInt("opMinTraffic", f.minTrafficGb).apply()
        opFixSelection()
    }

    /** Выбранные локация, регион и тариф — из подходящих; непомеченные локации — в первую очередь. */
    private fun opFixSelection() {
        if (opLocationsOrderable.none { it.id == opLocationId }) {
            val inRegion = opLocationsInRegion
            opLocationId = (inRegion.firstOrNull { !opIsBad(it.id) } ?: opLocationsOrderable.firstOrNull { !opIsBad(it.id) }
                ?: opLocationsOrderable.firstOrNull())?.id.orEmpty()
        }
        opRegion = opLocationsOrderable.firstOrNull { it.id == opLocationId }?.group ?: opRegions.firstOrNull()?.first.orEmpty()
        if (opSizesForLocation.none { it.id == opSizeId }) opSizeId = opSizesForLocation.firstOrNull()?.id.orEmpty()
    }

    val opSelectedSize: OpSize? get() = opSizesForLocation.firstOrNull { it.id == opSizeId }
    val opSelectedLocation: OpLocation? get() = opLocationsOrderable.firstOrNull { it.id == opLocationId }
    val opSelectedTemplate: OpTemplate? get() = opTemplates.firstOrNull { it.id == opTemplateId }

    fun opLoadVms() {
        val client = opClient() ?: return
        persist()
        launchTask(opLog) {
            addLog(opLog, "Загружаю список VPS…")
            val list = client.listVms()
            setOpVms(list)
            addLog(opLog, "VPS в аккаунте: ${list.size}")
            loadBalance()
        }
    }

    fun opLoadCatalog() {
        val client = opClient() ?: return
        persist()
        launchTask(opLog) {
            addLog(opLog, "Загружаю локации, тарифы, ОС и SSH-ключи…")
            val (locs, format) = client.locationsWithFormat()
            val sizes = client.sizes()
            val tpls = client.templates()
            val keys = client.sshKeys()
            opLocations.clear(); opLocations.addAll(locs)
            opSizes.clear(); opSizes.addAll(sizes)
            opTemplates.clear(); opTemplates.addAll(tpls)
            opKeys.clear(); opKeys.addAll(keys)
            opKeySel.retainAll { k -> keys.any { it.uuid == k } }
            opKeysLoaded = true
            opFixSelection()
            if (tpls.none { it.id == opTemplateId }) opTemplateId = OneProviderClient.preferredTemplate(tpls)?.id.orEmpty()
            opCatalogLoaded = true
            loadBalance()
            opPing.clear()
            opMeasurePing()
            val okLocs = opLocationsOrderable
            val okSizes = sizes.count { sz -> okLocs.any { OneProviderClient.isOrderable(sz, it, opFilter) } }
            addLog(opLog, "Подходящих локаций: ${okLocs.size} из ${locs.size} в ${opRegions.size} регионах, тарифов: $okSizes из ${sizes.size} " +
                "(доступны к заказу, IPv4 включён; фильтр: ${opFilter.title}). ОС: ${tpls.size}, SSH-ключей: ${keys.size}")
            val closed = locs.count { !it.available }
            val noSizes = locs.count { it.available && it.sizeIds.isEmpty() }
            val noIpv4 = locs.count { it.available && it.sizeIds.isNotEmpty() && !it.hasIpv4 }
            addLog(opLog, "Отсеяно локаций: закрыты провайдером — $closed, без тарифов — $noSizes, без IPv4 — $noIpv4, " +
                "без подходящих тарифов — ${locs.size - okLocs.size - closed - noSizes - noIpv4}. " +
                "Помечены «недоступна» за сутки: ${okLocs.count { opIsBad(it.id) }}.")
            addLog(opLog, "Формат ответа OneProvider — $format")
        }
    }

    fun opSelectLocation(id: String) {
        opLocationId = id
        opLocationsOrderable.firstOrNull { it.id == id }?.let { opRegion = it.group }
        if (opSizesForLocation.none { it.id == opSizeId }) opSizeId = opSizesForLocation.firstOrNull()?.id.orEmpty()
    }

    fun opToggleVm(id: String) {
        if (!opSelected.remove(id)) opSelected.add(id)
    }

    fun opToggleKey(uuid: String) {
        if (!opKeySel.remove(uuid)) opKeySel.add(uuid)
    }

    // ---------------- SSH-ключи кабинета ----------------

    fun opLoadKeys() {
        val client = opClient() ?: return
        persist()
        launchTask(opLog) {
            addLog(opLog, "Загружаю SSH-ключи кабинета…")
            val keys = client.sshKeys()
            opKeys.clear(); opKeys.addAll(keys)
            opKeySel.retainAll { k -> keys.any { it.uuid == k } }
            opKeysLoaded = true
            addLog(opLog, "SSH-ключей в кабинете: ${keys.size}")
        }
    }

    /** Подставляет публичную часть приватного ключа из «Доступа к серверам». */
    fun opKeyFromServerTab() {
        opError = null
        if (privateKey.isBlank()) {
            opError = "В «Доступе к серверам» (Инструменты) не задан приватный ключ."
            return
        }
        val pub = SshKeys.publicOf(privateKey, passphrase)
        if (pub == null) {
            opError = "Приватный ключ в «Доступе к серверам» (Инструменты) не читается или неверен пароль от ключа."
            return
        }
        opNewKeyValue = pub
        if (opNewKeyName.isBlank()) opNewKeyName = "hy2setup-phone"
    }

    fun opAddKey() {
        opError = null
        val name = opNewKeyName.trim()
        val value = OneProviderClient.normalizePublicKey(opNewKeyValue)
        when {
            name.isEmpty() -> { opError = "Введите название ключа."; return }
            value == null -> {
                opError = "Нужен публичный ключ OpenSSH в одну строку: «ssh-ed25519 AAAA… комментарий» " +
                    "(или ssh-rsa / ecdsa-sha2-…). Приватный ключ сюда вставлять нельзя."
                return
            }
            opKeys.any { SshKeys.normalizePublic(it.value) == SshKeys.normalizePublic(value) } -> {
                opError = "Такой ключ уже есть в кабинете."
                return
            }
        }
        val client = opClient() ?: return
        persist()
        launchTask(opLog) {
            addLog(opLog, "Добавляю SSH-ключ «$name» в кабинет…")
            val added = client.addSshKey(name, value!!)
            val keys = client.sshKeys()
            opKeys.clear(); opKeys.addAll(keys)
            opKeysLoaded = true
            val uuid = added?.uuid?.takeIf { it.isNotEmpty() }
                ?: keys.firstOrNull { SshKeys.normalizePublic(it.value) == SshKeys.normalizePublic(value) }?.uuid
            if (uuid != null && uuid !in opKeySel) opKeySel.add(uuid)
            opNewKeyName = ""
            opNewKeyValue = ""
            addLog(opLog, "Ключ добавлен и отмечен для новых VPS. Ключей в кабинете: ${keys.size}")
        }
    }

    fun opDeleteKey() {
        val key = opKeyToDelete ?: return
        opKeyToDelete = null
        val client = opClient() ?: return
        launchTask(opLog) {
            addLog(opLog, "Удаляю SSH-ключ «${key.name}» из кабинета…")
            client.deleteSshKey(key.uuid)
            opKeySel.remove(key.uuid)
            val keys = client.sshKeys()
            opKeys.clear(); opKeys.addAll(keys)
            addLog(opLog, "Ключ удалён из кабинета (на уже созданных VPS он остаётся). Ключей: ${keys.size}")
        }
    }

    /** Проверяет форму и открывает диалог подтверждения создания. */
    fun opRequestCreate() {
        opError = null
        if (opClient() == null) return
        val host = opHostname.trim()
        when {
            opSelectedLocation == null -> opError = "Выберите локацию."
            opSelectedSize == null -> opError = "Выберите тариф (размер памяти и трафик)."
            opSelectedTemplate == null -> opError = "Выберите операционную систему."
            !HOSTNAME.matches(host) -> opError = "Имя хоста: латиница, цифры, точка и дефис, например vpn1 или vpn1.example.com."
            else -> opConfirmCreate = true
        }
    }

    fun opCreate() {
        opConfirmCreate = false
        val client = opClient() ?: return
        val loc = opSelectedLocation ?: return
        val size = opSelectedSize ?: return
        val tpl = opSelectedTemplate ?: return
        val host = opHostname.trim()
        val keys = opKeySel.toList()
        persist()
        launchTask(opLog) {
            opCreated = null
            addLog(opLog, "Создаю VPS «$host»: ${loc.title}, ${size.summary}, ${tpl.name}…")
            val created = createVmChecked(client, loc, size, tpl, host, keys)
            opCreated = created
            opCreatedKeyed = keys.isNotEmpty()
            addLog(opLog, "✓ VPS создан: ID ${created.id}, IP ${created.ip.ifEmpty { "(появится позже)" }}")
            addLog(opLog, "Загрузка ОС обычно занимает 1–3 минуты, после этого можно ставить протокол.")
            opHostname = ""
            val list = client.listVms()
            opVms.clear(); opVms.addAll(list); opLoaded = true
        }
    }

    fun opDeleteSelected() {
        opConfirmDelete = false
        val client = opClient() ?: return
        val targets = opVms.filter { it.id in opSelected }
        if (targets.isEmpty()) return
        launchTask(opLog) {
            opOverage.clear()
            for (vm in targets) {
                try {
                    val r = client.destroyVm(vm.id, confirmClose = false)
                    if (r.destroyed) {
                        dropCombineServer(vm.id)
                        addLog(opLog, "✓ Удалён: ${vm.hostname} (${vm.ip})")
                    } else {
                        opOverage.add(vm to r)
                        addLog(opLog, "! ${vm.hostname}: перерасход трафика ${r.overageGb} ГБ, доплата ${r.overageCost} — нужно подтверждение.")
                    }
                } catch (e: OneProviderException) {
                    addLog(opLog, "✗ ${vm.hostname}: ${e.message}")
                }
            }
            val list = client.listVms()
            opVms.clear(); opVms.addAll(list)
            opSelected.retainAll { id -> list.any { it.id == id } }
        }
    }

    /** Удаление с согласием на оплату перерасхода трафика. */
    fun opConfirmOverage() {
        val client = opClient() ?: return
        val targets = opOverage.map { it.first }
        opOverage.clear()
        launchTask(opLog, clear = false) {
            for (vm in targets) {
                try {
                    val r = client.destroyVm(vm.id, confirmClose = true)
                    if (r.destroyed) dropCombineServer(vm.id)
                    addLog(opLog, if (r.destroyed) "✓ Удалён (с оплатой перерасхода): ${vm.hostname}" else "✗ ${vm.hostname}: ${r.message}")
                } catch (e: OneProviderException) {
                    addLog(opLog, "✗ ${vm.hostname}: ${e.message}")
                }
            }
            val list = client.listVms()
            opVms.clear(); opVms.addAll(list)
            opSelected.retainAll { id -> list.any { it.id == id } }
        }
    }

    fun opCancelOverage() {
        opOverage.clear()
        opLog.add("Удаление отменено — VPS с перерасходом трафика оставлены.")
    }

    /** Подставляет VPS в «Инструменты → Доступ к серверам» и переключается туда. */
    fun useOnServerTab(ip: String, rootPassword: String = "", keyed: Boolean = false) {
        if (ip.isBlank()) return
        stableProfiles.firstOrNull { it.vps?.ip == ip }?.let { editAccess(it.fqdn); return }
        accessTarget = ""
        prefs.edit().putString("accessTarget", "").apply()
        host = ip
        sshPort = "22"
        user = "root"
        if (rootPassword.isNotEmpty()) password = rootPassword
        // если при создании были выбраны ключи из кабинета и приватный ключ уже задан — входим по ключу
        useKey = privateKey.isNotBlank() && (keyed || rootPassword.isEmpty())
        hardenBeforeInstall = true
        errorMessage = null
        persist()
        accessSaved = draft()
        selectedTab = TAB_TOOLS
    }

    // =====================================================================
    //  Новый сервер: проверка протоколов и ключа безопасности
    // =====================================================================

    /** Проверка протоколов нового сервера и их портов (TCP и UDP порты не должны пересекаться). */
    private fun plusProtocolsProblem(): String? {
        if (!plusHy2 && !plusVless && !plusAwg && !plusTg) return "Включите хотя бы один протокол."
        val hy = hyPort.trim().toIntOrNull()
        val vl = vlessPort.trim().toIntOrNull()
        val aw = awgPort.trim().toIntOrNull()
        val tg = tgPort.trim().toIntOrNull()
        return when {
            plusHy2 && (hy == null || hy !in 1..65535) -> "Порт Hysteria2 должен быть числом 1–65535."
            (plusHy2 || plusVless) && !Regex("^https://[A-Za-z0-9._~:/?@&=%+-]+$").matches(masquerade.trim()) ->
                "Сайт-маскировка должен начинаться с https://."
            !SAFE_NAME.matches(email.trim()) -> "E-mail может содержать только латиницу, цифры и символы . _ @ + -"
            plusVless && (vl == null || vl !in 1..65535) -> "Порт VLESS должен быть числом 1–65535."
            plusVless && !plusHy2 && !ZONE.matches(realitySni.trim().lowercase()) ->
                "Без Hysteria2 VLESS маскируется под чужой сайт — укажите его, например www.microsoft.com."
            plusAwg && (aw == null || aw !in 1..65535) -> "Порт AmneziaWG должен быть числом 1–65535."
            plusAwg && plusHy2 && aw == hy -> "AmneziaWG и Hysteria2 не могут слушать один UDP-порт $aw — смените порт AmneziaWG."
            plusTg && (tg == null || tg !in 1..65535) -> "Порт Telegram-прокси должен быть числом 1–65535."
            plusTg && !ZONE.matches(tgDomain.trim().lowercase()) -> "Домен маскировки Telegram — имя хоста, например www.microsoft.com."
            plusTg && plusVless && tg == vl -> "VLESS и Telegram-прокси не могут слушать один TCP-порт $tg — смените порт Telegram."
            plusTg && tg == 80 && (plusHy2 || plusVless) -> "Порт 80/TCP нужен для сертификата Let's Encrypt — выберите для Telegram другой."
            plusTg && tg == 8443 && plusHy2 && plusVless -> "Порт 8443 занят сайтом Hysteria2 для маскировки VLESS — выберите для Telegram другой (например, 993)."
            else -> null
        }
    }

    /** Проверка ключа кабинета и приватного ключа «Доступа к серверам» для режима безопасности на новом VPS. */
    private fun secureKeyProblem(): String? {
        val selectedPubs = opKeys.filter { it.uuid in opKeySel }.map { it.value }
        return when {
            opKeySel.isEmpty() ->
                "Безопасность: отметьте SSH-ключ из кабинета — по нему приложение войдёт на новый VPS."
            privateKey.isBlank() ->
                "Безопасность: вставьте приватную часть этого ключа в «Доступе к серверам» (Инструменты) (поле «Приватный ключ»)."
            SshKeys.publicOf(privateKey, passphrase) == null ->
                "Безопасность: приватный ключ в «Доступе к серверам» (Инструменты) не читается или неверен пароль от ключа."
            !SshKeys.matchesAny(privateKey, passphrase, selectedPubs) ->
                "Безопасность: приватный ключ в «Доступе к серверам» (Инструменты) не соответствует ни одному из отмеченных ключей кабинета."
            else -> null
        }
    }

    /** Результат попытки TCP-подключения: отличаем «закрыт» (сервер ответил отказом) от «пакеты теряются». */
    enum class Probe { OPEN, REFUSED, TIMEOUT, ERROR }

    private suspend fun tcpProbe(ip: String, port: Int, timeoutMs: Int = 5_000): Pair<Probe, String> = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
            Probe.OPEN to ""
        } catch (e: java.net.ConnectException) {
            Probe.REFUSED to e.message.orEmpty()
        } catch (e: java.net.SocketTimeoutException) {
            Probe.TIMEOUT to ""
        } catch (e: Exception) {
            Probe.ERROR to (e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Ждёт открытия SSH (порт 22) и объясняет в журнале, что происходит: сервер ещё не запустил SSH (отказ),
     * или пакеты с телефона до сервера не доходят (тайм-аут). Во втором случае проверяет, пропускает ли сеть телефона
     * SSH вообще (github.com:22) и что говорит OneProvider о VPS — чтобы сказать, где искать причину.
     */
    private suspend fun waitForSsh(ip: String, target: SnapshotStateList<String>, limitMs: Long, op: OneProviderClient?, vmId: String?, port: Int = 22) {
        val start = System.currentTimeMillis()
        var timeouts = 0
        var diagnosed = false
        var phoneBlocksSsh: Boolean? = null
        while (true) {
            val (r, msg) = tcpProbe(ip, port)
            val sec = (System.currentTimeMillis() - start) / 1000
            when (r) {
                Probe.OPEN -> return
                Probe.REFUSED -> addLog(target, "Сервер отвечает, но SSH ещё не запущен — жду… ($sec с)")
                Probe.TIMEOUT -> { timeouts++; addLog(target, "Порт $port не отвечает (пакеты теряются) — жду… ($sec с)") }
                Probe.ERROR -> addLog(target, "Порт $port: $msg — жду… ($sec с)")
            }
            if (!diagnosed && timeouts >= 6) {
                diagnosed = true
                addLog(target, "")
                addLog(target, "Проверяю, почему порт 22 не отвечает…")
                val gh = tcpProbe("github.com", 22, 8_000).first
                phoneBlocksSsh = gh != Probe.OPEN
                if (phoneBlocksSsh == true) {
                    addLog(target, "✗ Сеть ${Device.GEN} не пропускает SSH: даже github.com:22 недоступен. Так бывает на мобильном интернете " +
                        "или когда включён VPN (Happ) с правилами, режущими порт 22. Переключитесь на Wi-Fi или выключите/включите VPN — ожидание продолжается.")
                } else {
                    addLog(target, "✓ SSH из сети ${Device.GEN} работает (github.com:22 доступен), а до $ip пакеты не доходят.")
                    if (platform.vpnActive()) {
                        addLog(target, "VPN на ${Device.PREP} включён — возможно, его правила не пускают порт 22 к этому адресу. Попробуйте выключить VPN или сменить его сервер.")
                    } else {
                        addLog(target, "Похоже, провайдер режет SSH к этому серверу. Включите VPN (Happ) для vpngramm — через него работает так же, как на компьютере. Ожидание продолжается.")
                    }
                }
                if (op != null && !vmId.isNullOrEmpty()) {
                    runCatching { op.listVms().firstOrNull { it.id == vmId } }.getOrNull()?.let {
                        addLog(target, "OneProvider: состояние VPS — «${it.status.ifEmpty { "неизвестно" }}».")
                    }
                }
                addLog(target, "Проверьте с компьютера: ssh root@$ip (или «Test-NetConnection $ip -Port 22» в PowerShell). Ping не показывает, открыт ли SSH.")
                addLog(target, "")
            }
            if (System.currentTimeMillis() - start > limitMs) {
                throw IllegalStateException(
                    when {
                        phoneBlocksSsh == true -> "Сеть ${Device.GEN} не пропускает SSH (порт 22) — VPS $ip, скорее всего, работает. Переключитесь на Wi-Fi или смените режим VPN и запустите ещё раз; созданный VPS останется во вкладке «VPS/DNS» — там его можно удалить."
                        timeouts > 0 -> "VPS $ip не открыл SSH (порт 22) за ${limitMs / 60_000} мин: пакеты с ${Device.GEN} не доходят. Скорее всего, провайдер режет SSH к этому адресу — " +
                            "включите VPN (Happ) для vpngramm и запустите снова. Если и с компьютера через VPN не пускает — откройте консоль VNC в панели OneProvider."
                        else -> "VPS $ip не открыл SSH (порт 22) за ${limitMs / 60_000} мин."
                    },
                )
            }
            delay(10_000)
        }
    }

    private suspend fun tcpOpen(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(ip, port), 5_000) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // =====================================================================
    //  Постоянные ссылки: VPS → DNS → протоколы с ключами профиля → безопасность
    // =====================================================================

    /** Сохраняет профиль в список по его домену (выбор сервера не меняется). */
    private fun storeStable(p: StableProfile) {
        val updated = ProfileImport.upsert(stableProfiles, p)
        stableProfiles.clear(); stableProfiles.addAll(updated)
        secrets.put(STABLE_PROFILES, ProfileImport.encodeList(stableProfiles))
    }

    private fun savePlusProfile(p: StableProfile) = storeStable(p)

    private fun selectStableQuiet(fqdn: String) {
        stableSelected = fqdn
        prefs.edit().putString("stableSelected", fqdn).apply()
    }

    /** Выбор сервера на Главной: журнал и ссылки предыдущего сервера убираются. */
    fun selectStable(fqdn: String) {
        if (running || fqdn == plusProfile?.fqdn) return
        selectStableQuiet(fqdn)
        plusNew = false
        plusTargetId = ""
        plusLog.clear(); plusLinks.clear(); plusSteps.clear()
        plusError = null
        plusAccessProblem = false
        plusRunTarget = null
        homeProtoInstalled = null
    }

    /** Убирает сервер с Главной (сам VPS и DNS не трогаются). */
    private fun removeStable(fqdn: String) {
        val wasSelected = plusProfile?.fqdn == fqdn
        stableProfiles.removeAll { it.fqdn == fqdn }
        secrets.put(STABLE_PROFILES, ProfileImport.encodeList(stableProfiles))
        if (plusRollback?.fqdn == fqdn) saveRollback(null)
        if (wasSelected) {
            selectStableQuiet(stableProfiles.firstOrNull()?.fqdn.orEmpty())
            plusLinks.clear(); plusSteps.clear(); plusLog.clear()
            plusError = null; plusTargetId = ""; plusRunTarget = null
        }
    }

    // ---------- Удаление сервера из списка Главной ----------
    /** Сервер, для которого открыт диалог «Удалить». */
    var serverToDelete by mutableStateOf<StableProfile?>(null)
    /** Второе подтверждение: удалить VPS и DNS. */
    var serverDeleteFull by mutableStateOf<StableProfile?>(null)
    /** Перерасход трафика при удалении VPS — нужно согласие на оплату. */
    var serverDeleteOverage by mutableStateOf<Pair<StableProfile, OpDestroyResult>?>(null)

    /** Убирает сервер только из списка: сервер, VPS и DNS продолжают работать. */
    fun removeFromList(fqdn: String) {
        serverToDelete = null
        if (running) return
        removeStable(fqdn)
        plusLog.add("Сервер $fqdn убран с Главной. VPS и DNS не изменялись; перенести его с прежними ссылками можно только из сохранённой копии.")
    }

    /** IP сервера использует другой сервер Главной — такой VPS удалять нельзя. */
    fun vpsSharedWithOthers(p: StableProfile): Boolean {
        val v = p.vps ?: return false
        return stableProfiles.any { o -> o.fqdn != p.fqdn && o.vps != null && (o.vps.ip == v.ip || (v.id.isNotEmpty() && o.vps.id == v.id)) }
    }

    /** Что мешает удалить VPS и DNS (null — можно). */
    fun deleteFullProblem(): String? = when {
        opApiKey.isBlank() || opClientKey.isBlank() -> "Нужны ключи OneProvider (карточка «Аккаунты»)."
        ncUser.isBlank() || ncToken.isBlank() -> "Нужны логин и токен name.com (карточка «Аккаунты»)."
        else -> null
    }

    /**
     * Удаляет VPS сервера в OneProvider и его A/AAAA-записи в name.com; из списка сервер убирается,
     * только если оба шага прошли — иначе его можно удалить повторно.
     */
    fun deleteServerFully(p: StableProfile, confirmOverage: Boolean = false) {
        serverDeleteFull = null
        serverDeleteOverage = null
        deleteFullProblem()?.let { plusError = it; return }
        val op = OneProviderClient(opApiKey.trim(), opClientKey.trim())
        val nc = NameComClient(ncUser.trim(), ncToken.trim())
        launchTask(plusLog) {
            addLog(plusLog, "Удаляю сервер ${p.fqdn}…")
            var vpsOk = true
            val v = p.vps
            if (v == null) {
                addLog(plusLog, "У сервера нет VPS — пропускаю.")
            } else if (vpsSharedWithOthers(p)) {
                addLog(plusLog, "VPS ${v.ip} использует другой сервер Главной — его не удаляю.")
            } else {
                val vms = op.listVms()
                val vm = vms.firstOrNull { v.id.isNotEmpty() && it.id == v.id } ?: vms.firstOrNull { it.ip == v.ip }
                if (vm == null) {
                    addLog(plusLog, "VPS ${v.ip} не найден в аккаунте OneProvider — видимо, уже удалён.")
                } else {
                    val r = op.destroyVm(vm.id, confirmClose = confirmOverage)
                    when {
                        r.destroyed -> {
                            dropCombineServer(vm.id)
                            addLog(plusLog, "✓ VPS ${vm.hostname.ifEmpty { vm.id }} (${vm.ip}) удалён в OneProvider.")
                        }
                        !confirmOverage && r.overageCost.isNotEmpty() -> {
                            addLog(plusLog, "! Перерасход трафика ${r.overageGb} ГБ, доплата ${r.overageCost} — нужно подтверждение. Ничего не удалено.")
                            serverDeleteOverage = p to r
                            return@launchTask
                        }
                        else -> {
                            vpsOk = false
                            addLog(plusLog, "✗ VPS не удалён: ${r.message}")
                        }
                    }
                }
                opVms.clear(); opVms.addAll(runCatching { op.listVms() }.getOrDefault(emptyList()))
            }

            var dnsOk = true
            try {
                val records = ProfileImport.recordsToDelete(nc.listRecords(p.zone), p.fqdn)
                if (records.isEmpty()) addLog(plusLog, "В name.com нет A-записи ${p.fqdn} — пропускаю.")
                for (r in records) {
                    nc.deleteRecord(p.zone, r.id)
                    addLog(plusLog, "✓ Запись ${r.displayName} ${r.type} ${r.answer} удалена в name.com.")
                }
            } catch (e: NameComException) {
                dnsOk = false
                addLog(plusLog, "✗ DNS: ${e.message}")
            }

            if (vpsOk && dnsOk) {
                removeStable(p.fqdn)
                addLog(plusLog, "✓ Сервер ${p.fqdn} удалён: VPS, DNS и строка в списке.")
            } else {
                addLog(plusLog, "Сервер ${p.fqdn} оставлен в списке — исправьте причину и удалите ещё раз.")
            }
        }
    }

    /** Профиль, с которым работает шторка «Заменить/Создать сервер» (null — создаётся новый). */
    private val plusCurrent: StableProfile? get() = if (plusNew) null else plusProfile

    /** Показывает ссылки профиля без обращения к серверу. */
    fun plusShowLinks() {
        val p = plusProfile ?: return
        plusLinks.clear()
        plusLinks.addAll(p.links())
    }

    /** Выбранный свободный сервер для переезда (null — покупаем новый VPS). */
    val plusTarget: CombineServer? get() = plusTargetId.takeIf { it.isNotEmpty() }?.let { id -> combineServers.firstOrNull { it.key == id } }

    /** Свободные готовые серверы: ни один профиль Главной на них сейчас не работает (иначе сломались бы его ссылки). */
    val plusTargets: List<CombineServer>
        get() = combineServers.filter { s ->
            stableProfiles.none { p -> val v = p.vps; v != null && ((v.id.isNotEmpty() && v.id == s.vpsId) || v.ip == s.ip) }
        }

    private fun saveCombineServers() {
        prefs.edit().putString("combineServers", CombineServers.encode(combineServers)).apply()
    }

    private fun rememberCombineServer(srv: CombineServer) {
        val updated = CombineServers.upsert(combineServers, srv)
        combineServers.clear(); combineServers.addAll(updated)
        saveCombineServers()
    }

    /** Убирает сервер из списка (сам VPS не трогается). */
    fun forgetCombineServer(srv: CombineServer) {
        combineServers.removeAll { it.key == srv.key }
        if (plusTargetId == srv.key) plusTargetId = ""
        saveCombineServers()
    }

    /** Убирает свободный сервер по ключу записи (ID VPS или «ip:адрес»). */
    private fun dropCombineServer(key: String) {
        if (key.isEmpty()) return
        if (combineServers.removeAll { it.key == key }) saveCombineServers()
        if (plusTargetId == key) plusTargetId = ""
    }

    /** Сохранённая запись сервера («Доступ по SSH»): порт и пользователь; если её нет — root на порту 22. */
    private fun savedServer(ip: String): ServerProfile = profiles.firstOrNull { it.host == ip } ?: ServerProfile(ip, 22, "root", false)

    /** Сохранённые для сервера пароль и ключ (из списка серверов «Доступа к серверам»). */
    private fun savedCredentials(ip: String): Pair<String, String> {
        val p = savedServer(ip)
        val key = secrets.get(secretKey(p, "privateKey")).ifBlank { privateKey }
        return secrets.get(secretKey(p, "password")) to key
    }

    /**
     * Есть ли VPS свободного сервера в аккаунте OneProvider (null — список VPS ещё не загружен
     * или сервер добавлен вручную и к OneProvider не относится).
     */
    fun combineServerAlive(srv: CombineServer): Boolean? =
        if (srv.manual || !opLoaded) null else opVms.any { it.id == srv.vpsId || it.ip == srv.ip }

    fun plusRequest() {
        plusError = null
        if (!plusNew) plusTarget?.let { return plusRequestExisting(it) }
        val problem = when {
            opApiKey.isBlank() || opClientKey.isBlank() -> "Введите Api-Key и Client-Key OneProvider."
            !opCatalogLoaded -> "Загрузите локации, тарифы и ключи OneProvider."
            opSelectedLocation == null -> "Выберите локацию."
            opSelectedSize == null -> "Выберите тариф."
            opSelectedTemplate == null -> "Выберите операционную систему."
            plusSecure -> secureKeyProblem()
            else -> null
        }
        if (problem != null) { plusError = problem; return }
        val plan = plusPlan() ?: return
        if (plan.subdomain.isEmpty()) {
            plusError = "Укажите поддомен (например, vpn) — корень домена приложение не перезаписывает."
            return
        }
        if (plusCurrent == null) {
            if (stableProfiles.any { it.fqdn == plan.fqdn }) {
                plusError = "Сервер ${plan.fqdn} уже есть на Главной — выберите его в списке или укажите другой поддомен."; return
            }
            // новый сервер: протоколы и порты выбраны в шторке (у существующего их задаёт профиль)
            plusProtocolsProblem()?.let { plusError = it; return }
        }
        plusConfirm = true
    }

    /** Переезд на свободный сервер: VPS не покупается, нужен только доступ по SSH. */
    private fun plusRequestExisting(t: CombineServer) {
        val profile = plusProfile ?: run { plusError = "Сначала создайте сервер на Главной — свободный сервер подходит только для переезда."; return }
        val (pass, key) = savedCredentials(t.ip)
        val problem = when {
            (t.vpsId.isNotEmpty() && t.vpsId == profile.vps?.id) || t.ip == profile.vps?.ip -> "Профиль уже работает на этом сервере."
            combineServerAlive(t) == false -> "VPS ${t.title} не найден в аккаунте OneProvider — возможно, он удалён. Уберите его из списка."
            key.isBlank() && pass.isEmpty() -> "Нет доступа к ${t.ip}: нужен пароль root или приватный ключ в «Доступе к серверам» (Инструменты)."
            t.secured && key.isBlank() -> "Сервер ${t.title} пускает только по ключу — вставьте приватный ключ в «Доступе к серверам» (Инструменты)."
            plusSecure && !t.secured && key.isBlank() -> "Безопасность: вставьте приватный ключ в «Доступе к серверам» (Инструменты) или выключите «Безопасность»."
            plusSecure && !t.secured && SshKeys.publicOf(key, passphrase) == null -> "Безопасность: приватный ключ не читается или неверен пароль от ключа."
            else -> null
        }
        if (problem != null) { plusError = problem; return }
        plusPlan() ?: return
        plusConfirm = true
    }

    /**
     * SSH к готовому серверу: сначала ключ (если он есть и сервер его принимает), иначе пароль root.
     * Возвращает конфигурацию для установки и для включения защиты (ключ + прежний пароль).
     */
    private suspend fun existingSsh(t: CombineServer, target: SnapshotStateList<String>): Pair<SshConfig, SshConfig> = sshFor(t.ip, target)

    private suspend fun sshFor(ip: String, target: SnapshotStateList<String>): Pair<SshConfig, SshConfig> {
        val sp = savedServer(ip)
        val (saved, key) = savedCredentials(ip)
        // пароль: сохранённый для этого сервера или введённый в «Доступе к серверам» (Инструменты), если там тот же адрес
        val pass = saved.ifEmpty { if (host.trim() == ip) password else "" }
        val keyCfg = SshConfig(ip, sp.port, sp.user, pass, key, if (key == privateKey) passphrase else secrets.get(secretKey(sp, "passphrase")))
        if (key.isNotBlank()) {
            try {
                val seen = AtomicBoolean(false)
                runner.runScript(keyCfg, Scripts.ping()) { if (it.trim() == Scripts.PING_MARKER) seen.set(true) }
                if (seen.get()) {
                    addLog(target, "✓ Вход по SSH-ключу работает.")
                    return keyCfg to keyCfg
                }
            } catch (e: SshException) {
                val m = e.message.orEmpty()
                if (pass.isEmpty() || !("Неверный" in m || "не принял" in m)) throw e
                addLog(target, "Сервер не принял ключ — вхожу по паролю root.")
            }
        }
        if (pass.isEmpty()) throw IllegalStateException(
            if (key.isBlank()) "Для $ip не сохранены ни пароль, ни SSH-ключ — введите их." else "Сервер $ip не пустил по ключу, а пароль не сохранён.",
        )
        return keyCfg.copy(privateKey = "", keyPassphrase = "") to keyCfg
    }

    // ---------- «Добавить работающий сервер» и «Добавить сервер по IP» (свободные серверы) ----------
    var importFqdn by mutableStateOf("")
    /** IP-адрес (или имя) сервера — общий для «Добавить работающий сервер» и «Добавить сервер по IP». */
    var importHost by mutableStateOf("")
    var importError by mutableStateOf<String?>(null)

    // данные входа на добавляемый сервер; пустые пароль и ключ — взять сохранённые («Доступ по SSH»)
    var freePort by mutableStateOf("22")
    var freeUser by mutableStateOf("root")
    var freeUseKey by mutableStateOf(false)
    var freePassword by mutableStateOf("")
    var freeKey by mutableStateOf("")
    var freePassphrase by mutableStateOf("")
    /** Ошибка формы «Добавить сервер по IP». */
    var freeError by mutableStateOf<String?>(null)
    /** Идёт проверка добавляемого сервера (показывается в форме). */
    var freeBusy by mutableStateOf(false)
        private set

    fun freeLoadKeyFrom(doc: Doc) {
        try {
            freeKey = doc.read().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            freeError = "Не удалось прочитать файл ключа: ${e.message}"
        }
    }

    /** Что не так с полями входа формы (null — всё в порядке); пустые пароль и ключ допустимы — возьмём сохранённые. */
    private fun freeLoginProblem(): String? {
        val port = freePort.trim().toIntOrNull()
        val u = freeUser.trim()
        return when {
            port == null || port !in 1..65535 -> "SSH-порт должен быть числом 1–65535."
            u.isEmpty() || !Regex("^[A-Za-z_][A-Za-z0-9_.-]*$").matches(u) -> "Укажите пользователя (обычно root)."
            freeUseKey && freeKey.isNotBlank() && SshKeys.publicOf(freeKey, freePassphrase) == null -> "Приватный ключ не читается или неверен пароль от ключа."
            freeUseKey && freeKey.isNotBlank() && u != "root" && freePassword.isEmpty() -> "Пользователь «$u» не root: введите пароль для sudo."
            else -> null
        }
    }

    /** SSH с данными из формы; null — пароль и ключ не введены (тогда берутся сохранённые для этого адреса). */
    private fun freeCfg(ip: String): SshConfig? {
        val key = if (freeUseKey) freeKey else ""
        if (key.isBlank() && freePassword.isEmpty()) return null
        return SshConfig(ip, freePort.trim().toInt(), freeUser.trim(), freePassword, key, if (freeUseKey) freePassphrase else "")
    }

    private fun clearFreeForm() {
        importHost = ""; freePort = "22"; freeUser = "root"; freeUseKey = false
        freePassword = ""; freeKey = ""; freePassphrase = ""
    }

    /**
     * Добавляет любой сервер по IP в «Свободные серверы» (для переезда на него): входит по SSH с паролем или ключом,
     * читает сведения о сервере и запоминает данные входа. Сам сервер не изменяется, на Главную не попадает.
     */
    fun addFreeServer(onDone: () -> Unit = {}) {
        freeError = null
        importError = null
        val h = importHost.trim().lowercase()
        val problem = when {
            h.isEmpty() || !HOSTNAME.matches(h) -> "Укажите IP-адрес сервера, например 203.0.113.10."
            else -> freeLoginProblem()
        }
        if (problem != null) { freeError = problem; return }
        if (running) return
        freeBusy = true
        launchTask(plusLog, clear = false) {
            try {
                val ip = if (IPV4.matches(h)) h else resolveIpv4(h) ?: throw IllegalStateException("Не удалось узнать IP-адрес $h.")
                stableProfiles.firstOrNull { it.vps?.ip == ip }?.let {
                    throw IllegalStateException("На $ip уже работает сервер Главной ${it.fqdn} — свободным он быть не может.")
                }
                val cfg = freeCfg(ip) ?: sshFor(ip, plusLog).first
                addLog(plusLog, "Свободный сервер: подключаюсь к ${cfg.user}@$ip:${cfg.port}…")
                val facts = java.util.concurrent.ConcurrentHashMap<String, String>()
                val code = runner.runScript(cfg, Scripts.serverFacts()) { line ->
                    if (line.startsWith(Scripts.FACT_MARKER)) {
                        val f = line.removePrefix(Scripts.FACT_MARKER).split("|", limit = 2)
                        if (f.size == 2) facts[f[0]] = f[1].trim()
                    } else if (!Links.isMarker(line)) postLog(plusLog, line)
                }
                if (code != 0) throw IllegalStateException("Сервер не дал прав root (код $code) — проверьте пользователя и пароль для sudo.")
                rememberProfile(cfg)
                val vm = opVms.firstOrNull { it.ip == ip }
                val names = mapOf("hy2" to "Hysteria2", "vless" to "VLESS", "awg" to Awg.TITLE, "tg" to "Telegram")
                val srv = CombineServer(
                    vpsId = vm?.id.orEmpty(), ip = ip, fqdn = if (IPV4.matches(h)) "" else h,
                    hostname = facts["host"].orEmpty().ifEmpty { vm?.hostname.orEmpty() },
                    protocols = facts["protocols"].orEmpty().split(" ").mapNotNull { names[it] },
                    secured = facts["pwauth"] == "no", origin = CombineServer.ORIGIN_MANUAL,
                )
                rememberCombineServer(srv)
                plusTargetId = srv.key
                addLog(plusLog, "✓ Сервер $ip добавлен в «Свободные серверы»" + facts["os"]?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty() +
                    ". Переехать на него: «Заменить сервер» → «Свободный сервер».")
                if (srv.protocols.isNotEmpty()) addLog(plusLog, "На нём уже стоят: ${srv.protocols.joinToString()} — при переезде они будут заменены протоколами вашего сервера.")
                if (facts["apt"] == "no") addLog(plusLog, "ПРЕДУПРЕЖДЕНИЕ: на сервере нет apt (не Debian/Ubuntu) — часть протоколов может не установиться.")
                if (facts["systemd"] == "no") addLog(plusLog, "ПРЕДУПРЕЖДЕНИЕ: на сервере нет systemd — протоколы на него не установятся.")
                clearFreeForm()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                freeError = e.message ?: e.javaClass.simpleName
                addLog(plusLog, "Свободный сервер не добавлен: ${freeError}")
            } finally {
                freeBusy = false
            }
        }
    }

    /**
     * Делает уже работающий сервер (например, сделанный комбайном до 1.7) сервером с постоянными ссылками:
     * по SSH читает пароли и ключи протоколов, копию сертификата и добавляет профиль на Главную. Сервер не изменяется.
     */
    fun importServer(onDone: () -> Unit = {}) {
        importError = null
        val fqdn = importFqdn.trim().trimEnd('.').lowercase()
        val h = importHost.trim().lowercase().ifEmpty { fqdn }
        when {
            !ZONE.matches(fqdn) -> { importError = "Укажите домен сервера, например vpn2.example.org."; return }
            stableProfiles.any { it.fqdn == fqdn } -> { importError = "$fqdn уже есть на Главной."; return }
            !HOSTNAME.matches(h) -> { importError = "Адрес сервера указан неверно."; return }
        }
        freeLoginProblem()?.let { importError = it; return }
        onDone()
        plusNew = false
        launchTask(plusLog) {
            plusLinks.clear(); plusSteps.clear(); plusRunTarget = null
            val ip = if (IPV4.matches(h)) h else resolveIpv4(h) ?: throw IllegalStateException("Не удалось узнать IP-адрес $h.")
            addLog(plusLog, "Подключаюсь к $ip и читаю настройки протоколов…")
            // вход: данные из формы, а если они не введены — сохранённые для этого адреса
            val ssh = freeCfg(ip) ?: sshFor(ip, plusLog).first
            val markers = java.util.Collections.synchronizedList(mutableListOf<String>())
            runner.runScript(ssh, Scripts.exportProfile()) { if (Links.isMarker(it)) markers += it else postLog(plusLog, it) }
            runner.runScript(ssh, Scripts.exportCert(fqdn, waits = 1)) { if (Links.isMarker(it)) markers += it else postLog(plusLog, it) }
            rememberProfile(ssh)
            val vm = opVms.firstOrNull { it.ip == ip }
            val res = ProfileImport.fromServer(markers.toList(), fqdn, zone, ProfileVps(vm?.id.orEmpty(), ip, vm?.hostname.orEmpty(), vm?.let { limitOf(it) }))
            if (res.found == 0) throw IllegalStateException("На $ip не найдено протоколов, которые можно перенести (Hysteria2, VLESS, AmneziaWG, Telegram).")
            var p = res.profile
            certFromMarkers(markers.toList())?.let { p = p.copy(cert = it) }
            storeStable(p)
            selectStable(p.fqdn)
            addLog(plusLog, "")
            addLog(plusLog, "✓ Сервер ${p.fqdn} добавлен на Главную. Перенесено протоколов: ${res.found}.")
            res.notes.forEach { addLog(plusLog, "• $it") }
            val dns = DnsCheck.resolveA(p.fqdn)
            if (ip !in dns) addLog(plusLog, "ПРЕДУПРЕЖДЕНИЕ: ${p.fqdn} сейчас указывает на ${dns.joinToString().ifEmpty { "ничто" }}, а не на $ip. При переезде A-запись будет исправлена.")
            if (vm == null) addLog(plusLog, "VPS с адресом $ip не найден в загруженном списке OneProvider — после переезда старый VPS удалите вручную во вкладке «VPS/DNS».")
            plusLinks.addAll(p.links())
            addLog(plusLog, "Ссылки сервера — ниже. При переносе на другой VPS они не изменятся.")
            importFqdn = ""
            clearFreeForm()
        }
    }

    /** DNS-план: имя берётся из профиля (если он есть), доступ к name.com — из общих полей. */
    private fun plusPlan(): DnsPlan? {
        val p = plusCurrent ?: return buildDnsPlan(withSubdomain = true) ?: run { plusError = errorMessage; null }
        if (ncUser.isBlank() || ncToken.isBlank()) { plusError = "Введите логин и API-токен name.com."; return null }
        val ttlNum = ttl.trim().toIntOrNull()?.coerceIn(300, 86400) ?: 300
        return DnsPlan(ncUser.trim(), ncToken.trim(), p.zone, p.subdomain, ttlNum)
    }

    fun plusStop() {
        plusJob?.cancel()
    }

    // ---------- Откат незавершённого переезда ----------

    /** Незавершённый переезд, который можно откатить (null — нет). Переживает перезапуск приложения. */
    var plusRollback by mutableStateOf(MoveRollback.fromJson(secrets.get(ROLLBACK_KEY))?.takeIf { rb -> stableProfiles.any { it.fqdn == rb.fqdn } })
        private set

    private fun saveRollback(rb: MoveRollback?) {
        plusRollback = rb
        secrets.put(ROLLBACK_KEY, rb?.toJson().orEmpty())
    }

    /** Тестам: задать состояние отката и адреса поддельных API. */
    internal fun setRollbackForTest(rb: MoveRollback?) = saveRollback(rb)
    internal var testOpUrl: String? = null
    internal var testNcUrl: String? = null

    private fun opApi(): OneProviderClient =
        testOpUrl?.let { OneProviderClient(opApiKey.trim(), opClientKey.trim(), it) } ?: OneProviderClient(opApiKey.trim(), opClientKey.trim())

    private fun ncApi(user: String, token: String): NameComClient =
        testNcUrl?.let { NameComClient(user, token, it) } ?: NameComClient(user, token)

    /**
     * После ошибки или остановки переезда запоминает, как вернуть всё назад. Для нового сервера (не переезд)
     * и когда ничего ещё не изменено — ничего не делает.
     */
    private fun offerRollback(
        before: StableProfile?, isNew: Boolean, oldVps: ProfileVps?, created: OpCreated?, existing: CombineServer?,
        newIp: String, current: PlusStep,
    ) {
        if (isNew || before == null || oldVps == null || oldVps.ip.isBlank()) return
        val now = stableProfiles.firstOrNull { it.fqdn == before.fqdn } ?: return
        val dns = current.ordinal >= PlusStep.DNS.ordinal
        val moved = now.vps?.ip != oldVps.ip
        if (created == null && !moved && !dns) return
        // повторный переезд после ошибки без отката: «настоящий» прежний сервер — из первого состояния
        val prev = plusRollback?.takeIf { it.fqdn == before.fqdn }
        if (prev != null && oldVps.ip != prev.oldIp) {
            // сервер прошлой неудачной попытки не теряем: он остаётся в свободных
            rememberCombineServer(
                CombineServer(
                    vpsId = oldVps.id, ip = oldVps.ip, fqdn = "", hostname = oldVps.hostname,
                    origin = if (oldVps.id.isEmpty()) CombineServer.ORIGIN_MANUAL else CombineServer.ORIGIN_COMBINE,
                ),
            )
        }
        val rb = MoveRollback(
            fqdn = before.fqdn, before = prev?.before ?: before.toJson(), oldIp = prev?.oldIp ?: oldVps.ip,
            newId = created?.id ?: existing?.vpsId.orEmpty(),
            newIp = newIp.ifEmpty { if (moved) now.vps?.ip.orEmpty() else "" },
            newHost = created?.hostname ?: existing?.hostname.orEmpty(),
            bought = created != null, dnsChanged = dns || prev?.dnsChanged == true,
        )
        saveRollback(rb)
        addLog(plusLog, "Переезд можно откатить: «Откатить переезд» ниже вернёт ссылки ${rb.fqdn} на прежний сервер ${rb.oldIp}.")
    }

    /** Убирает предложение отката, ничего не меняя (сервер остаётся в текущем состоянии). */
    fun plusRollbackDismiss() {
        if (running) return
        plusRollback?.let { addLog(plusLog, "Откат переезда ${it.fqdn} скрыт. Прежний сервер ${it.oldIp} не тронут.") }
        saveRollback(null)
    }

    /**
     * Откатывает незавершённый переезд: A-запись и профиль возвращаются на прежний сервер (он при переезде не менялся).
     * Купленный в этом переезде VPS удаляется ([deleteNew]) или остаётся в «Свободных серверах».
     * Ошибка name.com или OneProvider состояние не теряет — откат можно повторить.
     */
    fun plusRollbackRun(deleteNew: Boolean) {
        val rb = plusRollback ?: return
        if (running) return
        plusError = null
        val before = runCatching { StableProfile.fromJson(rb.before) }.getOrNull()
        if (before == null || stableProfiles.none { it.fqdn == rb.fqdn }) { saveRollback(null); return }
        if (rb.dnsChanged && (ncUser.isBlank() || ncToken.isBlank())) {
            plusError = "Для отката нужны логин и API-токен name.com (карточка «Аккаунты»): A-запись возвращается на прежний адрес."; return
        }
        if (deleteNew && rb.bought && (opApiKey.isBlank() || opClientKey.isBlank())) {
            plusError = "Чтобы удалить новый VPS, нужны ключи OneProvider (карточка «Аккаунты»)."; return
        }
        launchTask(plusLog, clear = false) {
            addLog(plusLog, "")
            addLog(plusLog, "ОТКАТ ПЕРЕЕЗДА ${rb.fqdn}: возвращаю прежний сервер ${rb.oldIp}")
            // 1. DNS — первым: при ошибке ничего не потеряно, откат можно повторить
            if (rb.dnsChanged) {
                val ttlNum = ttl.trim().toIntOrNull()?.coerceIn(300, 86400) ?: 300
                setupDnsRecord(
                    rb.oldIp, DnsPlan(ncUser.trim(), ncToken.trim(), before.zone, before.subdomain, ttlNum),
                    allowOverwrite = true, target = plusLog, dropAaaa = true,
                )
                moveMcpRecord(rb.oldIp, DnsPlan(ncUser.trim(), ncToken.trim(), before.zone, before.subdomain, ttlNum), plusLog)
            } else {
                addLog(plusLog, "A-запись ${rb.fqdn} не менялась — DNS не трогаю.")
            }
            // 2. профиль
            storeStable(before)
            addLog(plusLog, "✓ Профиль ${rb.fqdn} снова работает с сервером ${rb.oldIp}.")
            // 3. новый сервер
            if (rb.bought) {
                var keep = true
                if (deleteNew && rb.newId.isNotEmpty()) {
                    val r = opApi().destroyVm(rb.newId, confirmClose = false)
                    when {
                        r.destroyed -> {
                            keep = false
                            dropCombineServer(rb.newId)
                            addLog(plusLog, "✓ Новый VPS ${rb.newHost.ifEmpty { rb.newId }} удалён в OneProvider.")
                        }
                        r.overageCost.isNotEmpty() ->
                            addLog(plusLog, "! Новый VPS не удалён: перерасход трафика ${r.overageGb} ГБ, доплата ${r.overageCost}. Удалите его во вкладке «VPS/DNS» — там будет вопрос об оплате.")
                        else -> addLog(plusLog, "✗ Новый VPS не удалён: ${r.message}. Удалите его во вкладке «VPS/DNS».")
                    }
                }
                if (keep && rb.newIp.isNotEmpty()) {
                    rememberCombineServer(
                        CombineServer(vpsId = rb.newId, ip = rb.newIp, fqdn = "", hostname = rb.newHost, origin = CombineServer.ORIGIN_COMBINE),
                    )
                    addLog(plusLog, "Новый VPS ${rb.newIp} оставлен в «Свободных серверах» — на него можно переехать без покупки. Он оплачивается; удалить — во вкладке «VPS/DNS».")
                } else if (keep) {
                    addLog(plusLog, "Новый VPS ${rb.newHost.ifEmpty { rb.newId }} остался в аккаунте OneProvider — удалите его во вкладке «VPS/DNS», если он не нужен.")
                }
            } else if (rb.newIp.isNotEmpty()) {
                addLog(plusLog, "Свободный сервер ${rb.newIp} остался в списке; его протоколы могли быть частично заменены.")
            }
            saveRollback(null)
            plusSteps.clear(); plusOldVps = null; plusOldOverage = null; plusRunTarget = null
            plusLinks.clear(); plusLinks.addAll(before.links())
            if (accessTarget == rb.fqdn) loadAccess(before)
            addLog(plusLog, "✓ Откат выполнен: ссылки ${rb.fqdn} снова ведут на ${rb.oldIp}" +
                (if (rb.dnsChanged) " (DNS обновится в течение нескольких минут)" else "") + ". Нажмите «Проверить», чтобы убедиться, что сервер отвечает.")
        }
    }

    /**
     * Профиль нового сервера: протоколы и порты — из шторки «Создать сервер» (AmneziaWG и XHTTP добавляет [plusRun]).
     * VLESS «под свой домен» опирается на Hysteria2; без неё Reality маскируется под [realitySni].
     */
    internal fun newServerProfile(zone: String, subdomain: String): StableProfile {
        val p = StableProfile.create(zone, subdomain, email.trim(), masquerade.trim())
        return p.copy(
            hy2On = plusHy2, hy2Port = hyPort.trim().toIntOrNull() ?: 443,
            vlessOn = plusVless, vlessPort = vlessPort.trim().toIntOrNull() ?: 443,
            realitySni = if (plusHy2) p.fqdn else realitySni.trim().lowercase(),
            tg = if (plusTg) TgParams.generate(tgPort.trim().toIntOrNull() ?: 443, tgDomain.trim().lowercase()) else null,
            tgOn = plusTg,
        )
    }

    fun plusRun() {
        plusConfirm = false
        if (running) return
        val existing = if (plusNew) null else plusTarget
        val loc = opSelectedLocation
        val size = opSelectedSize
        val tpl = opSelectedTemplate
        if (existing == null && (loc == null || size == null || tpl == null)) return
        if (existing != null && plusCurrent == null) return
        val plan = plusPlan() ?: return
        val op = OneProviderClient(opApiKey.trim(), opClientKey.trim())
        val keys = opKeySel.toList()
        val secure = plusSecure
        // профиль создаётся до покупки VPS и сразу сохраняется
        val before = plusCurrent
        val isNew = before == null
        var profile = before ?: newServerProfile(plan.zone, plan.subdomain)
        if (isNew) {
            savePlusProfile(profile)
            selectStableQuiet(profile.fqdn)
            plusNew = false
        }
        // ключи AmneziaWG создаются один раз и живут в профиле — ссылка не меняется при переезде
        val withAwg = plusAwg
        if (withAwg && profile.awg == null) {
            val port = awgPort.trim().toIntOrNull()?.takeIf { it in 1..65535 && it != 443 } ?: Awg.randomPort()
            profile = profile.copy(awg = Awg.generate(profile.fqdn, port, plusTrailers))
        }
        // режим RandomTrailers меняется только по выбору пользователя (меняет и ссылку AmneziaWG)
        val awgNow = profile.awg
        val trailersChanged = withAwg && awgNow != null && awgNow.trailers != plusTrailers
        if (trailersChanged) profile = profile.copy(awg = awgNow?.copy(trailers = plusTrailers))
        // XHTTP: путь постоянный (выведен из UUID), поэтому включение и выключение ссылку не портят; без VLESS не работает
        val withXhttp = plusXhttp && profile.vlessOn
        if (profile.awgOn != withAwg || profile.xhttpOn != withXhttp || profile !== before) {
            profile = profile.copy(awgOn = withAwg, xhttpOn = withXhttp, xhttpPath = if (withXhttp) profile.xhttpPathEff else profile.xhttpPath)
            savePlusProfile(profile)
        }
        val oldVps = profile.vps
        persist()

        running = true
        plusActive = true
        plusLog.clear()
        plusLinks.clear()
        plusSteps.clear()
        PlusStep.entries.forEach { plusSteps[it] = StepStatus.PENDING }
        plusOldVps = null
        plusOldOverage = null
        plusRunTarget = existing
        var current = PlusStep.CREATE
        var created: OpCreated? = null
        var newIpSeen = existing?.ip.orEmpty()

        plusJob = scope.launch {
            fun step(s: PlusStep) {
                current = s
                plusSteps[s] = StepStatus.RUNNING
                addLog(plusLog, "")
                addLog(plusLog, "▶ ${s.title}")
            }
            fun done(s: PlusStep, st: StepStatus = StepStatus.DONE) { plusSteps[s] = st }

            /** Запускает скрипт с повторами, пока SSH на свежем VPS ещё не готов. */
            suspend fun runWithRetry(ssh: SshConfig, script: String, onLine: (String) -> Unit): Int {
                var attempt = 0
                while (true) {
                    attempt++
                    try {
                        return runner.runScript(ssh, script, onLine)
                    } catch (e: SshException) {
                        val msg = e.message.orEmpty()
                        val fatal = "не принял" in msg || "Неверный" in msg || "пароль" in msg || "Ключ сервера" in msg || "Вставлен" in msg
                        if (fatal || attempt >= 6) throw e
                        addLog(plusLog, "SSH пока не готов ($msg). Повтор через 20 с (попытка ${attempt + 1} из 6)…")
                        delay(20_000)
                    }
                }
            }

            try {
                addLog(plusLog, if (isNew) "Создан новый постоянный профиль ${profile.fqdn}. Сохраните его копию после установки."
                    else "Профиль ${profile.fqdn}: ссылки останутся прежними.")
                if (isNew && !profile.hy2On && profile.vlessOn) {
                    addLog(plusLog, "Hysteria2 выключена — VLESS маскируется под ${profile.realitySni} (в России такую маскировку режут чаще).")
                }
                if (trailersChanged) {
                    addLog(plusLog, "Режим RandomTrailers AmneziaWG ${if (plusTrailers) "включён" else "выключен"} — ссылку AmneziaWG " +
                        "нужно заново добавить в AmneziaVPN (остальные ссылки прежние).")
                }

                val ip: String
                val newId: String
                val rootPass: String
                val keyCfg: SshConfig
                var ssh: SshConfig
                if (existing == null) {
                    // 1. VPS
                    step(PlusStep.CREATE)
                    val c = createVmChecked(op, loc!!, size!!, tpl!!, plan.fqdn, keys)
                    created = c
                    addLog(plusLog, "VPS создан: ID ${c.id}, ${loc.title}, ${size.summary}, ${size.monthlyPrice}/мес")
                    var vmIp = c.ip
                    var tries = 0
                    while (!IPV4.matches(vmIp) && tries < 30) {
                        addLog(plusLog, "Жду назначения IPv4…")
                        delay(10_000)
                        vmIp = op.listVms().firstOrNull { it.id == c.id }?.ip.orEmpty()
                        tries++
                    }
                    if (!IPV4.matches(vmIp)) throw IllegalStateException("VPS не получил IPv4-адрес за 5 минут.")
                    ip = vmIp
                    newId = c.id
                    rootPass = c.password
                    newIpSeen = ip
                    addLog(plusLog, "IPv4: $ip")
                    // пароль root нового VPS сохраняем сразу: если переезд прервётся, сервер останется доступным (свободный сервер)
                    if (c.password.isNotEmpty()) rememberProfile(SshConfig(ip, 22, "root", c.password, "", ""))
                    // новый VPS сразу запоминаем в профиле — старый остаётся в oldVps
                    profile = profile.copy(vps = ProfileVps(c.id, ip, c.hostname, size.trafficGb?.takeIf { it.isFinite() }), movedAt = System.currentTimeMillis())
                    savePlusProfile(profile)
                    done(PlusStep.CREATE)

                    // 2. Загрузка
                    step(PlusStep.BOOT)
                    waitForSsh(ip, plusLog, 10 * 60_000, op, c.id)
                    addLog(plusLog, "SSH доступен. Даю системе ещё 20 секунд на старт служб.")
                    delay(20_000)
                    done(PlusStep.BOOT)

                    keyCfg = SshConfig(ip, 22, "root", c.password, privateKey, passphrase)
                    ssh = when {
                        c.password.isNotEmpty() -> keyCfg.copy(privateKey = "", keyPassphrase = "")
                        privateKey.isNotBlank() -> keyCfg
                        else -> throw IllegalStateException("Провайдер не выдал пароль root, а приватный ключ в «Доступе к серверам» (Инструменты) не задан.")
                    }
                } else {
                    // 1. Свободный сервер — VPS не покупается
                    step(PlusStep.CREATE)
                    ip = existing.ip
                    newId = existing.vpsId
                    addLog(plusLog, "Беру свободный сервер ${existing.title} ($ip)" + existing.location.let { if (it.isEmpty()) "" else ", $it" } + " — новый VPS не покупается.")
                    if (existing.protocols.isNotEmpty()) {
                        addLog(plusLog, "Его протоколы (${existing.protocols.joinToString()}) будут заменены протоколами профиля: прежние ссылки на этот сервер перестанут работать.")
                    }
                    done(PlusStep.CREATE)

                    // 2. Доступ по SSH
                    step(PlusStep.BOOT)
                    waitForSsh(ip, plusLog, 2 * 60_000, null, null, savedServer(ip).port)
                    val pair = existingSsh(existing, plusLog)
                    ssh = pair.first
                    keyCfg = pair.second
                    rootPass = savedCredentials(ip).first
                    profile = profile.copy(vps = ProfileVps(existing.vpsId, ip, existing.hostname), movedAt = System.currentTimeMillis())
                    savePlusProfile(profile)
                    done(PlusStep.BOOT)
                }

                // 3. DNS → новый IP
                step(PlusStep.DNS)
                setupDnsRecord(ip, plan, allowOverwrite = true, target = plusLog, dropAaaa = true)
                moveMcpRecord(ip, plan, plusLog)
                done(PlusStep.DNS)

                // DNS не ждём: Let's Encrypt спрашивает серверы имён name.com, а они знают новую запись сразу.
                // Публичные DNS проверяются в конце («Проверка сервера и DNS») — только как предупреждение.

                // 4–8. Протоколы с ключами профиля и копия сертификата
                val res = installProfile(
                    profile, ip, plusLog, strict = true,
                    run = { script, onLine -> runWithRetry(ssh, script, onLine) },
                    ssh = { ssh },
                    start = { st -> step(plusStepOf(st)) },
                    finish = { st, status -> done(plusStepOf(st), status) },
                )
                profile = res.first
                savePlusProfile(profile)
                rememberProfile(ssh)

                // Ключи для всех серверов (YubiKey и т. п.) — до «Безопасности», пока вход ещё открыт
                installDefaultKeys(ssh, plusLog)

                // 9. Безопасность — в самом конце
                if (secure && existing?.secured == true && ssh.privateKey.isNotBlank()) {
                    done(PlusStep.SECURE)
                    addLog(plusLog, "")
                    addLog(plusLog, "Безопасность: сервер уже защищён (вход только по ключу).")
                } else if (secure) {
                    step(PlusStep.SECURE)
                    try {
                        ssh = Hardener(runner) { postLog(plusLog, it) }.harden(keyCfg, rootPass)
                        rememberProfile(ssh)
                        done(PlusStep.SECURE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        done(PlusStep.SECURE, StepStatus.FAILED)
                        addLog(plusLog, "ОШИБКА БЕЗОПАСНОСТИ: ${e.message ?: e.javaClass.simpleName}")
                        addLog(plusLog, "Протоколы работают, но защита не включена. Её можно включить позже: «Инструменты» → «Доступ к серверам» → «Защитить сервер».")
                    }
                } else {
                    done(PlusStep.SECURE, StepStatus.SKIPPED)
                    addLog(plusLog, "")
                    addLog(plusLog, "Безопасность выключена — обновления и отключение пароля пропущены.")
                }

                // 10. Проверка: службы, сертификат, выход в интернет, DNS, порт 443 с телефона
                step(PlusStep.VERIFY)
                val problems = try {
                    runDiagnose(ssh, profile.fqdn, plusLog, profile.realitySni, profile.vlessPort)
                } catch (e: SshException) {
                    addLog(plusLog, "ПРЕДУПРЕЖДЕНИЕ: проверку выполнить не удалось: ${e.message}")
                    1
                }
                done(PlusStep.VERIFY, if (problems == 0) StepStatus.DONE else StepStatus.WARN)

                // 10. Ссылки — те же самые
                step(PlusStep.LINK)
                plusLinks.addAll(profile.links())
                // данные входа нового сервера уже сохранены (rememberProfile). Поля «Доступа по SSH» напрямую не меняем:
                // там может быть открыт другой сервер, и «Применить» записал бы ему чужой IP. Перечитываем, только если открыт этот.
                if (accessTarget == profile.fqdn) loadAccess(profile)
                done(PlusStep.LINK)
                addLog(plusLog, "")
                addLog(plusLog, if (isNew) "Готово! Сервер ${profile.fqdn} работает. Раздайте ссылки ниже — при смене VPS они не изменятся."
                    else "Готово! Сервер ${profile.fqdn} переехал на $ip. Ссылки прежние — клиентам ничего менять не нужно.")

                // переезд удался — откат прошлой неудачной попытки больше не нужен; её «настоящий» прежний сервер не теряем
                plusRollback?.takeIf { it.fqdn == profile.fqdn }?.let { rb ->
                    if (rb.oldIp != ip && rb.oldIp != oldVps?.ip) {
                        val was = runCatching { StableProfile.fromJson(rb.before) }.getOrNull()
                        rememberCombineServer(
                            CombineServer(
                                vpsId = was?.vps?.id.orEmpty(), ip = rb.oldIp, fqdn = "", hostname = was?.vps?.hostname.orEmpty(),
                                protocols = was?.links()?.map { l -> l.title }.orEmpty(),
                                origin = if (was?.vps?.id.isNullOrEmpty()) CombineServer.ORIGIN_MANUAL else CombineServer.ORIGIN_PROFILE,
                            ),
                        )
                        addLog(plusLog, "Сервер ${rb.oldIp}, на котором ${profile.fqdn} работал до прерванного переезда, остался в «Свободных серверах». " +
                            "Если он не нужен, удалите его VPS во вкладке «VPS/DNS».")
                    }
                    saveRollback(null)
                }

                // 10. Старый VPS
                if (existing != null) {
                    // сервер стал основным — в списке он больше не нужен
                    dropCombineServer(existing.key)
                    if (!profile.awgOn && existing.protocols.contains(Awg.TITLE)) {
                        addLog(plusLog, "На сервере остался прежний AmneziaWG со своей ссылкой — удалить его можно в блоке «Протоколы» на Главной.")
                    }
                }
                if (oldVps != null && oldVps.ip != ip && oldVps.id.isEmpty()) {
                    // прежний сервер не из OneProvider (добавлен вручную): удалить его через API нельзя — остаётся свободным
                    rememberCombineServer(
                        CombineServer(
                            vpsId = "", ip = oldVps.ip, fqdn = "", hostname = oldVps.hostname,
                            protocols = profile.links().map { l -> l.title }, secured = false, origin = CombineServer.ORIGIN_MANUAL,
                        ),
                    )
                    done(PlusStep.OLD_VPS, StepStatus.SKIPPED)
                    addLog(plusLog, "Прежний сервер ${oldVps.ip} добавлен вручную — приложение его не удаляет. Он остался в списке " +
                        "«Заменить сервер → Свободный сервер»: на него можно вернуться.")
                } else if (oldVps != null && oldVps.ip != ip && oldVps.id != newId) {
                    plusSteps[PlusStep.OLD_VPS] = StepStatus.RUNNING
                    addLog(plusLog, "Старый VPS ${oldVps.hostname.ifEmpty { oldVps.id }} (${oldVps.ip}) больше не нужен — подтвердите удаление.")
                    plusOldVps = oldVps
                } else {
                    done(PlusStep.OLD_VPS, StepStatus.SKIPPED)
                }
            } catch (e: CancellationException) {
                plusSteps[current] = StepStatus.FAILED
                addLog(plusLog, "Остановлено пользователем.")
                plusLeftovers(created, oldVps)
                offerRollback(before, isNew, oldVps, created, existing, newIpSeen, current)
            } catch (e: Exception) {
                plusSteps[current] = StepStatus.FAILED
                addLog(plusLog, "ОШИБКА: ${e.message ?: e.javaClass.simpleName}")
                plusLeftovers(created, oldVps)
                offerRollback(before, isNew, oldVps, created, existing, newIpSeen, current)
            } finally {
                running = false
                plusActive = false
                plusJob = null
            }
        }
    }

    /** Шаги установки протоколов профиля (новый сервер, переезд, «Обновить сервер»). */
    private enum class ProtoStep { HY2, VLESS, AWG, TG, CERT }

    private fun plusStepOf(s: ProtoStep) = when (s) {
        ProtoStep.HY2 -> PlusStep.HY2; ProtoStep.VLESS -> PlusStep.VLESS; ProtoStep.AWG -> PlusStep.AWG
        ProtoStep.TG -> PlusStep.TG; ProtoStep.CERT -> PlusStep.CERT
    }

    /**
     * Ставит на сервер протоколы, включённые в профиле, с его ключами — поэтому ссылки совпадают побайтно на любом VPS.
     * [strict]: ошибка Hysteria2 или VLESS прерывает работу (переезд); иначе — предупреждение и следующий протокол.
     * Возвращает профиль с копией сертификата и число протоколов, которые не установились.
     */
    private suspend fun installProfile(
        profile0: StableProfile,
        ip: String,
        target: SnapshotStateList<String>,
        strict: Boolean,
        run: suspend (String, (String) -> Unit) -> Int,
        ssh: () -> SshConfig,
        start: (ProtoStep) -> Unit,
        finish: (ProtoStep, StepStatus) -> Unit,
    ): Pair<StableProfile, Int> {
        var profile = profile0
        var failed = 0
        fun warn(s: ProtoStep, msg: String) {
            if (strict && (s == ProtoStep.HY2 || s == ProtoStep.VLESS)) throw IllegalStateException(msg)
            failed++
            finish(s, StepStatus.WARN)
            addLog(target, "$msg Продолжаю с остальными протоколами.")
        }
        val log: (String) -> Unit = { if (!Links.isMarker(it)) postLog(target, it) }

        // сайт домена с подписками — до Hysteria2: она отдаёт его из каталога
        if (profile.hy2On && profile.subAvailable) {
            try {
                run(Scripts.writeSite(StableProfile.SITE_DIR, Subscription.siteFiles(profile)), log)
            } catch (e: SshException) {
                addLog(target, "ПРЕДУПРЕЖДЕНИЕ: сайт с подписками не записан: ${e.message}")
            }
        }

        var hy2Ok = false
        if (profile.hy2On) {
            start(ProtoStep.HY2)
            val cert = profile.cert?.takeIf { it.daysLeft() > 10 }
            if (cert != null) addLog(target, "Ставлю сохранённый сертификат (ещё ${cert.daysLeft()} дн.) — Let's Encrypt не понадобится.")
            // TCP 443 занят VLESS или Telegram — сертификат выпускается через порт 80 (acmeHttp в hy2Params)
            val code = run(Scripts.install(profile.hy2Params(cert)), log)
            if (code == 0) { hy2Ok = true; finish(ProtoStep.HY2, StepStatus.DONE) }
            else warn(ProtoStep.HY2, "Установка Hysteria2 завершилась с ошибкой (код $code).")
        } else {
            finish(ProtoStep.HY2, StepStatus.SKIPPED)
        }

        if (profile.vlessOn) {
            start(ProtoStep.VLESS)
            if (profile.realitySelf && !hy2Ok) {
                warn(ProtoStep.VLESS, "VLESS пропущен: он маскируется под ${profile.fqdn}, а сайт и сертификат для этого даёт Hysteria2, которая не работает.")
            } else {
                val code = run(Scripts.installVless(profile.vlessParams()), log)
                if (code == 0) finish(ProtoStep.VLESS, StepStatus.DONE)
                else warn(ProtoStep.VLESS, "Установка VLESS завершилась с ошибкой (код $code).")
            }
        } else {
            finish(ProtoStep.VLESS, StepStatus.SKIPPED)
        }

        val awg = profile.awgEffective()
        if (profile.awgOn && awg != null) {
            start(ProtoStep.AWG)
            try {
                awgInstall(ssh(), awg, target)
                finish(ProtoStep.AWG, StepStatus.DONE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn(ProtoStep.AWG, "AmneziaWG не установлен: ${e.message}.")
            }
        } else {
            finish(ProtoStep.AWG, StepStatus.SKIPPED)
        }

        val tg = profile.tg
        if (profile.tgOn && tg != null) {
            start(ProtoStep.TG)
            val code = run(Scripts.installTelegram(tg.port, tg.front, tg.secret), log)
            if (code == 0) finish(ProtoStep.TG, StepStatus.DONE)
            else warn(ProtoStep.TG, "Установка Telegram-прокси завершилась с ошибкой (код $code).")
        } else {
            finish(ProtoStep.TG, StepStatus.SKIPPED)
        }

        // копия сертификата Let's Encrypt — на случай следующего переезда
        if (hy2Ok) {
            start(ProtoStep.CERT)
            try {
                val markers = java.util.Collections.synchronizedList(mutableListOf<String>())
                runner.runScript(ssh(), Scripts.exportCert(profile.fqdn)) { if (Links.isMarker(it)) markers += it else postLog(target, it) }
                val backup = certFromMarkers(markers.toList())
                if (backup != null) {
                    profile = profile.copy(cert = backup)
                    finish(ProtoStep.CERT, StepStatus.DONE)
                } else {
                    finish(ProtoStep.CERT, StepStatus.WARN)
                }
            } catch (e: SshException) {
                addLog(target, "ПРЕДУПРЕЖДЕНИЕ: копию сертификата получить не удалось: ${e.message}")
                finish(ProtoStep.CERT, StepStatus.WARN)
            }
        } else {
            finish(ProtoStep.CERT, StepStatus.SKIPPED)
        }
        return profile to failed
    }

    private fun plusLeftovers(created: OpCreated?, oldVps: ProfileVps?) {
        if (created != null) {
            addLog(plusLog, "Внимание: новый VPS ${created.hostname.ifEmpty { created.id }} уже создан и оплачивается. " +
                "Чтобы доставить протоколы, нажмите «Обновить сервер» на Главной (ключи и ссылки прежние) или удалите VPS во вкладке «VPS/DNS».")
        }
        if (plusSteps[PlusStep.DNS] == StepStatus.DONE && oldVps != null) {
            addLog(plusLog, "A-запись уже указывает на новый VPS. Старый VPS (${oldVps.ip}) не удалён.")
        }
    }

    private fun certFromMarkers(lines: List<String>): CertBackup? {
        fun value(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
            ?.let { runCatching { String(java.util.Base64.getDecoder().decode(it)) }.getOrNull() }
        val crt = value(Scripts.CRT_MARKER) ?: return null
        val key = value(Scripts.KEY_MARKER) ?: return null
        val notAfter = CertBackup.notAfterOf(crt) ?: return null
        return CertBackup(crt, key, value(Scripts.META_MARKER).orEmpty(), notAfter)
    }

    /** SSH к текущему серверу профиля: после безопасности вход только по ключу, поэтому ключ — первым. */
    /** Вход на выбранный сервер Главной — с данными из «Инструменты → Доступ по SSH». */
    internal fun plusSsh(): SshConfig? {
        val p = plusProfile
        if (p?.vps == null || p.vps.ip.isBlank()) {
            plusAccessProblem = p != null
            plusError = "У сервера не указан IP — укажите его в «Инструменты → Доступ по SSH» и нажмите «Применить»."
            return null
        }
        val a = accessOf(p)
        val (pass, key, phrase) = credsOf(a)
        if (hwLogin) hwKey?.let { return SshConfig(a.host, a.port, a.user, pass, "", "", it) }
        return when {
            key.isNotBlank() -> SshConfig(a.host, a.port, a.user, pass, key, phrase)
            pass.isNotEmpty() -> SshConfig(a.host, a.port, a.user, pass, "", "")
            else -> run {
                plusAccessProblem = true
                plusError = "Нужен пароль или SSH-ключ: «Инструменты → Доступ по SSH», выберите ${p.fqdn} и нажмите «Применить»."
                null
            }
        }
    }

    /** Проверка текущего сервера профиля (кнопка «Проверить» на Главной). */
    fun plusDiagnose() {
        val p = plusProfile ?: return
        val ssh = plusSsh() ?: return
        plusError = null
        launchTask(plusLog) {
            addLog(plusLog, "Читаю с сервера ${ssh.host} установленные протоколы…")
            val markers = java.util.Collections.synchronizedList(mutableListOf<String>())
            runner.runScript(ssh, Scripts.exportProfile()) { if (Links.isMarker(it)) markers += it else postLog(plusLog, it) }
            val (synced, notes) = ProfileImport.syncWithServer(p, markers.toList())
            if (synced != p) storeStable(synced)
            if (notes.isEmpty()) addLog(plusLog, "✓ Постоянные ссылки совпадают с сервером.") else notes.forEach { addLog(plusLog, "• $it") }
            plusLinks.clear()
            plusLinks.addAll(synced.links())

            // службы, трафик и подписки
            val check = java.util.Collections.synchronizedList(mutableListOf<String>())
            runner.runScript(ssh, ServerCheck.script()) { if (Links.isMarker(it)) check += it else postLog(plusLog, it) }
            val services = ServerCheck.services(check.toList())
            val traffic = ServerCheck.monthTrafficGb(check.toList())
            services.forEach { (name, ok) -> addLog(plusLog, (if (ok) "✓ " else "✗ ") + name + if (ok) " работает" else " — служба не запущена") }
            // лимит трафика тарифа — у OneProvider; запоминается в профиле, чтобы полоса была и после перезапуска
            val limit = fetchTrafficLimit(synced, plusLog)
            val vps = synced.vps?.let { v -> if (limit != null) v.copy(trafficLimitGb = limit) else v }
            traffic?.let { addLog(plusLog, "Трафик за месяц: ${"%.1f".format(it)} ГБ" + ((limit ?: vps?.trafficLimitGb)?.let { l -> " из ${l.toInt()} ГБ" } ?: "")) }
            if (synced.subAvailable) {
                if (ServerCheck.SITE_MARKER in check) {
                    runner.runScript(ssh, Scripts.writeSite(StableProfile.SITE_DIR, Subscription.siteFiles(synced))) { if (!Links.isMarker(it)) postLog(plusLog, it) }
                } else {
                    addLog(plusLog, "Подписка Happ ещё не включена на этом сервере — нажмите «Обновить сервер» в «Ссылках» (ключи и ссылки не меняются).")
                }
            }
            storeStable(synced.copy(status = services, checkAt = System.currentTimeMillis(), trafficGb = traffic ?: synced.trafficGb, vps = vps))
            addLog(plusLog, "")
            addLog(plusLog, "Проверяю сервер ${ssh.host} и имя ${synced.fqdn} …")
            runDiagnose(ssh, synced.fqdn, plusLog, synced.realitySni, synced.vlessPort)
        }
    }

    // ---------- Блок «Протоколы» на Главной: что стоит на сервере выбранного профиля ----------
    /** Протоколы на сервере (null — ещё не читали или выбран другой сервер). */
    var homeProtoInstalled by mutableStateOf<Set<ServerMode>?>(null)
        internal set
    /** Отмеченные галочки: после «Применить» на сервере будут ровно они. */
    var homeProtoChosen by mutableStateOf<Set<ServerMode>>(emptySet())
        internal set
    var homeProtoConfirm by mutableStateOf(false)

    /** Будут установлены / будут удалены при «Применить». */
    val homeProtoAdd: Set<ServerMode> get() = homeProtoChosen - (homeProtoInstalled ?: homeProtoChosen)
    val homeProtoRemove: Set<ServerMode> get() = (homeProtoInstalled ?: emptySet()) - homeProtoChosen

    /** Читает с сервера, какие протоколы стоят; заодно ключи протоколов, поставленных прежней вкладкой «Сервер», становятся постоянными. */
    fun homeProtoRead() {
        val p = plusProfile ?: return
        if (p.vps == null) return
        val ssh = plusSsh() ?: return
        plusError = null
        launchTask(plusLog) {
            plusSteps.clear()
            addLog(plusLog, "Читаю протоколы с сервера ${p.fqdn} (${ssh.host})…")
            val markers = java.util.Collections.synchronizedList(mutableListOf<String>())
            runner.runScript(ssh, Scripts.exportProfile()) { if (Links.isMarker(it)) markers += it else postLog(plusLog, it) }
            val (synced, notes) = ProfileImport.syncWithServer(p, markers.toList())
            if (synced != p) storeStable(synced)
            notes.forEach { addLog(plusLog, "• $it") }
            val found = ProfileImport.installedModes(markers.toList())
            homeProtoInstalled = found
            homeProtoChosen = found
            addLog(plusLog, if (found.isEmpty()) "На сервере нет протоколов, поставленных этим приложением."
                else "✓ На сервере: " + ServerMode.entries.filter { it in found }.joinToString { it.title } + ".")
        }
    }

    /** Галочка протокола. XHTTP работает только поверх VLESS: включает его, а снятый VLESS снимает и XHTTP. */
    fun homeProtoToggle(m: ServerMode) {
        var next = if (m in homeProtoChosen) homeProtoChosen - m else homeProtoChosen + m
        if (m == ServerMode.XHTTP && ServerMode.XHTTP in next) next = next + ServerMode.VLESS
        if (m == ServerMode.VLESS && ServerMode.VLESS !in next) next = next - ServerMode.XHTTP
        homeProtoChosen = next
    }

    /** «Применить»: проверка выбора и диалог подтверждения (установка и удаление меняют ссылки у всех людей). */
    fun homeProtoRequest() {
        val p = plusProfile ?: return
        val chosen = homeProtoChosen
        plusError = when {
            homeProtoInstalled == null -> "Сначала прочитайте протоколы с сервера."
            chosen.isEmpty() -> "Оставьте хотя бы один протокол — иначе у сервера не останется ни одной ссылки."
            ServerMode.VLESS in chosen && p.realitySelf && ServerMode.HY2 !in chosen ->
                "VLESS маскируется под ${p.fqdn}: сайт и сертификат для этого даёт Hysteria2. Оставьте Hysteria2 или уберите и VLESS."
            else -> null
        }
        if (plusError == null && (homeProtoAdd.isNotEmpty() || homeProtoRemove.isNotEmpty())) homeProtoConfirm = true
    }

    /** Порт нового Telegram-прокси: из «Доступа к серверам», но не 80 (Let's Encrypt) и не занятый VLESS или сайтом Hysteria2. */
    private fun newTgPort(p: StableProfile): Int {
        val want = tgPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 993
        return if (want == 80 || want == 443 || (p.vlessOn && want == p.vlessPort)) 993 else want
    }

    /** Порт нового AmneziaWG: из «Доступа к серверам», но не порт Hysteria2. */
    private fun newAwgPort(p: StableProfile): Int =
        awgPort.trim().toIntOrNull()?.takeIf { it in 1..65535 && it != p.hy2Port && it != 443 } ?: Awg.randomPort()

    /**
     * Ставит отмеченные и удаляет снятые протоколы на сервере выбранного профиля. Ключи новых протоколов создаются один раз
     * и хранятся в профиле, поэтому их ссылки постоянные; подписка Happ перезаписывается с новым набором ссылок.
     */
    fun homeProtoApply() {
        homeProtoConfirm = false
        val p0 = plusProfile ?: return
        val installed = homeProtoInstalled ?: return
        val ssh = plusSsh() ?: return
        val chosen = homeProtoChosen
        val add = chosen - installed
        val remove = installed - chosen
        var p = p0.copy(
            hy2On = ServerMode.HY2 in chosen, vlessOn = ServerMode.VLESS in chosen, xhttpOn = ServerMode.XHTTP in chosen,
            awgOn = ServerMode.AWG in chosen, tgOn = ServerMode.TG in chosen,
        )
        if (p.xhttpOn) p = p.copy(xhttpPath = p.xhttpPathEff)
        if (p.awgOn && p.awg == null) p = p.copy(awg = Awg.generate(p.fqdn, newAwgPort(p), plusTrailers))
        if (p.tgOn && p.tg == null) p = p.copy(tg = TgParams.generate(newTgPort(p), tgDomain.trim().lowercase().ifEmpty { "www.microsoft.com" }))
        // сайт с подписками отдаёт Hysteria2, а где именно (443 или 127.0.0.1:8443) — зависит от VLESS и Telegram
        val changed = add + remove
        val needHy2 = p.hy2On && (ServerMode.HY2 in add || ServerMode.VLESS in changed || ServerMode.TG in changed)
        val needVless = p.vlessOn && (ServerMode.VLESS in add || ServerMode.XHTTP in add)
        fun names(set: Set<ServerMode>) = ServerMode.entries.filter { it in set }.joinToString { it.title }
        plusError = null
        launchTask(plusLog) {
            plusSteps.clear()
            addLog(plusLog, "Протоколы ${p.fqdn} (${ssh.host}): " + listOfNotNull(
                ("установить — " + names(add)).takeIf { add.isNotEmpty() },
                ("удалить — " + names(remove)).takeIf { remove.isNotEmpty() },
            ).joinToString("; ") + ".")
            val log: (String) -> Unit = { if (!Links.isMarker(it)) postLog(plusLog, it) }
            suspend fun step(title: String, script: String): Boolean {
                addLog(plusLog, ""); addLog(plusLog, "▶ $title")
                val code = runner.runScript(ssh, script, log)
                if (code != 0) addLog(plusLog, "ПРЕДУПРЕЖДЕНИЕ: $title — завершено с кодом $code.")
                return code == 0
            }

            // 1. удаление (XHTTP вместе с VLESS уходит и так)
            if (ServerMode.XHTTP in remove && ServerMode.VLESS !in remove) step("Удаление VLESS XHTTP", Scripts.removeXhttp())
            if (ServerMode.TG in remove) step("Удаление Telegram-прокси", Scripts.removeTelegram())
            if (ServerMode.AWG in remove) step("Удаление AmneziaWG", Scripts.remove(Protocol.AWG))
            if (ServerMode.VLESS in remove) step("Удаление VLESS", Scripts.remove(Protocol.VLESS))
            if (ServerMode.HY2 in remove) step("Удаление Hysteria2", Scripts.remove(Protocol.HY2))

            // 2. установка с ключами профиля
            if (p.hy2On && p.subAvailable) runner.runScript(ssh, Scripts.writeSite(StableProfile.SITE_DIR, Subscription.siteFiles(p)), log)
            if (needHy2) {
                val cert = p.cert?.takeIf { it.daysLeft() > 10 }
                if (step("Hysteria2 + Salamander (ключи профиля)", Scripts.install(p.hy2Params(cert))) && p.cert == null) {
                    val markers = java.util.Collections.synchronizedList(mutableListOf<String>())
                    runner.runScript(ssh, Scripts.exportCert(p.fqdn)) { if (Links.isMarker(it)) markers += it else postLog(plusLog, it) }
                    certFromMarkers(markers.toList())?.let { p = p.copy(cert = it) }
                }
            }
            if (needVless) step("VLESS + Reality" + (if (p.xhttpOn) " + XHTTP" else "") + " (ключи профиля)", Scripts.installVless(p.vlessParams()))
            val awg = p.awgEffective()
            if (ServerMode.AWG in add && awg != null) {
                addLog(plusLog, ""); addLog(plusLog, "▶ AmneziaWG 3.1 (ключи профиля)")
                try {
                    awgInstall(ssh, awg, plusLog)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addLog(plusLog, "ПРЕДУПРЕЖДЕНИЕ: AmneziaWG не установлен: ${e.message}")
                }
            }
            val tg = p.tg
            if (ServerMode.TG in add && tg != null) step("Telegram-прокси (порт ${tg.port}/TCP)", Scripts.installTelegram(tg.port, tg.front, tg.secret))

            // 3. сверка с сервером: в ссылках и подписке остаются только протоколы, которые реально встали
            val markers = java.util.Collections.synchronizedList(mutableListOf<String>())
            runner.runScript(ssh, Scripts.exportProfile()) { if (Links.isMarker(it)) markers += it }
            val now = ProfileImport.installedModes(markers.toList())
            val failed = chosen - now
            if (failed.isNotEmpty()) {
                p = p.copy(
                    hy2On = p.hy2On && ServerMode.HY2 in now, vlessOn = p.vlessOn && ServerMode.VLESS in now,
                    xhttpOn = p.xhttpOn && ServerMode.XHTTP in now, awgOn = p.awgOn && ServerMode.AWG in now, tgOn = p.tgOn && ServerMode.TG in now,
                )
                if (p.hy2On && p.subAvailable) runner.runScript(ssh, Scripts.writeSite(StableProfile.SITE_DIR, Subscription.siteFiles(p)), log)
            }
            storeStable(p)
            homeProtoInstalled = now
            homeProtoChosen = now
            plusLinks.clear(); plusLinks.addAll(p.links())
            addLog(plusLog, "")
            addLog(plusLog, if (failed.isEmpty()) "✓ Готово. Ссылки и подписка Happ обновлены; ссылки прежних протоколов не изменились."
                else "Готово, но не установилось: ${names(failed)} — см. журнал выше. Остальные ссылки и подписка обновлены.")
            if (ServerMode.HY2 in remove) addLog(plusLog, "Hysteria2 удалена — подписка Happ на этом сервере больше не открывается (её отдавала Hysteria2).")
        }
    }

    /** Лимит трафика VPS профиля (ГБ): из загруженного списка VPS OneProvider, иначе сохранённый в профиле. */
    fun trafficLimitGb(p: StableProfile): Double? {
        val v = p.vps ?: return null
        return opVms.firstOrNull { sameVps(v, it) }?.let { limitOf(it) } ?: v.trafficLimitGb
    }

    private fun sameVps(v: ProfileVps, vm: OpVm) = (v.id.isNotEmpty() && vm.id == v.id) || vm.ip == v.ip

    /** Лимит трафика тарифа VPS (ГБ в месяц); null — без лимита или неизвестен. */
    private fun limitOf(vm: OpVm): Double? = OneProviderClient.parseTrafficGb(vm.bandwidthGb)?.takeIf { it.isFinite() }

    /** Свежий список VPS OneProvider; лимиты трафика заодно запоминаются в профилях (полоса трафика — и после перезапуска). */
    private fun setOpVms(list: List<OpVm>, except: String? = null) {
        opVms.clear(); opVms.addAll(list)
        opSelected.retainAll { id -> list.any { it.id == id } }
        opLoaded = true
        stableProfiles.toList().forEach { p ->
            val v = p.vps ?: return@forEach
            if (p.fqdn == except) return@forEach
            val limit = list.firstOrNull { sameVps(v, it) }?.let { limitOf(it) } ?: return@forEach
            if (limit != v.trafficLimitGb) storeStable(p.copy(vps = v.copy(trafficLimitGb = limit)))
        }
    }

    /** Лимит трафика VPS профиля у OneProvider, если введены ключи; null — не узнать (ошибка, VPS не в этом аккаунте). */
    private suspend fun fetchTrafficLimit(p: StableProfile, target: SnapshotStateList<String>): Double? {
        val v = p.vps ?: return null
        if (opApiKey.isBlank() || opClientKey.isBlank()) return null
        val list = try {
            OneProviderClient(opApiKey.trim(), opClientKey.trim()).listVms()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            addLog(target, "Лимит трафика у OneProvider узнать не удалось: ${e.message}")
            return null
        }
        setOpVms(list, except = p.fqdn)
        val vm = list.firstOrNull { sameVps(v, it) }
        if (vm == null) addLog(target, "VPS ${v.ip} нет в аккаунте OneProvider — лимит трафика неизвестен.")
        return vm?.let { limitOf(it) }
    }

    // ---------- Работа в фоне и контроль ----------
    /** Текст уведомления о ходе работы: текущий шаг создания или переезда сервера, иначе последняя строка журнала. */
    val jobText: String
        get() {
            plusSteps.entries.firstOrNull { it.value == StepStatus.RUNNING }?.let { return "${plusProfile?.fqdn.orEmpty()}: ${it.key.title}" }
            return listOf(plusLog, log, tgLog, opLog, dnsLog).firstOrNull { it.isNotEmpty() }
                ?.lastOrNull { it.isNotBlank() }?.take(120) ?: "Работа с сервером…"
        }

    /** Отчёт о прошлом падении приложения (показывается один раз). */
    var crashReport by mutableStateOf<String?>(null)

    /** Баланс аккаунта OneProvider (если API его отдаёт). */
    var opBalance by mutableStateOf<String?>(null)
        private set

    private var balanceTried = false

    /** Тестам: показать баланс без обращения к OneProvider. */
    internal fun setBalanceForTest(b: String?) { opBalance = b }

    fun loadBalance() {
        if (balanceTried) return
        val client = opClient() ?: return
        balanceTried = true
        scope.launch {
            val (b, format) = runCatching { client.balance() }.getOrElse { null to (it.message ?: "") }
            opBalance = b
            if (b == null) opLog.add("Баланс OneProvider: API его не отдаёт ($format).")
        }
    }

    // ---------- Люди ----------
    /** Ожидающее подтверждения изменение списка людей (оно переустанавливает протоколы на сервере). */
    var peopleConfirm by mutableStateOf<StableProfile?>(null)
    var peopleConfirmText by mutableStateOf("")

    private fun proposePeople(next: StableProfile, text: String) {
        peopleConfirmText = text
        peopleConfirm = next
    }

    fun addPerson(name: String) {
        val p = plusProfile ?: return
        val u = ProfileUser.create(name, p.users)
        val warn = if (!p.hy2MultiUser) "\n\nВнимание: у Hysteria2 при втором человеке включается режим «имя:пароль» — ваша ссылка Hysteria2 " +
            "изменится один раз (VLESS и AmneziaWG — нет). Подписка Happ обновится сама." else ""
        proposePeople(p.copy(users = p.users + u), "Добавить «${u.name}» на ${p.fqdn}? У него будут свои ключи и своя подписка; " +
            "протоколы на сервере будут переустановлены с новым списком людей (несколько минут).$warn")
    }

    fun togglePerson(id: String) {
        val p = plusProfile ?: return
        val u = p.users.firstOrNull { it.id == id } ?: return
        val next = p.copy(users = p.users.map { if (it.id == id) it.copy(disabled = !it.disabled) else it })
        proposePeople(next, if (u.disabled) "Включить «${u.name}»? Его прежние ссылки снова заработают." else
            "Отключить «${u.name}»? Его ссылки перестанут работать (Telegram-прокси общий — его отключить нельзя). Включить можно обратно.")
    }

    fun removePerson(id: String) {
        val p = plusProfile ?: return
        val u = p.users.firstOrNull { it.id == id } ?: return
        proposePeople(p.copy(users = p.users.filter { it.id != id }), "Удалить «${u.name}» насовсем? Его ссылки перестанут работать, вернуть их нельзя.")
    }

    /** Подтверждено: сохраняем людей и переустанавливаем протоколы на текущем сервере. */
    fun confirmPeople() {
        val next = peopleConfirm ?: return
        peopleConfirm = null
        plusApply(next, "Применяю список людей")
    }

    /**
     * Переустанавливает протоколы текущего сервера по профилю (ключи прежние) — для людей, подписки и после ручных правок.
     * Профиль сохраняется сразу; если сервер недоступен, изменения применятся при следующем «Обновить сервер» или переезде.
     */
    fun plusApply(next: StableProfile? = plusProfile, what: String = "Обновляю сервер по профилю") {
        val p = next ?: return
        val ssh = plusSsh() ?: return
        storeStable(p)
        plusError = null
        launchTask(plusLog) {
            plusSteps.clear()
            addLog(plusLog, "$what: ${p.fqdn} (${ssh.host}). Ключи и ссылки остаются прежними.")
            val res = installProfile(
                p, ssh.host, plusLog, strict = false,
                run = { script, onLine -> runner.runScript(ssh, script, onLine) },
                ssh = { ssh },
                start = { st -> addLog(plusLog, ""); addLog(plusLog, "▶ " + plusStepOf(st).title) },
                finish = { _, _ -> },
            )
            storeStable(res.first)
            plusLinks.clear(); plusLinks.addAll(res.first.links())
            addLog(plusLog, "")
            addLog(plusLog, if (res.second == 0) "✓ Готово: сервер обновлён." else "Готово, но не всё установилось (${res.second}) — см. журнал выше.")
        }
    }

    /**
     * Переводит VLESS на Reality «под себя» (SNI = свой домен) и переустанавливает Hysteria2 и VLESS на текущем
     * сервере — без покупки нового VPS. Ссылка VLESS меняется один раз (параметр sni), Hysteria2 — нет.
     */
    fun plusSwitchToSelf() {
        plusConfirmSelf = false
        val p0 = plusProfile ?: return
        val ssh = plusSsh() ?: return
        val p = p0.copy(realitySni = p0.fqdn)
        plusError = null
        launchTask(plusLog) {
            addLog(plusLog, "Перевожу VLESS на маскировку под ${p.fqdn} на сервере ${ssh.host} …")
            val cert = p.cert?.takeIf { it.daysLeft() > 10 }
            val hy = runner.runScript(ssh, Scripts.install(p.hy2Params(cert))) { if (!Links.isMarker(it)) postLog(plusLog, it) }
            if (hy != 0) throw IllegalStateException("Переустановка Hysteria2 завершилась с ошибкой (код $hy). Профиль не изменён.")
            val vl = runner.runScript(ssh, Scripts.installVless(p.vlessParams())) { if (!Links.isMarker(it)) postLog(plusLog, it) }
            if (vl != 0) throw IllegalStateException("Переустановка VLESS завершилась с ошибкой (код $vl). Профиль не изменён.")
            savePlusProfile(p)
            plusLinks.clear()
            plusLinks.addAll(p.links())
            addLog(plusLog, "")
            addLog(plusLog, "Готово! VLESS теперь маскируется под ${p.fqdn}. Новую ссылку VLESS (ниже) нужно один раз " +
                "импортировать в Happ заново, старую удалите. Ссылка Hysteria2 не изменилась.")
        }
    }

    fun plusLiveCheck() {
        val ssh = plusSsh() ?: return
        plusError = null
        launchTask(plusLog) { runLiveCheck(ssh, plusLog) }
    }

    /**
     * Защита уже работающего сервера без переустановки: обновления, автообновления, вход только по SSH-ключу.
     * Протоколы и ссылки не меняются. Заодно доделывает прерванную установку пакетов (dpkg --configure -a).
     */
    fun secureNow() {
        val ssh = buildSshConfig(allowHw = false) ?: return
        if (curKey.isBlank()) {
            return fail(
                if (vpsMode) "Для защиты нужен приватный SSH-ключ, который добавлен на сервер: выберите «SSH-ключ» в блоке «Сервер» и вставьте его."
                else "Для защиты нужен приватный SSH-ключ, который добавлен на сервер (например, ключ из кабинета OneProvider): " +
                    "выберите «SSH-ключ» в «Доступе к серверам» и вставьте его.",
            )
        }
        if (SshKeys.publicOf(curKey, curPhrase) == null) return fail("Приватный ключ не читается или неверен пароль от ключа.")
        persist()
        launchTask(log) {
            addLog(log, "БЕЗОПАСНОСТЬ ${ssh.host}: обновления, автообновления, вход только по SSH-ключу, файрвол, fail2ban. Протоколы и ссылки не меняются.")
            val keyCfg = ssh.copy(privateKey = curKey, keyPassphrase = curPhrase, password = curPass)
            val secured = Hardener(runner) { postLog(log, it) }.harden(keyCfg, if (curUseKey) "" else curPass)
            markKeyLogin()
            rememberProfile(secured)
            addLog(log, "")
            addLog(log, "Готово! Сервер защищён: вход только по ключу, автообновления, файрвол и fail2ban (подробности выше).")
        }
    }

    fun liveCheck() {
        val ssh = buildSshConfig() ?: return
        persist()
        launchTask(log) { runLiveCheck(ssh, log) }
    }

    /**
     * «Живая проверка»: на сервере на [LIVE_SECONDS] с включаются подробные журналы, пользователь в это время
     * включает Happ; затем итог — дошёл ли телефон до сервера и подошли ли ключи.
     */
    private suspend fun runLiveCheck(ssh: SshConfig, target: SnapshotStateList<String>) {
        val phoneIp = withContext(Dispatchers.IO) {
            runCatching {
                val c = java.net.URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6_000; c.readTimeout = 6_000
                c.inputStream.bufferedReader().use { it.readText().trim() }.also { c.disconnect() }
            }.getOrDefault("")
        }
        addLog(target, "Запускаю живую проверку на ${ssh.host}" + (if (phoneIp.isNotEmpty()) " (IP ${Device.GEN}: $phoneIp)" else "") + " …")
        var started = false
        runner.runScript(ssh, Scripts.liveCheck(LIVE_SECONDS, phoneIp)) { if (it.trim() == Scripts.LIVE_STARTED) started = true else postLog(target, it) }
        if (!started) throw IllegalStateException("Живую проверку запустить не удалось.")
        addLog(target, "")
        addLog(target, "СЕЙЧАС: сверните приложение, в Happ выключите и снова включите VLESS, откройте любой сайт; " +
            "затем то же с Hysteria2. У вас $LIVE_SECONDS секунд.")
        var left = LIVE_SECONDS + 5
        while (left > 0) {
            delay(15_000)
            left -= 15
            if (left > 0) addLog(target, "… осталось примерно $left с")
        }
        addLog(target, "Читаю результат…")
        runner.runScript(ssh, Scripts.liveResult()) { postLog(target, it) }
        rememberProfile(ssh)
    }

    /** Удаляет старый VPS после переезда (с подтверждением; при перерасходе — второй вопрос). */
    fun plusDeleteOld(confirmOverage: Boolean = false) {
        val old = plusOldVps ?: return
        val client = opClient() ?: return
        plusOldOverage = null
        launchTask(plusLog, clear = false) {
            val r = client.destroyVm(old.id, confirmClose = confirmOverage)
            when {
                r.destroyed -> {
                    plusOldVps = null
                    dropCombineServer(old.id)
                    plusSteps[PlusStep.OLD_VPS] = StepStatus.DONE
                    addLog(plusLog, "✓ Старый VPS ${old.hostname.ifEmpty { old.id }} удалён.")
                }
                !confirmOverage && r.overageCost.isNotEmpty() -> {
                    plusOldOverage = r
                    addLog(plusLog, "! Перерасход трафика ${r.overageGb} ГБ, доплата ${r.overageCost} — нужно подтверждение.")
                }
                else -> addLog(plusLog, "✗ ${r.message}")
            }
        }
    }

    fun plusKeepOld() {
        plusOldVps?.let {
            addLog(plusLog, "Старый VPS ${it.hostname.ifEmpty { it.id }} оставлен — удалить можно во вкладке «VPS/DNS». " +
                "Он добавлен в список «Заменить сервер → Свободный сервер»: на него можно вернуться без покупки VPS.")
            val p = plusProfile
            rememberCombineServer(
                CombineServer(
                    vpsId = it.id, ip = it.ip, fqdn = p?.fqdn.orEmpty(), hostname = it.hostname,
                    protocols = p?.links()?.map { l -> l.title }.orEmpty(), secured = false, origin = CombineServer.ORIGIN_PROFILE,
                ),
            )
        }
        plusOldVps = null
        plusOldOverage = null
        plusSteps[PlusStep.OLD_VPS] = StepStatus.SKIPPED
    }

    /** Настройки доступа для копии: «Доступ к серверам», сохранённые серверы, имя, ключи OneProvider и name.com. */
    private fun currentSettings(): BackupSettings = BackupSettings(
        host = host.trim(), sshPort = sshPort.trim(), user = user.trim(), useKey = useKey,
        password = password, privateKey = privateKey, passphrase = passphrase,
        zone = zone.trim(), subdomain = subdomain.trim(), ttl = ttl.trim(), email = email.trim(), masquerade = masquerade.trim(),
        ncUser = ncUser.trim(), ncToken = ncToken.trim(), opApiKey = opApiKey.trim(), opClientKey = opClientKey.trim(),
        servers = profiles.map {
            SavedServer(it, secrets.get(secretKey(it, "password")), secrets.get(secretKey(it, "privateKey")), secrets.get(secretKey(it, "passphrase")))
        },
        hwKey = if (hwKeys.isEmpty()) "" else app.hy2setup.hwkey.HwKey.listToJson(hwKeys),
        defaultKeys = if (defaultKeys.isEmpty()) "" else DefaultKeys.encode(defaultKeys),
        vpsServers = vpsServers.map {
            SavedServer(
                it, secrets.get(vpsSecret(it, "password")), secrets.get(vpsSecret(it, "privateKey")), secrets.get(vpsSecret(it, "passphrase")),
                domain = prefs.getString("vpsDomain:${it.id}", "") ?: "", self = prefs.getBoolean("vpsSelf:${it.id}", true),
            )
        },
    )

    private fun backupData() = BackupData(stableProfiles.toList(), currentSettings())

    /** Отпечатки серверов, попавших в сохранённую копию (домен → отпечаток ключей и людей). */
    private var backupSaved by mutableStateOf(Backup.decodeSaved(prefs.getString("backupServers", null)))
    private var fpCache: Pair<StableProfile, String>? = null

    /**
     * Ключи или люди выбранного сервера менялись после последней копии с ним. Настройки «Доступа к серверам»
     * сюда не входят — иначе плашка загоралась бы после каждого перезапуска.
     */
    val backupStale: Boolean
        get() {
            val p = plusProfile ?: return false
            migrateBackupHash()
            val fp = fpCache?.takeIf { it.first == p }?.second ?: Backup.serverFingerprint(p).also { fpCache = p to it }
            return backupSaved[p.fqdn] != fp
        }

    /** Серверы есть в копии (сохранённой или прочитанной) — плашка для них гаснет. */
    private fun markBackedUp(servers: List<StableProfile>) {
        if (servers.isEmpty()) return
        backupSaved = backupSaved + servers.associate { it.fqdn to Backup.serverFingerprint(it) }
        prefs.edit().putString("backupServers", Backup.encodeSaved(backupSaved)).apply()
    }

    /** До 3.1 хранился один отпечаток всей копии: если он ещё совпадает — все серверы считаются сохранёнными. */
    private fun migrateBackupHash() {
        val old = prefs.getString("backupHash", null) ?: return
        prefs.edit().remove("backupHash").apply()
        if (runCatching { Backup.fingerprint(backupData()) }.getOrNull() == old) markBackedUp(stableProfiles.toList())
    }

    /** Сохраняет копию всех серверов и настроек доступа. [pass] пустой — без шифрования. */
    fun plusExport(doc: Doc, pass: String) {
        val data = backupData()
        if (pass.isNotEmpty() && pass.length < 8) { plusError = "Пароль копии — не короче 8 символов (или оставьте поле пустым)."; return }
        plusError = null
        launchTask(plusLog, clear = false) {
            val text = withContext(Dispatchers.Default) { Backup.encode(data, pass) }
            withContext(Dispatchers.IO) {
                doc.write(text.toByteArray())
            }
            markBackedUp(data.servers)
            addLog(plusLog, "Копия сохранена: серверов ${data.servers.size}" + (if (data.servers.isNotEmpty()) " (${data.servers.joinToString { it.fqdn }})" else "") +
                ", настройки «Доступа к серверам» и ключи OneProvider и name.com. " +
                if (pass.isEmpty()) "Файл НЕ зашифрован — храните его надёжно и никому не пересылайте." else "Файл зашифрован паролем — без пароля его не открыть.")
        }
    }

    /** Серверы из копии, которые заменят существующие с тем же доменом, — по одному, с подтверждением. */
    private val pendingImports = ArrayDeque<StableProfile>()
    /** Зашифрованная копия ждёт пароль. */
    var importNeedsPassword by mutableStateOf(false)
    private var importText: String? = null
    /** Настройки из копии, которые заменят уже заполненные поля, — после подтверждения. */
    var pendingSettings by mutableStateOf<BackupSettings?>(null)

    /** Читает файл копии: открытую — сразу, зашифрованную — после ввода пароля. */
    fun plusImport(doc: Doc) {
        plusError = null
        launchTask(plusLog, clear = false) {
            val text = withContext(Dispatchers.IO) { String(doc.read()) }
            if (Backup.isEncrypted(text)) {
                importText = text
                importNeedsPassword = true
            } else {
                applyBackup(text, "")
            }
        }
    }

    fun plusImportWithPassword(pass: String) {
        importNeedsPassword = false
        val text = importText ?: return
        importText = null
        launchTask(plusLog, clear = false) { applyBackup(text, pass) }
    }

    fun cancelImportPassword() {
        importNeedsPassword = false
        importText = null
    }

    private suspend fun applyBackup(text: String, pass: String) {
        val data = try {
            withContext(Dispatchers.Default) { Backup.decode(text, pass) }
        } catch (e: BackupCrypto.WrongPassword) {
            plusError = e.message; return
        } catch (e: IllegalArgumentException) {
            plusError = e.message; return
        } catch (e: org.json.JSONException) {
            plusError = "Файл повреждён или это не копия vpngramm."; return
        }
        // эти ключи уже лежат в файле копии — напоминать о ней не нужно
        markBackedUp(data.servers)
        var added = 0
        for (p in data.servers) {
            val same = stableProfiles.firstOrNull { it.fqdn == p.fqdn }
            when {
                same == null -> { savePlusProfile(p); added++ }
                same != p -> pendingImports.addLast(p)
            }
        }
        if (added > 0) {
            if (stableProfiles.size == added) selectStable(data.servers.first().fqdn)
            addLog(plusLog, "Из копии добавлено серверов: $added.")
        }
        data.settings?.let { s ->
            if (settingsEmpty()) {
                applySettings(s)
                addLog(plusLog, "Из копии восстановлены настройки «Доступа к серверам» и ключи OneProvider и name.com.")
            } else if (s != currentSettings()) {
                pendingSettings = s
            }
        }
        plusPendingImport = pendingImports.removeFirstOrNull()
        if (added == 0 && plusPendingImport == null && pendingSettings == null) addLog(plusLog, "Всё из копии уже есть на ${Device.PREP} и совпадает.")
    }

    /** Поля доступа ещё не заполнены — настройки из копии можно поставить без вопросов. */
    private fun settingsEmpty() = opApiKey.isBlank() && opClientKey.isBlank() && ncToken.isBlank() && privateKey.isBlank() && password.isEmpty()

    private fun applySettings(s: BackupSettings) {
        host = s.host; sshPort = s.sshPort.ifEmpty { "22" }; user = s.user.ifEmpty { "root" }; useKey = s.useKey
        password = s.password; privateKey = s.privateKey; passphrase = s.passphrase
        if (s.zone.isNotEmpty()) zone = s.zone
        if (s.subdomain.isNotEmpty()) subdomain = s.subdomain
        if (s.ttl.isNotEmpty()) ttl = s.ttl
        email = s.email
        if (s.masquerade.isNotEmpty()) masquerade = s.masquerade
        ncUser = s.ncUser; ncToken = s.ncToken; opApiKey = s.opApiKey; opClientKey = s.opClientKey
        DefaultKeys.decode(s.defaultKeys).takeIf { it.isNotEmpty() }?.let { add -> setDefaultKeys(DefaultKeys.merge(defaultKeys, add)) }
        app.hy2setup.hwkey.HwKey.listFromJson(s.hwKey).filter { k -> hwKeys.none { it.fingerprint == k.fingerprint } }.takeIf { it.isNotEmpty() }?.let { add ->
            hwKeys.addAll(add)
            if (hwSelected.isEmpty()) hwSelected = add.first().fingerprint
            saveHwKeys()   // вход по ним на этом устройстве ещё не проверяли
        }
        for (srv in s.servers) {
            val updated = ServerProfiles.upsert(profiles, srv.profile)
            profiles.clear(); profiles.addAll(updated)
            secrets.put(secretKey(srv.profile, "password"), srv.password)
            secrets.put(secretKey(srv.profile, "privateKey"), srv.privateKey)
            secrets.put(secretKey(srv.profile, "passphrase"), srv.passphrase)
        }
        prefs.edit().putString("profiles", ServerProfiles.encode(profiles)).apply()
        for (srv in s.vpsServers) {
            val updated = ServerProfiles.upsert(vpsServers, srv.profile)
            vpsServers.clear(); vpsServers.addAll(updated)
            secrets.put(vpsSecret(srv.profile, "password"), srv.password)
            secrets.put(vpsSecret(srv.profile, "privateKey"), srv.privateKey)
            secrets.put(vpsSecret(srv.profile, "passphrase"), srv.passphrase)
            prefs.edit().putString("vpsDomain:${srv.profile.id}", srv.domain).putBoolean("vpsSelf:${srv.profile.id}", srv.self).apply()
        }
        prefs.edit().putString("vpsServers", ServerProfiles.encode(vpsServers)).apply()
        persist()
    }

    fun confirmSettings(apply: Boolean) {
        val s = pendingSettings ?: return
        pendingSettings = null
        if (apply) {
            applySettings(s)
            addLog(plusLog, "Настройки «Доступа к серверам» и ключи OneProvider и name.com заменены данными из копии.")
        }
    }

    fun plusApplyImport(p: StableProfile? = plusPendingImport) {
        plusPendingImport = null
        if (p != null) {
            savePlusProfile(p)
            selectStable(p.fqdn)
            plusShowLinks()
            addLog(plusLog, "Сервер ${p.fqdn} заменён данными из копии. Ссылки — ниже.")
        }
        plusPendingImport = pendingImports.removeFirstOrNull()
    }

    /** «Оставить текущий» в диалоге замены — переходим к следующему серверу из копии. */
    fun plusSkipImport() {
        plusPendingImport = pendingImports.removeFirstOrNull()
    }

    // =====================================================================
    //  Telegram-прокси (MTProto, mtg FakeTLS)
    // =====================================================================

    private fun tgSsh(): SshConfig? {
        securityProblem()?.let { tgError = it; return null }
        val ssh = buildSshConfig() ?: run { tgError = errorMessage ?: "Проверьте данные сервера в «Доступе к серверам» (Инструменты)."; return null }
        persist()
        return ssh
    }

    /** Telegram-прокси в общей установке «Доступа к серверам». */
    private fun planTg(): ProtoJob? {
        val port = tgPort.trim().toIntOrNull()
        if (port == null || port !in 1..65535) return null.also { fail("Порт Telegram-прокси должен быть числом 1–65535.") }
        val front = tgDomain.trim().lowercase()
        if (!ZONE.matches(front)) return null.also { fail("Домен маскировки Telegram — имя хоста, например www.microsoft.com.") }
        return ProtoJob { ssh, _ ->
            val markers = java.util.Collections.synchronizedList(mutableListOf<String>())
            val code = runner.runScript(ssh, Scripts.installTelegram(port, front)) { line ->
                if (Links.isMarker(line)) markers += line else postLog(log, line)
            }
            val link = Links.telegramFromMarkers(markers.toList(), ssh.host)
            if (code == 0 && link != null) {
                resultLinks.add(link)
                true
            } else {
                addLog(log, "Установка Telegram-прокси завершилась с ошибкой (код $code).")
                false
            }
        }
    }

    private fun tgSimple(script: String) {
        tgError = null
        val ssh = tgSsh() ?: return
        launchTask(tgLog) {
            addLog(tgLog, "Подключаюсь к ${ssh.host}:${ssh.port} …")
            val code = runner.runScript(ssh, script) { postLog(tgLog, it) }
            rememberProfile(ssh)
            if (code != 0) addLog(tgLog, "Завершено с кодом $code.")
        }
    }

    /** Удаляет сохранённые пароли, SSH-ключ и токен с телефона. */
    fun forgetSecrets() {
        // постоянный профиль «Комбайна+» не трогаем: иначе изменятся ссылки у всех клиентов
        val profiles = secrets.get(STABLE_PROFILES)
        secrets.clear()
        if (profiles.isNotEmpty()) secrets.put(STABLE_PROFILES, profiles)
        hwKeys.clear(); hwSelected = ""; hwVerifiedSet = emptySet(); hwLogin = false
        saveHwKeys()
        password = ""
        privateKey = ""
        passphrase = ""
        vpsPassword = ""; vpsKey = ""; vpsPassphrase = ""
        ncToken = ""
        opApiKey = ""
        opClientKey = ""
        log.add("Сохранённые пароли, SSH-ключ, токен name.com и ключи OneProvider удалены с ${Device.GEN}.")
        if (stableProfiles.isNotEmpty()) log.add("Серверы с постоянными ссылками на Главной сохранены (убрать их можно там же).")
    }

    // =====================================================================
    //  Внутреннее
    // =====================================================================

    /** Выполняет задачу, перехватывает ошибки и пишет их в журнал [target]. */
    private fun launchTask(target: SnapshotStateList<String>, clear: Boolean = true, block: suspend () -> Unit) {
        if (running) return
        running = true
        if (clear) target.clear()
        if (target === plusLog) plusAccessProblem = false
        errorMessage = null
        dnsError = null
        opError = null
        tgError = null
        scope.launch {
            try {
                block()
            } catch (e: SshException) {
                addLog(target, "ОШИБКА: ${e.message}")
                if (target === plusLog && plusProfile != null && !plusNew) {
                    plusAccessProblem = true
                    addLog(target, "Если неверны IP, порт, пользователь или пароль — исправьте их: «Инструменты → Доступ по SSH» → «Применить».")
                }
            } catch (e: NameComException) {
                addLog(target, "ОШИБКА: ${e.message}")
            } catch (e: OneProviderException) {
                addLog(target, "ОШИБКА: ${e.message}")
            } catch (e: Exception) {
                addLog(target, "ОШИБКА: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running = false
                vpsMode = false
            }
        }
    }

    /** Из потока ввода-вывода: добавить строку журнала в главном потоке. */
    private fun postLog(target: SnapshotStateList<String>, line: String) {
        scope.launch { target.add(line) }
    }

    private fun addLog(target: SnapshotStateList<String>, line: String) {
        target.add(line)
    }

    private fun fail(msg: String) {
        errorMessage = msg
    }

    private fun failDns(msg: String) {
        dnsError = msg
        errorMessage = msg
    }

    private fun sameFqdn(r: DnsRecord, fqdn: String) = r.fqdn.trimEnd('.').lowercase() == fqdn

    private suspend fun resolveIpv4(host: String): String? = withContext(Dispatchers.IO) {
        if (IPV4.matches(host)) return@withContext host
        try {
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun buildDnsPlan(withSubdomain: Boolean, forDnsTab: Boolean = false): DnsPlan? {
        val report: (String) -> Unit = if (forDnsTab) ::failDns else ::fail
        val u = ncUser.trim()
        if (u.isEmpty()) { report("Введите логин name.com (имя пользователя аккаунта)."); return null }
        if (ncToken.isBlank()) { report("Введите API-токен name.com."); return null }
        val z = zone.trim().lowercase()
        if (!ZONE.matches(z)) { report("Укажите домен (зону) в виде example.com."); return null }
        var sub = ""
        var ttlNum = 300
        if (withSubdomain) {
            sub = subdomain.trim().lowercase()
            if (sub.isNotEmpty() && !SUBDOMAIN.matches(sub)) {
                report("Поддомен: латиница, цифры, точка, дефис, подчёркивание (например, vpn).")
                return null
            }
            ttlNum = ttl.trim().toIntOrNull() ?: 0
            if (ttlNum !in 300..86400) { report("TTL должен быть числом от 300 до 86400 секунд."); return null }
        }
        return DnsPlan(u, ncToken.trim(), z, sub, ttlNum)
    }

    private fun buildSshConfig(allowHw: Boolean = true): SshConfig? {
        if (vpsMode) return vpsSshConfig()
        if (allowHw && hwLogin) hwKey?.let { k -> return buildSshConfig(allowHw = false, needSecret = false)?.copy(privateKey = "", hwKey = k) }
        return buildSshConfig(allowHw = false, needSecret = true)
    }

    private fun buildSshConfig(allowHw: Boolean, needSecret: Boolean): SshConfig? {
        val h = host.trim()
        if (h.isEmpty() || !Regex("^[A-Za-z0-9.:\\-\\[\\]]+$").matches(h)) {
            fail("Укажите IP-адрес или домен сервера.")
            return null
        }
        val p = sshPort.toIntOrNull()
        if (p == null || p !in 1..65535) {
            fail("SSH-порт должен быть числом 1–65535.")
            return null
        }
        val u = user.trim()
        if (u.isEmpty()) {
            fail("Укажите имя пользователя.")
            return null
        }
        if (needSecret && useKey && privateKey.isBlank()) {
            fail("Вставьте приватный ключ или выберите файл.")
            return null
        }
        if (needSecret && !useKey && password.isEmpty()) {
            fail("Введите пароль.")
            return null
        }
        if (useKey && u != "root" && password.isEmpty()) {
            fail("Пользователь «$u» не root: введите пароль для sudo (под полем ключа).")
            return null
        }
        return SshConfig(
            host = h, port = p, user = u,
            password = password,
            privateKey = if (useKey) privateKey else "",
            keyPassphrase = passphrase,
        )
    }

    /** Сохраняет все поля: обычные — в настройки, секреты — в зашифрованное хранилище. */
    fun persist() {
        if (accessTarget.isEmpty()) {
            prefs.edit().putString("host", host.trim()).putString("sshPort", sshPort.trim()).putString("user", user.trim()).putBoolean("useKey", useKey).apply()
        }
        // поля «Доступа по SSH» — общие, только когда правится «Другой сервер»; сервер Главной сохраняет «Применить»
        val own = accessTarget.isEmpty()
        if (own) {
            secrets.put("password", password)
            secrets.put("privateKey", privateKey)
            secrets.put("passphrase", passphrase)
        }
        secrets.put("ncToken", ncToken.trim())
        secrets.put("opApiKey", opApiKey.trim())
        secrets.put("opClientKey", opClientKey.trim())
        prefs.edit()
            .putBoolean("harden", hardenBeforeInstall)
            .putBoolean("plusSecure", plusSecure)
            .putBoolean("plusHy2", plusHy2).putBoolean("plusVless", plusVless).putBoolean("plusTg", plusTg)
            .putBoolean("plusAwg", plusAwg)
            .putBoolean("plusXhttp", plusXhttp)
            .putBoolean("plusTrailers", plusTrailers)
            .putBoolean("vlessSelf", vlessSelf)
            .putString("tgPort", tgPort.trim()).putString("tgDomain", tgDomain.trim())
            .putString("hyPort", hyPort.trim()).putString("protocol", protocol.name)
            .putString("vlessPort", vlessPort.trim())
            .putString("awgPort", awgPort.trim())
            .putBoolean("awgTrailers", awgTrailers)
            .putString("opLoc", opLocationId).putString("opRegion", opRegion).putString("opSize", opSizeId).putString("opTpl", opTemplateId).putString("realitySni", realitySni.trim()).putString("domain", domain.trim()).putString("email", email.trim())
            .putString("sni", sni.trim()).putString("masq", masquerade.trim())
            .putBoolean("useDns", useDns).putString("ncUser", ncUser.trim()).putString("zone", zone.trim())
            .putString("sub", subdomain.trim()).putString("ttl", ttl.trim())
            .apply()
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val SAFE_NAME = Regex("^[A-Za-z0-9._@+-]*$")
        private val ZONE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
        private val SUBDOMAIN = Regex("^[a-z0-9_]([a-z0-9._-]*[a-z0-9_])?$")
        private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")
        private val RECORD_HOST = Regex("^(\\*|\\*\\.[a-z0-9_.-]+|[a-z0-9_]([a-z0-9._-]*[a-z0-9_])?)$")
        private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
    }
}
