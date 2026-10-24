package app.hy2setup.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Основа модели экрана: на ПК — область корутин в потоке интерфейса (Swing), живёт, пока открыто окно. */
abstract class BaseViewModel {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
