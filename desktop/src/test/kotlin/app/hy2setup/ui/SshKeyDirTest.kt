package app.hy2setup.ui

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** «Выбрать файл ключа…» открывается в папке .ssh пользователя. */
class SshKeyDirTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun usesSshFolderWhenPresent() {
        val home = tmp.newFolder()
        File(home, ".ssh").mkdirs()
        assertEquals(File(home, ".ssh"), sshKeyDir(home.path))
    }

    @Test
    fun fallsBackToProfile() {
        val home = tmp.newFolder()
        assertEquals(File(home.path), sshKeyDir(home.path))
    }
}
