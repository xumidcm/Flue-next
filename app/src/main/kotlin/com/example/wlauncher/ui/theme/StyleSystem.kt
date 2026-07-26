package com.flue.launcher.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Stable
data class LauncherStyle(
    val uiStyle: UiStyle,
    val screenBackground: Color,
    val screenScrim: Brush,
    val cardColor: Color,
    val pressedCardColor: Color,
    val selectedCardColor: Color,
    val outlineColor: Color,
    val titleColor: Color,
    val bodyColor: Color,
    val secondaryTextColor: Color,
    val accentColor: Color,
    val positiveColor: Color,
    val topBarChipColor: Color,
    val topBarTextColor: Color,
    val appListBaseColor: Color,
    val appListFadeColor: Color,
    val appListPressedOverlay: Color,
    val cardShape: Shape,
    val compactShape: Shape,
    val bubbleShape: Shape,
    // 五档形状语言（chip < row < card < panel）。cardShape/compactShape 保留是
    // 兼容旧调用点；新代码一律走这四档，禁止再写 RoundedCornerShape(数字) 字面量。
    val chipShape: Shape = CircleShape,
    val rowShape: Shape = RoundedCornerShape(18.dp),
    val panelShape: Shape = RoundedCornerShape(30.dp),
    /**
     * 开关「未激活」轨道色。HyperOS 浅色底是极淡的黑 (#0000001F)、深色底是
     * 淡白 (#FFFFFF33)，不能拿 secondaryTextColor 兑出来 —— 那个太深，
     * 会让关掉的开关看起来像禁用态。
     */
    val switchTrackOffColor: Color = Color(0x33FFFFFF),
    val watchFaceOverlayStrength: Float,
    val faceSwitchEnterSpec: FiniteAnimationSpec<Float>,
    val faceSwitchExitSpec: FiniteAnimationSpec<Float>,
    val pageMotionSpec: FiniteAnimationSpec<Float>,
    val launchMaskEnterSpec: FiniteAnimationSpec<Float>,
    val launchMaskExitSpec: FiniteAnimationSpec<Float>,
    val pressScaleSpec: FiniteAnimationSpec<Float>,
    val switchThumbTravel: Float
)

private val LocalLauncherStyle = compositionLocalOf { appleWatchLauncherStyle(darkMode = true) }

val UiStyle.isMaterial: Boolean
    get() = this == UiStyle.MATERIAL_3

val UiStyle.isMiuix: Boolean
    get() = this == UiStyle.MIUIX

fun appleWatchLauncherStyle(darkMode: Boolean): LauncherStyle {
    val card = if (darkMode) Color(0x1FFFFFFF) else Color(0x16000000)
    return LauncherStyle(
        uiStyle = UiStyle.APPLE_WATCH,
        screenBackground = if (darkMode) Color.Black else Color(0xFFF7F7FA),
        screenScrim = Brush.verticalGradient(
            listOf(
                Color.Black.copy(alpha = if (darkMode) 0.0f else 0.05f),
                Color.Transparent,
                Color.Black.copy(alpha = if (darkMode) 0.34f else 0.10f)
            )
        ),
        cardColor = card,
        pressedCardColor = if (darkMode) Color(0xFF1A1A1D) else Color(0xFFE8E8ED),
        selectedCardColor = WatchColors.ActiveCyan.copy(alpha = if (darkMode) 0.16f else 0.20f),
        outlineColor = Color.White.copy(alpha = if (darkMode) 0.05f else 0.08f),
        titleColor = if (darkMode) Color.White else Color(0xFF101216),
        bodyColor = if (darkMode) Color.White else Color(0xFF101216),
        secondaryTextColor = WatchColors.TextTertiary,
        accentColor = WatchColors.ActiveCyan,
        positiveColor = WatchColors.ActiveGreen,
        topBarChipColor = Color.White.copy(alpha = if (darkMode) 0.08f else 0.10f),
        topBarTextColor = if (darkMode) Color.White else Color(0xFF16181D),
        appListBaseColor = card,
        appListFadeColor = Color.Black.copy(alpha = if (darkMode) 0.72f else 0.08f),
        appListPressedOverlay = Color.White.copy(alpha = if (darkMode) 0.08f else 0.04f),
        // 26dp 与通知卡对齐（原 24dp），panel 30dp 用于媒体卡等大块表面。
        cardShape = RoundedCornerShape(26.dp),
        compactShape = RoundedCornerShape(18.dp),
        bubbleShape = CircleShape,
        chipShape = CircleShape,
        rowShape = RoundedCornerShape(18.dp),
        panelShape = RoundedCornerShape(30.dp),
        switchTrackOffColor = if (darkMode) Color(0x33FFFFFF) else Color(0x1F000000),
        watchFaceOverlayStrength = 1f,
        faceSwitchEnterSpec = tween(durationMillis = 220, delayMillis = 70),
        faceSwitchExitSpec = tween(durationMillis = 180),
        pageMotionSpec = tween(durationMillis = 220),
        launchMaskEnterSpec = tween(durationMillis = 140),
        launchMaskExitSpec = tween(durationMillis = 180),
        pressScaleSpec = spring(stiffness = 820f, dampingRatio = 0.74f),
        switchThumbTravel = 22f
    )
}

