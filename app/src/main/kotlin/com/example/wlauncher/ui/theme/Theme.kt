package com.flue.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.flue.launcher.ui.common.FlueScreenShape
import com.flue.launcher.ui.common.LocalFlueScreenShapeOverride

// UI 风格
enum class UiStyle {
    APPLE_WATCH,
    MATERIAL_3,
    MIUIX
}

/**
 * 启动时的默认界面风格（老师指定改成 watchOS 档）。
 *
 * 枚举名仍是 `APPLE_WATCH` —— 它是**持久化值**（`UiStyle.valueOf(存的字符串)`），改名会让
 * 所有老用户的存档解析失败、被下面那条 `?:` 兜回默认档，等于静默重置他们的选择。
 * 对外的名字只在文案层改（`settings_ui_aw` 那几条字符串）。
 */
val DEFAULT_UI_STYLE: UiStyle = UiStyle.APPLE_WATCH

fun uiStyleFromPreference(raw: String?): UiStyle =
    raw?.let { value -> runCatching { UiStyle.valueOf(value) }.getOrNull() } ?: DEFAULT_UI_STYLE

// 深色 Material3 颜色方案
private val DarkColorScheme = darkColorScheme(
    primary = WatchColorsDark.ActiveCyan,
    secondary = WatchColorsDark.ActiveGreen,
    tertiary = WatchColorsDark.ActiveBlue,
    background = WatchColorsDark.Black,
    surface = WatchColorsDark.Black,
    onPrimary = WatchColorsDark.White,
    onSecondary = WatchColorsDark.White,
    onTertiary = WatchColorsDark.White,
    onBackground = WatchColorsDark.White,
    onSurface = WatchColorsDark.White,
)

// 浅色 Material3 颜色方案
private val LightColorScheme = lightColorScheme(
    primary = WatchColorsLight.ActiveCyan,
    secondary = WatchColorsLight.ActiveGreen,
    tertiary = WatchColorsLight.ActiveBlue,
    background = WatchColorsDark.White,
    surface = WatchColorsDark.White,
    onPrimary = WatchColorsDark.Black,
    onSecondary = WatchColorsDark.Black,
    onTertiary = WatchColorsDark.Black,
    onBackground = WatchColorsDark.Black,
    onSurface = WatchColorsDark.Black,
)

// MIUIX（HyperOS）配色注入 M3 colorScheme：商店 / 编辑器 / 登录等直接读
// MaterialTheme.* 的界面在 MIUIX 风格下整体换肤（#3482FF 蓝 + HyperOS 灰阶）。
private val MiuixDarkColorScheme = darkColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color.White,
    secondary = Color(0xFF34C08D),
    tertiary = Color(0xFF3482FF),
    background = Color.Black,
    surface = Color(0xFF111113),
    surfaceVariant = Color(0xFF1F1F1F),
    secondaryContainer = Color(0xFF20304A),
    onSecondaryContainer = Color(0xFFB9D4FF),
    onBackground = Color(0xE6FFFFFF),
    onSurface = Color(0xE6FFFFFF),
    onSurfaceVariant = Color(0x80FFFFFF),
    outline = Color(0x26FFFFFF),
)

private val MiuixLightColorScheme = lightColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color.White,
    secondary = Color(0xFF31A56F),
    tertiary = Color(0xFF3482FF),
    background = Color(0xFFF7F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF2F2F5),
    secondaryContainer = Color(0xFFE3EDFF),
    onSecondaryContainer = Color(0xFF1B4C9C),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF80838A),
    outline = Color(0x1F000000),
)

// 主题模式
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

