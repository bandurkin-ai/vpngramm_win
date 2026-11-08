package app.hy2setup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hy2setup.LogSummary
import app.hy2setup.ShareLink
import app.hy2setup.StepStatus

/** Показ короткого сообщения внизу экрана (snackbar) из любого места интерфейса. */
val LocalSnack = compositionLocalOf<(String) -> Unit> { {} }

/** Копирует текст в буфер и показывает «Скопировано». */
@Composable
fun rememberCopier(): (label: String, text: String) -> Unit {
    val clip = rememberClipboard()
    val snack = LocalSnack.current
    return remember(clip, snack) {
        { label, text ->
            clip(label, text)
            snack("$label: скопировано")
        }
    }
}

// ---------------------------------------------------------------- карточки и поля

/** Карточка-раздел с иконкой и заголовком; сворачивается по нажатию на заголовок, чтобы экран был короче. */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    collapsible: Boolean = true,
    /** Все блоки по умолчанию закрыты. */
    startExpanded: Boolean = false,
    /** Вызывается при сворачивании и разворачивании (true — развернули). */
    onExpandChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(startExpanded) }
    Card(
        modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().let { if (collapsible) it.clickable { expanded = !expanded; onExpandChange?.invoke(expanded) } else it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (collapsible) {
                    Icon(
                        if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                        contentDescription = if (expanded) "Свернуть" else "Развернуть",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!collapsible || expanded) content()
        }
    }
}

/** Пояснение мелким шрифтом. */
@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Пояснение, раскрывающееся по «Подробнее» — экран не перегружен текстом. */
@Composable
fun MoreHint(short: String, long: String) {
    var open by rememberSaveable(short) { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Hint(if (open) "$short $long" else short)
        Text(
            if (open) "Скрыть" else "Подробнее",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 6.dp),
        )
    }
}

@Composable
fun AppField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    enabled: Boolean = true,
    lines: Int = 1,
    mono: Boolean = false,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = lines == 1,
        minLines = lines,
        maxLines = if (lines == 1) 1 else lines,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        textStyle = if (mono) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp) else LocalTextStyle.current,
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        supportingText = supporting?.let { { Text(it) } },
        trailingIcon = when {
            trailing != null -> trailing
            secret -> {
                {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(if (reveal) AppIcons.EyeOff else AppIcons.Eye, if (reveal) "Скрыть" else "Показать")
                    }
                }
            }
            else -> null
        },
    )
}

/** Строка-переключатель во всю ширину (касание по всей строке). */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, subtitle: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = enabled) { onChange(!checked) },
    )
}

/** Сегментированный выбор из нескольких вариантов (Material 3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/**
 * Поле с выпадающим списком-столбцом и галочками: можно отметить несколько вариантов. Список не закрывается
 * при выборе — закрывается касанием вне его.
 */
@Composable
fun <T> MultiPicker(label: String, options: List<Pair<T, String>>, selected: Set<T>, onToggle: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val text = options.filter { it.first in selected }.joinToString(", ") { it.second }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text, onValueChange = {}, readOnly = true, enabled = enabled, maxLines = 3,
            label = { Text(label) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            trailingIcon = { Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null) },
        )
        Box(Modifier.matchParentSize().clip(MaterialTheme.shapes.medium).clickable(enabled = enabled) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    leadingIcon = { Checkbox(checked = value in selected, onCheckedChange = null) },
                    onClick = { onToggle(value) },
                )
            }
        }
    }
}

/** Поле с выпадающим списком значений. */
@Composable
fun Picker(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, singleLine = true, enabled = enabled,
            label = { Text(label) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            trailingIcon = { Icon(AppIcons.ExpandMore, contentDescription = null) },
        )
        Box(Modifier.matchParentSize().clip(MaterialTheme.shapes.medium).clickable(enabled = enabled && options.isNotEmpty()) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 380.dp)) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); open = false }) }
        }
    }
}

