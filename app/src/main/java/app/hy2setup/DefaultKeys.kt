package app.hy2setup

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * Открытый ключ SSH из «Ключей для всех серверов» — строка authorized_keys: тип, base64-блоб, комментарий.
 * Это открытые ключи (не секреты): приложение дописывает их в authorized_keys создаваемых серверов.
 */
data class PublicKeyLine(val type: String, val blob: String, val comment: String) {
    /** Строка для authorized_keys. */
    val line: String get() = listOf(type, blob, comment).filter { it.isNotEmpty() }.joinToString(" ")

    /** Ключ аппаратного токена (FIDO): серверу нужен OpenSSH 8.2+. */
    val hardware: Boolean get() = type.startsWith("sk-")

    /** Для ключей токена — application (например, ssh:myname). */
    val application: String
        get() = if (!hardware) "" else runCatching {
            val b = ByteBuffer.wrap(Base64.getDecoder().decode(blob))
            repeat(if (type.startsWith("sk-ecdsa")) 3 else 2) { b.position(b.position() + 4 + b.int) }
            String(ByteArray(b.int).also { b.get(it) })
        }.getOrDefault("")

    val fingerprint: String
        get() = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(blob)))

    /** Короткое название типа для списка. */
    val kind: String
        get() = when (type) {
            "sk-ssh-ed25519@openssh.com" -> "ed25519 · токен"
            "sk-ecdsa-sha2-nistp256@openssh.com" -> "ecdsa · токен"
            "ssh-ed25519" -> "ed25519"
            "ssh-rsa" -> "RSA"
            else -> type.removePrefix("ecdsa-sha2-").let { "ecdsa $it" }
        } + application.takeIf { it.isNotEmpty() && it != "ssh:" }?.let { " · $it" }.orEmpty()

    /** Подпись: комментарий или отпечаток. */
    val title: String get() = comment.ifBlank { fingerprint.take(20) + "…" }
}

object DefaultKeys {
    val TYPES = setOf(
        "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
        "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com",
    )

    /** Разбор одной строки; null — не ключ (тип не тот, base64 испорчен, тип внутри блоба другой). */
    fun parse(text: String): PublicKeyLine? {
        val parts = text.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 2 || parts[0] !in TYPES) return null
        val raw = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return null
        val inner = runCatching {
            val b = ByteBuffer.wrap(raw)
            String(ByteArray(b.int).also { b.get(it) })
        }.getOrNull()
        if (inner != parts[0]) return null
        // комментарий — без кавычек и переводов строк, чтобы безопасно попасть в скрипт
        val comment = parts.getOrElse(2) { "" }.replace(Regex("[^\\p{L}\\p{N} @._:+=-]"), "").trim().take(80)
        return PublicKeyLine(parts[0], parts[1], comment)
    }

    /** Разбор файла .pub или вставленного текста: строки-ключи и число строк, которые ключом не являются. */
    fun parseAll(text: String): Pair<List<PublicKeyLine>, Int> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val keys = lines.mapNotNull { parse(it) }
        return keys.distinctBy { it.blob } to (lines.size - keys.size)
    }

    /** Добавляет ключи к списку без повторов (по блобу). */
    fun merge(list: List<PublicKeyLine>, add: List<PublicKeyLine>): List<PublicKeyLine> =
        (list + add).distinctBy { it.blob }

    fun encode(list: List<PublicKeyLine>): String = org.json.JSONArray(list.map { it.line }).toString()

    fun decode(json: String?): List<PublicKeyLine> = runCatching {
        val a = org.json.JSONArray(json.orEmpty().ifBlank { "[]" })
        (0 until a.length()).mapNotNull { parse(a.getString(it)) }.distinctBy { it.blob }
    }.getOrDefault(emptyList())
}
