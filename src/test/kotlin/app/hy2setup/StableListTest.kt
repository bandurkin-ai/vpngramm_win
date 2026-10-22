package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Test

/** Несколько серверов с постоянными ссылками на Главной. */
class StableListTest {
    init { System.setProperty("vpngramm.home", java.nio.file.Files.createTempDirectory("vpngramm-test").toString()) }

    @Test
    fun selectingSwitchesProfileAndTargetsExcludeBusyServers() {
        val vm = MainViewModel()
        val a = StableProfile.create("example.org", "a", "", "https://www.bing.com/").copy(vps = ProfileVps("1", "203.0.113.1", "a"))
        val b = StableProfile.create("example.org", "b", "", "https://www.bing.com/").copy(vps = ProfileVps("2", "203.0.113.2", "b"))
        vm.plusProfile = a
        vm.plusProfile = b
        assertEquals("b.example.org", vm.plusProfile?.fqdn)
        vm.selectStable("a.example.org")
        assertEquals(a, vm.plusProfile)
        assertEquals(2, vm.stableProfiles.size)
        vm.combineServers += CombineServer("2", "203.0.113.2", "b.example.org")
        vm.combineServers += CombineServer("9", "203.0.113.9", "old.example.org")
        // сервер профиля b занят — переносить на него a нельзя
        assertEquals(listOf("9"), vm.plusTargets.map { it.vpsId })
        // общий IP: VPS профиля b используется ещё и c — удалять его нельзя
        val c = StableProfile.create("example.org", "c", "", "https://www.bing.com/").copy(vps = ProfileVps("", "203.0.113.2", ""))
        vm.plusProfile = c
        vm.selectStable("a.example.org")
        assertEquals(true, vm.vpsSharedWithOthers(b))
        assertEquals(false, vm.vpsSharedWithOthers(a))
        // удаление невыбранного сервера не меняет выбор, выбранного — переключает на оставшийся
        vm.removeFromList("c.example.org")
        assertEquals("a.example.org", vm.plusProfile?.fqdn)
        vm.removeFromList("a.example.org")
        assertEquals("b.example.org", vm.plusProfile?.fqdn)
        assertEquals(listOf("b.example.org"), vm.stableProfiles.map { it.fqdn })
        // сохранение на диск зашифровано Android Keystore — в Robolectric его нет, проверяется в ProfileImportTest
    }
}
