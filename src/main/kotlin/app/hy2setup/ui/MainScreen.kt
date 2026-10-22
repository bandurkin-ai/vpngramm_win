package app.hy2setup.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hy2setup.MainViewModel
import kotlinx.coroutines.launch

private data class Dest(val title: String, val icon: ImageVector)

private val DESTS = listOf(
    Dest("Главная", AppIcons.Home),
    Dest("Сервер", AppIcons.Dns),
    Dest("Облако", AppIcons.Cloud),
    Dest("Инструменты", AppIcons.Build),
)

/** Корень интерфейса: нижняя навигация на 4 раздела, snackbar и общий диалог «доверять серверу?». */
@Composable
fun AppRoot(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snack: (String) -> Unit = remember { { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } } }
    CompositionLocalProvider(LocalSnack provides snack) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    DESTS.forEachIndexed { i, d ->
                        NavigationBarItem(
                            selected = vm.selectedTab == i,
                            onClick = { vm.selectedTab = i },
                            icon = { Icon(d.icon, null) },
                            label = { Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            },
        ) { inner ->
            Box(Modifier.padding(inner).consumeWindowInsets(inner)) {
                AnimatedContent(vm.selectedTab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { tab ->
                    when (tab) {
                        0 -> HomeScreen(vm)
                        1 -> ServerScreen(vm)
                        2 -> CloudScreen(vm)
                        else -> ToolsScreen(vm)
                    }
                }
            }
        }
        HostKeyDialog(vm)
    CrashDialog(vm)
    }
}

/**
 * Каркас экрана раздела: логотип vpngramm по центру верхней панели, заголовок раздела в начале
 * прокручиваемого содержимого, полоса прогресса во время работы и место для главной кнопки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenFrame(
    title: String,
    running: Boolean,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val behavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text("vpngramm", fontFamily = LogoFont, fontSize = 32.sp, maxLines = 1) },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    scrollBehavior = behavior,
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            }
        },
        floatingActionButton = floating,
        bottomBar = bottomBar,
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            content()
        }
    }
}

@Composable
private fun HostKeyDialog(vm: MainViewModel) {
    vm.hostKeyPrompt?.let { msg ->
        ConfirmDialog(
            title = "Новый сервер",
            text = "Сервер подключается впервые. Сверьте отпечаток с тем, что показывает панель хостинга, " +
                "и доверяйте, только если он совпадает.\n\n$msg",
            confirm = "Доверять",
            onConfirm = { vm.answerHostKey(true) },
            onDismiss = { vm.answerHostKey(false) },
        )
    }
}

/** Отчёт о прошлом падении: его можно скопировать или отправить разработчику (в нём нет ключей и паролей). */
@Composable
private fun CrashDialog(vm: MainViewModel) {
    val report = vm.crashReport ?: return
    val snack = LocalSnack.current
    val copy = rememberCopier()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.crashReport = null },
        icon = { androidx.compose.material3.Icon(AppIcons.Warning, null) },
        title = { androidx.compose.material3.Text("Приложение упало в прошлый раз") },
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                androidx.compose.material3.Text("Пришлите этот отчёт — по нему видно, где ошибка. В нём только место ошибки и версия Windows, без ключей и паролей.")
                androidx.compose.material3.Text(
                    report.take(1500), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ copy("Отчёт", report); vm.crashReport = null }) { androidx.compose.material3.Text("Скопировать") } },
        dismissButton = { androidx.compose.material3.TextButton({ saveToFile(report, "vpngramm-crash.txt", snack); vm.crashReport = null }) { androidx.compose.material3.Text("В файл") } },
    )
}
