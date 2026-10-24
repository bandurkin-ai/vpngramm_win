package app.hy2setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Фоновый контроль серверов (только Android). */
class MonitorWorkerTest {
    @Test
    fun monitorAlertsOnlyAfterTwoFailuresWithoutVpn() {
        assertFalse(MonitorWorker.decideAlert(ok = false, fails = 1, vpn = false))
        assertTrue(MonitorWorker.decideAlert(ok = false, fails = 2, vpn = false))
        assertFalse(MonitorWorker.decideAlert(ok = false, fails = 3, vpn = false))
        assertTrue(MonitorWorker.decideAlert(ok = false, fails = 8, vpn = false))
        assertFalse(MonitorWorker.decideAlert(ok = false, fails = 2, vpn = true))
        assertFalse(MonitorWorker.decideAlert(ok = true, fails = 0, vpn = false))
    }
}
