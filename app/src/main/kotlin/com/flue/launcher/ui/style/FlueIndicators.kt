package com.flue.launcher.ui.style

import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.flue.launcher.ui.style.apple.AppleLoadingIndicator
import com.flue.launcher.ui.style.apple.AppleRefreshIndicator
import com.flue.launcher.ui.style.md3.Md3LoadingIndicator
import com.flue.launcher.ui.style.md3.Md3RefreshIndicator
import com.flue.launcher.ui.style.miuix.MiuixStyleLoadingIndicator
import com.flue.launcher.ui.style.miuix.MiuixStyleRefreshIndicator
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.UiStyle

/** The business surface that owns a loading indicator. */
enum class FlueLoadingIndicatorUsage {
    DEFAULT,
    WATCH_FACE_LOADING,
    WATCH_STORE,
}

// MIUIX uses the official InfiniteProgressIndicator, whose size is already
// the visual diameter and does not need a compensating scale factor.
internal const val FLUE_MIUIX_LOADING_VISUAL_SCALE = 1.0f
internal const val FLUE_MIUIX_STORE_LOADING_VISUAL_SCALE = 1.0f
internal const val FLUE_MIUIX_WATCH_FACE_LOADING_VISUAL_SCALE = 1.0f
internal const val FLUE_MD3_LOADING_VISUAL_SCALE = 1.35f

internal fun flueLoadingIndicatorVisualSize(
    size: Dp,
    uiStyle: UiStyle,
    usage: FlueLoadingIndicatorUsage = FlueLoadingIndicatorUsage.DEFAULT,
): Dp = when (uiStyle) {
    UiStyle.MIUIX -> size * when (usage) {
        FlueLoadingIndicatorUsage.WATCH_FACE_LOADING -> FLUE_MIUIX_WATCH_FACE_LOADING_VISUAL_SCALE
        FlueLoadingIndicatorUsage.WATCH_STORE -> FLUE_MIUIX_STORE_LOADING_VISUAL_SCALE
        FlueLoadingIndicatorUsage.DEFAULT -> FLUE_MIUIX_LOADING_VISUAL_SCALE
    }
    UiStyle.MATERIAL_3 -> size * FLUE_MD3_LOADING_VISUAL_SCALE
    UiStyle.APPLE_WATCH -> size
}

/**
 * 加载指示器的风格分发。
 *
 * ## 为什么要拆
 *
 * Each style owns its loading component so a Miuix indicator is not replaced
 * by a hand-drawn fallback or by another style's component.
 *
 * 分档之后：MIUIX 用 miuix 真组件、MD3 用 M3 的 `LoadingIndicator`（`MaterialShapes`
 * 形变）、Apple 用细环匀速弧（就是那份手搓实现，它本来就是 watchOS 的读法，搬到
 * Apple 档正合适）。
 *
 * @param size 名义边长。三套官方组件的固有留白不同，由这里换算成各自的视觉边长。
 */
@Composable
fun FlueLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    uiStyle: UiStyle = LauncherTheme.style.uiStyle,
    usage: FlueLoadingIndicatorUsage = FlueLoadingIndicatorUsage.DEFAULT,
) {
    val visualSize = flueLoadingIndicatorVisualSize(size, uiStyle, usage)
    // 加载是中性状态，不使用可操作项的强调色；三套风格统一走主题的官方次要灰。
    val color = LauncherTheme.style.secondaryTextColor
    when (uiStyle) {
        UiStyle.MIUIX -> MiuixStyleLoadingIndicator(modifier, visualSize, color)
        UiStyle.MATERIAL_3 -> Md3LoadingIndicator(modifier, visualSize, color)
        UiStyle.APPLE_WATCH -> AppleLoadingIndicator(modifier, visualSize, color)
    }
}

/**
 * Determinate progress with the same style boundary as the indeterminate
 * loading indicator. This keeps editor conversion progress from leaking a
 * raw Material component into MIUIX and Apple surfaces.
 */
@Composable
fun FlueDeterminateProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    uiStyle: UiStyle = LauncherTheme.style.uiStyle,
    usage: FlueLoadingIndicatorUsage = FlueLoadingIndicatorUsage.DEFAULT,
) {
    val style = LauncherTheme.style
    val safeProgress = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val height = when (uiStyle) {
        UiStyle.MIUIX -> 4.dp
        UiStyle.MATERIAL_3 -> 6.dp
        UiStyle.APPLE_WATCH -> 3.dp
    }
    val track = when (uiStyle) {
        UiStyle.MIUIX -> style.secondaryTextColor.copy(alpha = 0.18f)
        UiStyle.MATERIAL_3 -> style.secondaryTextColor.copy(alpha = 0.24f)
        UiStyle.APPLE_WATCH -> style.secondaryTextColor.copy(alpha = 0.22f)
    }
    val fill = when (uiStyle) {
        UiStyle.MIUIX -> style.accentColor
        UiStyle.MATERIAL_3 -> style.accentColor
        UiStyle.APPLE_WATCH -> style.secondaryTextColor
    }
    Canvas(modifier = modifier) {
        drawFlueProgressTrack(track, fill, safeProgress, height.toPx())
    }
}

private fun DrawScope.drawFlueProgressTrack(
    track: Color,
    fill: Color,
    progress: Float,
    heightPx: Float,
) {
    val barHeight = heightPx.coerceAtMost(size.height).coerceAtLeast(1f)
    val top = (size.height - barHeight) / 2f
    val radius = barHeight / 2f
    val barSize = Size(size.width, barHeight)
    drawRoundRect(track, Offset(0f, top), barSize, CornerRadius(radius, radius))
    if (progress > 0f) {
        val width = (size.width * progress).coerceAtLeast(barHeight)
            .coerceAtMost(size.width)
        drawRoundRect(fill, Offset(0f, top), Size(width, barHeight), CornerRadius(radius, radius))
    }
}

/**
 * 下拉刷新指示器的风格分发。
 *
 * **手势与位移账本不在这里**：`FluePullToRefreshState` 已经把「拉了多远、够不够触发、
 * 松手回弹」算完了，分工本来就正确，A4 只换外观。所以这里只吃两个读数 ——
 * 进度与是否正在刷新。
 *
 * @param progress 0..1 的下拉进度（`FluePullToRefreshState.progress`）。
 * @param refreshing 是否正在刷新。
 */
@Composable
fun FlueRefreshIndicator(
    progress: Float,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
    uiStyle: UiStyle = LauncherTheme.style.uiStyle,
) {
    when (uiStyle) {
        UiStyle.MIUIX -> MiuixStyleRefreshIndicator(progress, refreshing, modifier)
        UiStyle.MATERIAL_3 -> Md3RefreshIndicator(progress, refreshing, modifier)
        UiStyle.APPLE_WATCH -> AppleRefreshIndicator(
            progress = progress,
            refreshing = refreshing,
            modifier = modifier,
            color = LauncherTheme.style.secondaryTextColor,
        )
    }
}
