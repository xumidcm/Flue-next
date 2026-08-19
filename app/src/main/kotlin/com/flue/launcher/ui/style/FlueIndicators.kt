package com.flue.launcher.ui.style

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

// MIUIX 档现在是自绘的弧（见 MiuixStyleLoadingIndicator），没有上游组件那圈内建留白，
// 所以名义边长就是视觉边长 —— 0.78 那个折扣本来就是为了抵掉 InfiniteProgressIndicator
// 的内部 padding，留着只会让弧比调用点要求的小一圈（老师报过「加载圆圈太小」）。
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
 * 现状是**两次全局统一**，正好是老师点名要废除的做法：手搓的 `MiuixLoadingIndicator`
 * 被全局用掉 24 处，而表盘加载页又全局用了 miuix 的真 `InfiniteProgressIndicator`。
 * 结果三套风格下「转圈」长得一模一样 —— 拿某一套的指示器覆盖了全局。
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
