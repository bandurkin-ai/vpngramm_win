package app.hy2setup.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.hy2setup.MainViewModel
import app.hy2setup.PlusStep
import app.hy2setup.ProfileVps
import app.hy2setup.ShareLink
import app.hy2setup.StableProfile
import app.hy2setup.StepStatus
import app.hy2setup.TAB_CLOUD
import app.hy2setup.TAB_HOME
import app.hy2setup.TAB_TOOLS
import app.hy2setup.platform.DesktopPlatform
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Снимки вкладок Windows-версии (без окна, через Skia). PNG — в desktop/build/screens/. */
class DesktopScreensTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun vm(tab: Int, withProfile: Boolean = true) = MainViewModel(DesktopPlatform(tmp.newFolder())).apply {
        selectedTab = tab
        if (withProfile) {
            plusProfile = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/")
                .copy(vps = ProfileVps("123", "203.0.113.10", "vpn"))
            plusLinks += ShareLink("VLESS", "vless://11111111-2222-3333-4444-555555555555@vpn.example.org:443?security=reality#vpn")
            plusLog += listOf("== Проверка сервера vpn.example.org ==", "OK: Hysteria2 работает", "Готово.")
            PlusStep.entries.forEach { plusSteps[it] = StepStatus.DONE }
        }
        host = "203.0.113.10"
    }

    private fun shot(name: String, tab: Int, dark: Boolean = false, withProfile: Boolean = true) {
        val model = vm(tab, withProfile)
        val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
        val file = File(dir, "$name-${if (dark) "dark" else "light"}.png")
        ImageComposeScene(1080, 3200, Density(2f)) { Hy2Theme(dark = dark, dynamic = false) { AppRoot(model) } }.use { scene ->
            scene.render(0)
            scene.render(1_000_000_000L)
            val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            file.writeBytes(png)
        }
        assertTrue(file.length() > 10_000)
    }

    @Test fun home() = shot("1-home", TAB_HOME)
    @Test fun homeDark() = shot("1-home", TAB_HOME, dark = true)
    @Test fun homeEmpty() = shot("1-home-empty", TAB_HOME, withProfile = false)
    @Test fun cloud() = shot("2-cloud", TAB_CLOUD)
    @Test fun tools() = shot("3-tools", TAB_TOOLS)
}
