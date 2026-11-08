package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Ключи для всех серверов»: разбор строк authorized_keys и скрипт установки. */
class DefaultKeysTest {
    // тестовые ключи токена (случайные; application ssh:myname и ssh:sk_2 — как у ssh-keygen -O application=…)
    private val yk5c = "sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29tAAAAICCk8FhAsU6IeB/jEusJXp65VKtjeiTegEYM72oNsR1VAAAACnNzaDpteW5hbWU= token-main"
    private val ykBackup = "sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2guY29tAAAAIPvXyZsLqQHOBp1eGq776pyrG2NfxCuVe75DpCFpWdePAAAACHNzaDpza18y token-backup"
    private val ed = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII9chrP5LZBDjmcPmsDN3/tUQVqV60vCSecK3Z8I2AyT laptop"

    @Test
    fun yubikeyKeysAreParsed() {
        val (keys, bad) = DefaultKeys.parseAll("$yk5c\n\n$ykBackup\n")
        assertEquals(0, bad)
        assertEquals(listOf("token-main", "token-backup"), keys.map { it.comment })
        assertEquals(listOf("ssh:myname", "ssh:sk_2"), keys.map { it.application })
        assertTrue(keys.all { it.hardware })
        assertEquals("ed25519 · токен · ssh:myname", keys[0].kind)
        assertEquals(yk5c, keys[0].line)
    }

    @Test
    fun garbageIsRejected() {
        assertNull(DefaultKeys.parse("hello world"))
        assertNull(DefaultKeys.parse("ssh-ed25519 !!!notbase64"))
        // тип в строке не совпадает с типом внутри блоба
        assertNull(DefaultKeys.parse("ssh-rsa " + ed.split(" ")[1]))
        assertNull(DefaultKeys.parse("-----BEGIN OPENSSH PRIVATE KEY-----"))
        val p = DefaultKeys.parse(ed)!!
        assertFalse(p.hardware)
        assertEquals("", p.application)
        // в комментарии не остаётся кавычек — строка безопасно уходит в скрипт
        assertEquals("evil x", DefaultKeys.parse(ed.substringBeforeLast(" ") + " evil' x")!!.comment)
    }

    @Test
    fun mergeAndStorageDropDuplicates() {
        val a = DefaultKeys.parseAll(yk5c).first
        val merged = DefaultKeys.merge(a, DefaultKeys.parseAll("$yk5c\n$ykBackup").first)
        assertEquals(2, merged.size)
        assertEquals(merged, DefaultKeys.decode(DefaultKeys.encode(merged)))
        assertEquals(emptyList<PublicKeyLine>(), DefaultKeys.decode("not json"))
    }

    @Test
    fun scriptAddsEachKeyOnceAndChecksOpenSshForTokens() {
        val s = Scripts.addAuthorizedKeys(listOf(yk5c, ykBackup, yk5c))
        assertEquals(1, Regex("echo '" + Regex.escape(yk5c) + "' >>").findAll(s).count())
        assertTrue(s.contains("echo '$ykBackup' >>"))
        assertTrue(s.contains("OpenSSH 8.2"))
        assertFalse(Scripts.addAuthorizedKeys(listOf(ed)).contains("OpenSSH 8.2"))
    }

    @Test
    fun listTravelsInBackup() {
        val json = DefaultKeys.encode(DefaultKeys.parseAll("$yk5c\n$ykBackup").first)
        val back = BackupSettings.fromJson(BackupSettings(defaultKeys = json).toJson())
        assertEquals(2, DefaultKeys.decode(back.defaultKeys).size)
        assertFalse(BackupSettings().toJson().has("defaultKeys"))
    }
}