val LocalGlobalUiScale = compositionLocalOf { 1f }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WatchLauncherTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    uiStyle: UiStyle = UiStyle.APPLE_WATCH,
    screenShape: FlueScreenShape = FlueScreenShape.AUTO,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    WatchColors.setDarkMode(isDark)

    val inner: @Composable () -> Unit = {
        CompositionLocalProvider(LocalFlueScreenShapeOverride provides screenShape) {
            ProvideLauncherStyle(
                uiStyle = uiStyle,
                darkMode = isDark,
                content = content
            )
        }
    }

    when (uiStyle) {
        // 真 Material 3 Expressive：官方 expressive 配色基线 + 弹性
        // motion scheme（组件级 spring 动效），不再是手搓「假 MD3」。
        UiStyle.MATERIAL_3 -> MaterialExpressiveTheme(
            colorScheme = if (isDark) darkColorScheme() else expressiveLightColorScheme(),
            motionScheme = MotionScheme.expressive(),
            content = inner,
        )
        UiStyle.MIUIX -> MaterialTheme(
            colorScheme = if (isDark) MiuixDarkColorScheme else MiuixLightColorScheme,
            // M3 主题必须留在外层：106 个 `LauncherTheme.style` 读取点与大量
            // `MaterialTheme.colorScheme` 读取点都依赖它，拆不掉。MIUIX 档在它**里面**
            // 再套一层真 `MiuixTheme`，miuix 组件（basic.Switch / basic.Slider /
            // extra.WindowDialog）才拿得到自己的配色与平滑圆角。两套主题走各自独立的
            // CompositionLocal，嵌套无冲突。
            content = { MiuixThemeLayer(isDark = isDark, content = inner) },
        )
        UiStyle.APPLE_WATCH -> MaterialTheme(
            colorScheme = if (isDark) DarkColorScheme else LightColorScheme,
            content = inner,
        )
    }
}

/**
 * MIUIX 档的真 miuix 主题层。
 *
 * 配色直接从 miuix 的 `darkColorScheme()` / `lightColorScheme()` 起，只把**强调色**换成
 * 项目统一的 `#3482FF` —— 强调色是全局一致的（三套风格共用同一个来源），其余灰阶交给
 * miuix 自己的配方，免得手抄一遍 HyperOS 的颜色表又抄漏。
 */
@Composable
private fun MiuixThemeLayer(
    isDark: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = remember(isDark) {
        if (isDark) {
            top.yukonga.miuix.kmp.theme.darkColorScheme(primary = Color(0xFF3482FF))
        } else {
            top.yukonga.miuix.kmp.theme.lightColorScheme(primary = Color(0xFF3482FF))
        }
    }
    top.yukonga.miuix.kmp.theme.MiuixTheme(colors = colors, content = content)
}

@Composable
fun ProvideGlobalUiScale(    scalePercent: Int,
    content: @Composable () -> Unit
) {
    val baseDensity = LocalDensity.current
    val scale = scalePercent.coerceIn(50, 150) / 100f
    val scaledDensity = remember(baseDensity, scale) {
        Density(
            density = baseDensity.density * scale,
            fontScale = baseDensity.fontScale
        )
    }
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalGlobalUiScale provides scale,
        content = content
    )
}

/**
 * 在 [androidx.compose.ui.window.Dialog] 内重新挂上全局 UI 缩放。
 *
 * Dialog 是**独立 Window**，Compose 在它的根节点按该 Window 自己的
 * DisplayMetrics 重新提供 [LocalDensity]，外层 [ProvideGlobalUiScale] 那份
 * 已缩放的 density 到不了弹窗里 —— 所以弹窗一直按系统原始 density 排版，
 * 设置里的「全局 UI 大小」对它毫无作用（老师实机反馈）。
 *
 * [LocalGlobalUiScale] 走的是普通 CompositionLocal，**能**穿过 Dialog 边界，
 * 所以这里直接读它拿到当前倍率，不需要调用方再传一次百分比。
 */
@Composable
fun ProvideDialogUiScale(
    content: @Composable () -> Unit
) {
    val scale = LocalGlobalUiScale.current
    val dialogDensity = LocalDensity.current
    val scaledDensity = remember(dialogDensity, scale) {
        Density(
            density = dialogDensity.density * scale,
            fontScale = dialogDensity.fontScale
        )
    }
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        content = content
    )
}
