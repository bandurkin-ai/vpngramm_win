package app.hy2setup

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerProfilesTest {
    private val a = ServerProfile("stockholm.example.org", 22, "root", useKey = true)
    private val b = ServerProfile("203.0.113.10", 2222, "admin", useKey = false)

    @Test
    fun encodeDecodeRoundTrip() {
        assertEquals(listOf(a, b), ServerProfiles.decode(ServerProfiles.encode(listOf(a, b))))
    }

    @Test
    fun garbageOrEmptyGivesEmptyList() {
        assertEquals(emptyList<ServerProfile>(), ServerProfiles.decode(null))
        assertEquals(emptyList<ServerProfile>(), ServerProfiles.decode("not json"))
    }

    @Test
    fun upsertPutsNewestFirstWithoutDuplicates() {
        val list = ServerProfiles.upsert(listOf(a, b), b.copy(useKey = true))
        assertEquals(listOf(b.copy(useKey = true), a), list)
    }

    @Test
    fun sameHostDifferentUserIsSeparateEntry() {
        val list = ServerProfiles.upsert(listOf(a), a.copy(user = "deploy"))
        assertEquals(2, list.size)
    }
}
