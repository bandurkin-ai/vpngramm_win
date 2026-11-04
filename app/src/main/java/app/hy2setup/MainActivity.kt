package app.hy2setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.hy2setup.ui.AppRoot
import app.hy2setup.ui.Hy2Theme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { viewModelFactory { initializer { MainViewModel(application) } } }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Сохраняем введённые данные, когда приложение сворачивают или закрывают. */
    override fun onStop() {
        super.onStop()
        vm.persist()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // отчёт о прошлом падении — показать, чтобы его можно было прислать
        vm.crashReport = CrashReport.take(this)
        setContent { Hy2Theme { AppRoot(vm) } }
        runCatching { Notifications.ensureChannels(this) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // долгая работа — уведомление в шторке, чтобы Android не обрывал её в свёрнутом приложении
        lifecycleScope.launch {
            snapshotFlow { if (vm.running) vm.jobText else null }.distinctUntilChanged().collect { text ->
                runCatching { if (text != null) JobService.update(this@MainActivity, text) else JobService.stop(this@MainActivity) }
            }
        }
        handle(intent)
    }

    override fun onPause() {
        super.onPause()
        if (app.hy2setup.platform.NfcHost.activity === this) app.hy2setup.platform.NfcHost.activity = null
    }

    override fun onResume() {
        super.onResume()
        app.hy2setup.platform.NfcHost.activity = this
        vm.refreshMonitor()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(Notifications.EXTRA_FQDN)?.let { vm.openFromNotification(it) }
    }
}