fun materialLauncherStyle(darkMode: Boolean): LauncherStyle {
    val bg = if (darkMode) Color(0xFF0D1118) else Color(0xFFF5F1EA)
    val surface = if (darkMode) Color(0xFF18212D) else Color(0xFFE8E1D6)
    val surfaceAlt = if (darkMode) Color(0xFF243244) else Color(0xFFD9D0C2)
    val accent = if (darkMode) Color(0xFF8CB4FF) else Color(0xFF295EA8)
    val text = if (darkMode) Color(0xFFF4F7FB) else Color(0xFF1E2938)
    return LauncherStyle(
        uiStyle = UiStyle.MATERIAL_3,
        screenBackground = bg,
        screenScrim = Brush.verticalGradient(
            listOf(
                accent.copy(alpha = if (darkMode) 0.16f else 0.12f),
                Color.Transparent,
                surface.copy(alpha = 0.86f)
            )
        ),
        cardColor = surface,
        pressedCardColor = surfaceAlt,
        selectedCardColor = accent.copy(alpha = if (darkMode) 0.22f else 0.18f),
        outlineColor = accent.copy(alpha = if (darkMode) 0.22f else 0.16f),
        titleColor = text,
        bodyColor = text,
        secondaryTextColor = text.copy(alpha = 0.72f),
        accentColor = accent,
        positiveColor = if (darkMode) Color(0xFF7AD982) else Color(0xFF1C7B39),
        topBarChipColor = surfaceAlt,
        topBarTextColor = text,
        appListBaseColor = surface,
        appListFadeColor = bg.copy(alpha = 0.92f),
        appListPressedOverlay = accent.copy(alpha = if (darkMode) 0.12f else 0.08f),
        cardShape = RoundedCornerShape(32.dp),
        compactShape = RoundedCornerShape(24.dp),
        bubbleShape = RoundedCornerShape(30.dp),
        chipShape = CircleShape,
        rowShape = RoundedCornerShape(24.dp),
        panelShape = RoundedCornerShape(36.dp),
        switchTrackOffColor = if (darkMode) Color(0x33FFFFFF) else Color(0x1F000000),
        watchFaceOverlayStrength = 1.25f,
        faceSwitchEnterSpec = tween(durationMillis = 300, delayMillis = 20),
        faceSwitchExitSpec = tween(durationMillis = 220),
        pageMotionSpec = spring(
            dampingRatio = 0.84f,
            stiffness = Spring.StiffnessMediumLow
        ),
        launchMaskEnterSpec = tween(durationMillis = 220),
        launchMaskExitSpec = tween(durationMillis = 220),
        pressScaleSpec = spring(stiffness = 560f, dampingRatio = 0.82f),
        switchThumbTravel = 20f
    )
}

