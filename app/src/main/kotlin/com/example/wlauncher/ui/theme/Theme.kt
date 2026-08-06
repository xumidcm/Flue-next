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

val DEFAULT_UI_STYLE: UiStyle = UiStyle.MIUIX

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
            content = inner,
        )
        UiStyle.APPLE_WATCH -> MaterialTheme(
            colorScheme = if (isDark) DarkColorScheme else LightColorScheme,
            content = inner,
        )
    }
}

@Composable
fun ProvideGlobalUiScale(
    scalePercent: Int,
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
