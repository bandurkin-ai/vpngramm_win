package app.hy2setup.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope

/** Основа модели экрана: на Android — ViewModel с viewModelScope. */
abstract class BaseViewModel : ViewModel() {
    val scope: CoroutineScope get() = viewModelScope
}
