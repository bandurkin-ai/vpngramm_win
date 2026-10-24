package app.hy2setup

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Запуск MainActivity целиком (разрешения, служба, WorkManager, экраны) на разных данных. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StartupTest {
    private fun launch() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { }
            s.recreate()
            s.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            s.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
    }

    @Test fun cleanInstall() = launch()

    @Test fun withPrefsFrom2() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        ctx.getSharedPreferences("app", 0).edit().putInt("monitorHours", 3).commit()
        launch()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReportTest {
    @Test
    fun savedReportIsShownOnce() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        CrashReport.save(ctx, "main", IllegalStateException("тест"))
        val r = CrashReport.take(ctx)
        org.junit.Assert.assertTrue(r!!.contains("IllegalStateException: тест"))
        org.junit.Assert.assertNull(CrashReport.take(ctx))
    }
}