@Composable
fun ErrorText(text: String?) {
    if (text == null) return
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(10.dp))
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Кнопка с иконкой над подписью — плитка быстрого действия. */
@Composable
fun ActionTile(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.heightIn(min = 76.dp),
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 6.dp).let { if (!enabled) it.background(Color.Transparent) else it },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- статус и результаты

data class StatusLook(val container: Color, val content: Color, val icon: ImageVector)

@Composable
fun lookOf(state: LogSummary.State): StatusLook {
    val s = LocalStatusColors.current
    val c = MaterialTheme.colorScheme
    return when (state) {
        LogSummary.State.OK -> StatusLook(s.successContainer, s.onSuccessContainer, AppIcons.Check)
        LogSummary.State.WARN -> StatusLook(s.warningContainer, s.onWarningContainer, AppIcons.Warning)
        LogSummary.State.ERROR -> StatusLook(c.errorContainer, c.onErrorContainer, AppIcons.Error)
        LogSummary.State.RUNNING -> StatusLook(c.primaryContainer, c.onPrimaryContainer, AppIcons.Bolt)
        LogSummary.State.INFO -> StatusLook(c.surfaceContainerHighest, c.onSurface, AppIcons.Info)
    }
}

/** Маленький «светофор»: итог последней операции. */
@Composable
fun StatusChip(state: LogSummary.State, text: String) {
    val look = lookOf(state)
    Row(
        Modifier.clip(CircleShape).background(look.container).padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(look.icon, null, Modifier.size(16.dp), tint = look.content)
        Spacer(Modifier.width(6.dp))
        Text(text, color = look.content, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Итог операции простыми словами + кнопка журнала + ссылки. Заменяет блоки «Расшифровка»/«Журнал»/«Ссылка». */
@Composable
fun ResultPanel(log: List<String>, running: Boolean, links: List<ShareLink>, title: String = "Результат", compact: Boolean = false) {
    if (log.isEmpty() && links.isEmpty()) return
    var showLog by remember { mutableStateOf(false) }
    // compact: итоги и шаги — в невысоком окне (вдвое ниже обычного) с пролистыванием; видны последние шаги
    val scroll = rememberScrollState()
    if (compact) LaunchedEffect(log.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    var openLink by remember { mutableStateOf<ShareLink?>(null) }
    val copy = rememberCopier()
    if (log.isNotEmpty()) {
        val summary = LogSummary.of(log, running)
        val look = lookOf(summary.state)
        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = look.container, contentColor = look.content),
            onClick = { showLog = true },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(look.icon, null)
                    Spacer(Modifier.width(10.dp))
                    Text(summary.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Column(
                    (if (compact) Modifier.heightIn(max = 128.dp).verticalScroll(scroll) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    summary.points.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    // краткий журнал: шаги и итоги без технического вывода
                    LogSummary.events(log, running, max = if (compact) 40 else 8).forEach { e ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                when (e.state) {
                                    LogSummary.State.OK -> "✓"; LogSummary.State.ERROR -> "✗"; LogSummary.State.WARN -> "!"
                                    LogSummary.State.RUNNING -> "…"; LogSummary.State.INFO -> "·"
                                },
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(18.dp),
                            )
                            Text(e.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // пока идёт работа — последняя строка: видно, что происходит прямо сейчас
                if (running) log.lastOrNull { it.isNotBlank() }?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { showLog = true }) {
                        Icon(AppIcons.Terminal, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Подробнее")
                    }
                    TextButton(onClick = { copy("Журнал", log.joinToString("\n")) }) { Text("Копировать") }
                }
            }
        }
    }
    links.forEach { link -> LinkRow(link, onOpen = { openLink = link }) }
    if (showLog) LogSheet(title, log, running) { showLog = false }
    openLink?.let { LinkSheet(it) { openLink = null } }
}

/** Ссылка одной строкой: название, адрес, «Копировать» и «QR». */
@Composable
fun LinkRow(link: ShareLink, onOpen: () -> Unit) {
    val copy = rememberCopier()
    Card(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Qr, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(link.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    link.uri, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
            IconButton(onClick = { copy(link.title, link.uri) }) {
                Icon(AppIcons.Copy, "Копировать ссылку", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** Шторка со ссылкой: QR-код крупно, «Копировать», «Поделиться». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSheet(link: ShareLink, onDismiss: () -> Unit) {
    val share = rememberShare()
    val copy = rememberCopier()
    val qr = remember(link.uri) { qrImage(link.uri) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(link.title, style = MaterialTheme.typography.titleLarge)
            Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.White, shadowElevation = 2.dp) {
                Image(qr, "QR-код ссылки ${link.title}", Modifier.padding(16.dp).size(240.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { copy(link.title, link.uri) }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(AppIcons.Copy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Копировать")
                }
                OutlinedButton(onClick = { share(link.uri, "Поделиться ссылкой") }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(AppIcons.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Поделиться")
                }
            }
            Text(
                link.uri, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
            )
            Hint("В клиенте (Happ, INCY, Hiddify…) выберите «Добавить из буфера» или «Сканировать QR». Ссылка содержит пароли — не публикуйте её.")
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Шторка журнала: расшифровка сверху, полный журнал моноширинным шрифтом, копирование. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSheet(title: String, log: List<String>, running: Boolean, onDismiss: () -> Unit) {
    val copy = rememberCopier()
    val scroll = rememberScrollState()
    LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { copy("Журнал", log.joinToString("\n")) }) { Icon(AppIcons.Copy, "Копировать журнал") }
            }
            val summary = LogSummary.of(log, running)
            val look = lookOf(summary.state)
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(look.container).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(summary.headline, color = look.content, style = MaterialTheme.typography.titleSmall)
                summary.points.forEach { Text("• $it", color = look.content, style = MaterialTheme.typography.bodySmall) }
            }
            Box(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(MaterialTheme.shapes.medium)
                    .background(Color(0xFF15161A)),
            ) {
                Text(
                    log.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                    color = Color(0xFFE3E3E8), modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------- шаги

/** Вертикальный степпер с состоянием каждого шага. */
@Composable
fun Stepper(steps: List<Pair<String, StepStatus>>) {
    val s = LocalStatusColors.current
    val c = MaterialTheme.colorScheme
    Column {
        steps.forEachIndexed { i, (title, st) ->
            val (bg, fg, mark) = when (st) {
                StepStatus.PENDING -> Triple(c.surfaceContainerHighest, c.onSurfaceVariant, "${i + 1}")
                StepStatus.RUNNING -> Triple(c.primary, c.onPrimary, "${i + 1}")
                StepStatus.DONE -> Triple(s.success, s.onSuccess, "✓")
                StepStatus.WARN -> Triple(s.warning, s.onWarning, "!")
                StepStatus.FAILED -> Triple(c.error, c.onError, "✕")
                StepStatus.SKIPPED -> Triple(c.surfaceContainerHighest, c.onSurfaceVariant, "–")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                    Text(mark, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = if (st == StepStatus.RUNNING) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (st == StepStatus.PENDING || st == StepStatus.SKIPPED) c.onSurfaceVariant else c.onSurface,
                )
            }
            if (i < steps.lastIndex) {
                Box(Modifier.padding(start = 13.dp).width(2.dp).height(8.dp).background(c.outlineVariant))
            }
        }
    }
}

// ---------------------------------------------------------------- диалоги

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Отмена",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (danger) AppIcons.Warning else AppIcons.Info, null) },
        title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(text) } },
        confirmButton = {
            TextButton(onConfirm) { Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onDismiss) { Text(dismiss) } },
    )
}

/** Тонкий разделитель между группами внутри карточки. */
@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
