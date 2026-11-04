package app.hy2setup.hwkey

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * Ключ SSH на аппаратном токене (Рутокен MFA, YubiKey и другие FIDO2/U2F): OpenSSH sk-ecdsa (P-256) или sk-ed25519.
 * Закрытый ключ не покидает токен; здесь — открытый ключ и key handle (credential id), по которому токен находит ключ.
 */
class HwKey(
    /** Открытый ключ: P-256 — 65 байт (0x04 | X | Y), Ed25519 — 32 байта. */
    val publicKey: ByteArray,
    val keyHandle: ByteArray,
    val application: String = SkKey.APPLICATION,
    /** Алгоритм COSE: -7 — ES256 (P-256), -8 — EdDSA (Ed25519). */
    val alg: Int = Ctap2.ES256,
    /** Резидентный ключ: хранится в самом токене и загружается с него на новом телефоне. */
    val resident: Boolean = false,
    /** Чей ключ — название токена («YubiKey 5C Nano», «Rutoken MFA»). */
    val name: String = "",
) {
    val ed25519: Boolean get() = alg == Ctap2.EDDSA
    val type: String get() = if (ed25519) SkKey.TYPE_ED25519 else SkKey.TYPE

    /** Строка для ~/.ssh/authorized_keys. */
    fun authorizedKeysLine(comment: String = "vpngramm-hwkey"): String =
        type + " " + Base64.getEncoder().encodeToString(SkKey.publicBlob(this)) + " " + comment

    /** Отпечаток SHA256 (как у ssh-keygen -l) — заодно идентификатор ключа в приложении. */
    val fingerprint: String
        get() = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(SkKey.publicBlob(this)))

    /** «YubiKey 5C Nano · ed25519 · резидентный». */
    val title: String
        get() = listOf(name.ifEmpty { "Аппаратный ключ" }, if (ed25519) "ed25519" else "ecdsa P-256", if (resident) "резидентный" else "нерезидентный")
            .joinToString(" · ")

    fun toJson(): String = JSONObject()
        .put("pub", Base64.getEncoder().encodeToString(publicKey))
        .put("kh", Base64.getEncoder().encodeToString(keyHandle))
        .put("app", application).put("alg", alg).put("rk", resident).put("name", name).toString()

    override fun equals(other: Any?) = other is HwKey && publicKey.contentEquals(other.publicKey) &&
        keyHandle.contentEquals(other.keyHandle) && application == other.application && alg == other.alg

    override fun hashCode() = publicKey.contentHashCode() * 31 + keyHandle.contentHashCode()

    companion object {
        fun fromJson(s: String): HwKey? = runCatching {
            val o = JSONObject(s)
            HwKey(
                Base64.getDecoder().decode(o.getString("pub")), Base64.getDecoder().decode(o.getString("kh")),
                o.optString("app", SkKey.APPLICATION), o.optInt("alg", Ctap2.ES256), o.optBoolean("rk", false), o.optString("name"),
            )
        }.getOrNull()?.takeIf { it.publicKey.size == (if (it.ed25519) 32 else 65) && it.keyHandle.isNotEmpty() }

        /** Список ключей (JSON-массив); старая запись из 3.8 — один ключ. */
        fun listFromJson(s: String): List<HwKey> {
            if (s.isBlank()) return emptyList()
            return runCatching {
                val a = org.json.JSONArray(s)
                (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it).toString()) }
            }.getOrElse { listOfNotNull(fromJson(s)) }
        }

        fun listToJson(keys: List<HwKey>): String = org.json.JSONArray().apply { keys.forEach { put(JSONObject(it.toJson())) } }.toString()
    }
}

/** Форматы OpenSSH для ключей токенов (PROTOCOL.u2f в исходниках OpenSSH). */
object SkKey {
    const val TYPE = "sk-ecdsa-sha2-nistp256@openssh.com"
    const val TYPE_ED25519 = "sk-ssh-ed25519@openssh.com"
    /** «Приложение» FIDO, которое OpenSSH использует по умолчанию. */
    const val APPLICATION = "ssh:"

    /** ecdsa: string type, string "nistp256", string Q, string app; ed25519: string type, string pk, string app. */
    fun publicBlob(k: HwKey): ByteArray =
        if (k.ed25519) ssh { str(TYPE_ED25519); str(k.publicKey); str(k.application) }
        else ssh { str(TYPE); str("nistp256"); str(k.publicKey); str(k.application) }

    /** Подпись ecdsa для SSH: string type, string (mpint r, mpint s), byte flags, uint32 counter; [der] — подпись токена. */
    fun signatureBlob(der: ByteArray, flags: Int, counter: Long): ByteArray {
        val (r, s) = derToRs(der)
        val inner = ssh { mpint(r); mpint(s) }
        return ssh { str(TYPE); str(inner); byte(flags); uint32(counter) }
    }

    /** Подпись ed25519 для SSH: string type, string sig(64), byte flags, uint32 counter. */
    fun signatureBlobEd25519(sig: ByteArray, flags: Int, counter: Long): ByteArray =
        ssh { str(TYPE_ED25519); str(sig); byte(flags); uint32(counter) }

    /** Разбор DER-подписи ECDSA в (r, s). */
    fun derToRs(der: ByteArray): Pair<BigInteger, BigInteger> {
        var i = 0
        fun len(): Int {
            val b = der[i++].toInt() and 0xff
            if (b < 0x80) return b
            var n = 0
            repeat(b and 0x7f) { n = (n shl 8) or (der[i++].toInt() and 0xff) }
            return n
        }
        fun integer(): BigInteger {
            require(der[i++].toInt() == 0x02) { "ожидалось INTEGER" }
            val l = len()
            return BigInteger(1, der.copyOfRange(i, i + l)).also { i += l }
        }
        require(der[i++].toInt() == 0x30) { "ожидалась SEQUENCE" }
        len()
        return integer() to integer()
    }

    private class W(val d: DataOutputStream) {
        fun str(s: String) = str(s.toByteArray(Charsets.UTF_8))
        fun str(b: ByteArray) { d.writeInt(b.size); d.write(b) }
        fun mpint(v: BigInteger) = str(v.toByteArray())
        fun byte(v: Int) = d.writeByte(v)
        fun uint32(v: Long) = d.writeInt(v.toInt())
    }

    private fun ssh(block: W.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        W(DataOutputStream(out)).block()
        return out.toByteArray()
    }
}
