package app.hy2setup

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Папка данных приложения: %APPDATA%\vpngramm на Windows, ~/.vpngramm на остальных системах (для разработки). */
object AppDirs {
    val root: File by lazy {
        val override = System.getProperty("vpngramm.home")
        val dir = when {
            override != null -> File(override)
            else -> {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrBlank()) File(appData, "vpngramm") else File(System.getProperty("user.home"), ".vpngramm")
            }
        }
        dir.apply { mkdirs() }
    }

    fun file(name: String) = File(root, name)
}

/** Мелкие настройки: аналог SharedPreferences, хранятся в %APPDATA%\vpngramm\<name>.properties. */
class Prefs(name: String) {
    private val file = AppDirs.file("$name.properties")
    private val props = Properties().apply {
        if (file.exists()) runCatching { file.reader(StandardCharsets.UTF_8).use { load(it) } }
    }

    @Synchronized fun getString(key: String, def: String?): String? = props.getProperty(key, def)
    @Synchronized fun getBoolean(key: String, def: Boolean): Boolean = props.getProperty(key)?.toBooleanStrictOrNull() ?: def
    @Synchronized fun getInt(key: String, def: Int): Int = props.getProperty(key)?.toIntOrNull() ?: def

    fun edit() = Editor()

    inner class Editor {
        private val changes = LinkedHashMap<String, String?>()
        private var wipe = false
        fun putString(key: String, value: String?) = apply { changes[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        fun remove(key: String) = apply { changes[key] = null }
        fun clear() = apply { wipe = true; changes.clear() }

        fun apply() {
            synchronized(this@Prefs) {
                if (wipe) props.clear()
                for ((k, v) in changes) if (v == null) props.remove(k) else props.setProperty(k, v)
                save()
            }
        }
    }

    private fun save() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writer(StandardCharsets.UTF_8).use { props.store(it, null) }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
        }
    }
}

/** Системные диалоги выбора файла (AWT, на Windows — стандартные окна «Открыть»/«Сохранить как»). */
object FileDialogs {
    fun open(title: String = "Выберите файл"): File? = show(title, FileDialog.LOAD, null)

    fun save(suggestedName: String, title: String = "Сохранить как"): File? = show(title, FileDialog.SAVE, suggestedName)

    private fun show(title: String, mode: Int, name: String?): File? {
        val dialog = FileDialog(null as Frame?, title, mode)
        if (name != null) dialog.file = name
        dialog.isVisible = true
        val f = dialog.file ?: return null
        return File(dialog.directory, f)
    }
}
