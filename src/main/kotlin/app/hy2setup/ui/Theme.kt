package app.hy2setup.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/*
 * Тема Material 3: светлая или тёмная — по настройке Windows.
 * На старых Android — фирменная палитра «сапфир» (спокойный синий, ассоциируется с защитой соединения).
 */

private val BrandLight = lightColorScheme(
    primary = Color(0xFF3D5BA9), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF), onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF595D72), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEE1F9), onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF006A60), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9EF2E4), onTertiaryContainer = Color(0xFF00201C),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF8FF), onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFAF8FF), onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E2EC), onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767680), outlineVariant = Color(0xFFC6C6D0),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4), surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
    inverseSurface = Color(0xFF2F3036), inverseOnSurface = Color(0xFFF1F0F7), inversePrimary = Color(0xFFB5C4FF),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFB5C4FF), onPrimary = Color(0xFF042C78),
    primaryContainer = Color(0xFF23438F), onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFC1C5DD), onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF414659), onSecondaryContainer = Color(0xFFDEE1F9),
    tertiary = Color(0xFF82D5C8), onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048), onTertiaryContainer = Color(0xFF9EF2E4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318), onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F), onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF90909A), outlineVariant = Color(0xFF45464F),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25), surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    inverseSurface = Color(0xFFE3E1E9), inverseOnSurface = Color(0xFF2F3036), inversePrimary = Color(0xFF3D5BA9),
)

/** Цвета состояний, которых нет в стандартной схеме M3: «успех» и «внимание» (для светлой и тёмной темы). */
@Immutable
data class StatusColors(
    val success: Color, val onSuccess: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val onWarning: Color, val warningContainer: Color, val onWarningContainer: Color,
)

private val StatusLight = StatusColors(
    success = Color(0xFF2E6A30), onSuccess = Color.White,
    successContainer = Color(0xFFB2F2AE), onSuccessContainer = Color(0xFF002106),
    warning = Color(0xFF7A5900), onWarning = Color.White,
    warningContainer = Color(0xFFFFDF9C), onWarningContainer = Color(0xFF261A00),
)

private val StatusDark = StatusColors(
    success = Color(0xFF97D594), onSuccess = Color(0xFF00390D),
    successContainer = Color(0xFF125219), onSuccessContainer = Color(0xFFB2F2AE),
    warning = Color(0xFFF2C048), onWarning = Color(0xFF402D00),
    warningContainer = Color(0xFF5C4300), onWarningContainer = Color(0xFFFFDF9C),
)

/** Рукописный шрифт логотипа vpngramm (Grand Hotel, OFL). */
val LogoFont = FontFamily(Font("grand_hotel.ttf"))

val LocalStatusColors = staticCompositionLocalOf { StatusLight }

/** Крупные скругления — характерная черта Material 3 Expressive. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun Hy2Theme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = when {
        dark -> BrandDark
        else -> BrandLight
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) StatusDark else StatusLight) {
        MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
    }
}