/**
 * MIUIX（HyperOS 视觉语言）风格令牌。
 *
 * 配色按 HyperOS 设置页的实际观感取值，深浅色一一对应：
 * - 页面底：浅色 #F7F7F7 / 深色 纯黑 #000000
 * - 卡片面：浅色 纯白 #FFFFFF / 深色 #1F1F1F
 * - 主文字：#000000E6 / #FFFFFFE6（不是纯黑纯白，HyperOS 一直压 90% 透明度）
 * - 次要字：#00000080 / #FFFFFF80
 * - 强调蓝：#3482FF（深浅色同一支，HyperOS 不随深色变浅）
 * - 分隔线：极淡，浅色 #0000000F / 深色 #FFFFFF14
 */
fun miuixLauncherStyle(darkMode: Boolean): LauncherStyle {
    val bg = if (darkMode) Color(0xFF000000) else Color(0xFFF7F7F7)
    val card = if (darkMode) Color(0xFF1F1F1F) else Color(0xFFFFFFFF)
    // 按下态只比卡片深/浅一档，HyperOS 的按压反馈很克制
    val cardPressed = if (darkMode) Color(0xFF2C2C2C) else Color(0xFFEDEDED)
    val accent = Color(0xFF3482FF)
    val text = if (darkMode) Color(0xE6FFFFFF) else Color(0xE6000000)
    val secondary = if (darkMode) Color(0x80FFFFFF) else Color(0x80000000)
    val hairline = if (darkMode) Color(0x14FFFFFF) else Color(0x0F000000)
    return LauncherStyle(
        uiStyle = UiStyle.MIUIX,
        screenBackground = bg,
        screenScrim = Brush.verticalGradient(
            listOf(
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = if (darkMode) 0.18f else 0.04f)
            )
        ),
        cardColor = card,
        pressedCardColor = cardPressed,
        selectedCardColor = accent.copy(alpha = if (darkMode) 0.20f else 0.10f),
        outlineColor = hairline,
        titleColor = text,
        bodyColor = text,
        secondaryTextColor = secondary,
        accentColor = accent,
        // MIUIX 的开关 / 选中态统一使用蓝色强调色，而不是绿色
        positiveColor = accent,
        topBarChipColor = if (darkMode) Color(0xFF2C2C2C) else Color(0xFFECECEC),
        topBarTextColor = text,
        appListBaseColor = card,
        appListFadeColor = bg.copy(alpha = if (darkMode) 0.86f else 0.92f),
        appListPressedOverlay = if (darkMode) Color(0x14FFFFFF) else Color(0x0D000000),
        cardShape = RoundedCornerShape(18.dp),
        compactShape = RoundedCornerShape(14.dp),
        bubbleShape = RoundedCornerShape(999.dp),
        chipShape = CircleShape,
        rowShape = RoundedCornerShape(14.dp),
        panelShape = RoundedCornerShape(22.dp),
        switchTrackOffColor = if (darkMode) Color(0x33FFFFFF) else Color(0x1F000000),
        watchFaceOverlayStrength = 1f,
        faceSwitchEnterSpec = tween(durationMillis = 260, delayMillis = 40),
        faceSwitchExitSpec = tween(durationMillis = 200),
        // MIUIX 层级转场统一 spring(0.9, 400)：略微过冲、收得干净
        pageMotionSpec = spring(dampingRatio = 0.9f, stiffness = 400f),
        launchMaskEnterSpec = tween(durationMillis = 180),
        launchMaskExitSpec = tween(durationMillis = 200),
        pressScaleSpec = spring(stiffness = 600f, dampingRatio = 0.9f),
        switchThumbTravel = 22f
    )
}

@Composable
fun ProvideLauncherStyle(
    uiStyle: UiStyle,
    darkMode: Boolean,
    content: @Composable () -> Unit
) {
    val style = remember(uiStyle, darkMode) {
        when (uiStyle) {
            UiStyle.APPLE_WATCH -> appleWatchLauncherStyle(darkMode)
            UiStyle.MATERIAL_3 -> materialLauncherStyle(darkMode)
            UiStyle.MIUIX -> miuixLauncherStyle(darkMode)
        }
    }
    CompositionLocalProvider(LocalLauncherStyle provides style, content = content)
}

object LauncherTheme {
    val style: LauncherStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalLauncherStyle.current
}

fun styleAwareEnterTransition(style: LauncherStyle): EnterTransition = EnterTransition.None

fun styleAwareExitTransition(style: LauncherStyle): ExitTransition = ExitTransition.None
