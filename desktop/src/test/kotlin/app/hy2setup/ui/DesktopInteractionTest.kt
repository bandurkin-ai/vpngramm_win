package app.hy2setup.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import app.hy2setup.MainViewModel
import app.hy2setup.ProfileVps
import app.hy2setup.StableProfile
import app.hy2setup.TAB_HOME
import app.hy2setup.TAB_TOOLS
import app.hy2setup.platform.DesktopPlatform
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/** Нажатия в интерфейсе Windows-версии: ссылки сервера, QR-код, вкладки. */
@OptIn(ExperimentalTestApi::class)
class DesktopInteractionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun model(tab: Int) = MainViewModel(DesktopPlatform(tmp.newFolder())).apply {
        selectedTab = tab
        plusProfile = StableProfile.create("example.org", "vpn", "", "https://www.bing.com/").copy(vps = ProfileVps("123", "203.0.113.10", "vpn"))
    }

    private fun save(img: androidx.compose.ui.graphics.ImageBitmap, name: String) {
        val dir = File(System.getProperty("screens.dir") ?: "build/screens").apply { mkdirs() }
        ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png"))
    }

    /** Главная → «Ссылки» → ссылка VLESS: шторка с QR-кодом и кнопками. */
    @Test
    fun linksAndQr() = runDesktopComposeUiTest(540, 1000) {
        val vm = model(TAB_HOME)
        setContent { Hy2Theme(dark = false, dynamic = false) { AppRoot(vm) } }
        onNodeWithText("Ссылки").performClick()
        waitForIdle()
        onNodeWithText("Ссылки vpn.example.org").assertExists()
        onNodeWithText("Подписка Happ").assertExists()
        onNodeWithText("Подписка Happ").performClick()
        waitForIdle()
        onNodeWithContentDescription("QR-код ссылки Подписка Happ").assertExists()
        onNodeWithText("Поделиться").assertExists()
        save(onAllNodes(isRoot())[0].captureToImage(), "5-link-qr")
    }

    /** В Windows-версии нет вкладки VPN: в нижнем меню четыре раздела. */
    @Test
    fun noVpnTab() = runDesktopComposeUiTest(540, 1000) {
        val vm = model(TAB_HOME)
        setContent { Hy2Theme(dark = false, dynamic = false) { AppRoot(vm) } }
        onNodeWithText("VPN").assertDoesNotExist()
        for (tab in listOf("Главная", "VPS/DNS", "Инструменты")) {
            org.junit.Assert.assertTrue(tab, onAllNodesWithText(tab).fetchSemanticsNodes().isNotEmpty())
        }
        onNodeWithText("Инструменты").performClick()
        waitForIdle()
        onNodeWithText("Доступ по SSH").assertExists()
        onNodeWithText("Аппаратные ключи").assertDoesNotExist()
        onNodeWithText("Забыть пароли и токены").assertExists()
        save(onAllNodes(isRoot())[0].captureToImage(), "5-tools-no-monitor")
    }

    @Test
    fun toolsSayComputer() = runDesktopComposeUiTest(540, 1000) {
        val vm = model(TAB_TOOLS)
        setContent { Hy2Theme(dark = false, dynamic = false) { AppRoot(vm) } }
        onNodeWithText("Удалить с компьютера", substring = true).assertExists()
        // блока «Контроль серверов» в Windows-версии нет
        onNodeWithText("Контроль серверов").assertDoesNotExist()
        onNodeWithText("vpngramm v4.0", substring = true).assertExists()
    }
}
