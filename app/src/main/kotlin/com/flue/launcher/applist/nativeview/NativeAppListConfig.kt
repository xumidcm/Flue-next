package com.flue.launcher.applist.nativeview

import com.flue.launcher.motion.FlueMotionProfile
import com.flue.launcher.motion.FlueMotionProfiles
import com.flue.launcher.util.FlowCrescentDirection
import com.flue.launcher.util.FlowShapeCurve
import com.flue.launcher.util.flowCrescentOffsetPx
import kotlin.math.abs
import kotlin.math.hypot

enum class NativeAppListTheme {
    LIGHT,
    DARK,
}

enum class NativeAppListIconShape {
    CIRCLE,
    ROUNDED_RECT,
}

enum class NativeAppListPresentation {
    LINEAR,
    HONEYCOMB,
    DENSE_GRID,

    /**
     * 心流：等列网格 + 月牙错位。
     *
     * 几何与蜂窝共用一套「绝对定位 + 视口中心鱼眼」的骨架（[NativeAppGridGeometry]），
     * 唯一多出来的是整行按离屏幕中线的距离往一侧平移的那条弧。
     */
    FLOW,

    /**
     * 星环：铺满屏幕的正交方格 + 圆屏上四边一起往屏心收拢。
     *
     * 与 [DENSE_GRID] 同为规则网格，两处差别：
     *
     * 1. 格子是**正方形且吃满可用宽度**（`availableWidth / columns`，见
     *    [nativeRingCellSizePx]），横纵都不留内边距 —— 参考图里图标是铺满整屏的；
     * 2. 圆屏上再叠一道**径向收拢**（[nativeRingConvergenceOffset]）：每个格子按
     *    自己离屏心的距离往屏心压。压的是径向量，所以一整行的外侧列被压得比内侧
     *    多，直排自然弯成弧 —— 上下左右四条边同时有弧度，这就是「星环」。
     *
     * 直屏只在上下边缘触发带内应用鱼眼，触发带之外退化成规规矩矩的平铺网格。
     */
    RING,
}

/** The two supported production fish-eye profiles. */
enum class NativeFisheyePreset {
    CLASSIC,
    NEXT,
}

/** Physical screen outline used by the shared drawer geometry seam. */
enum class NativeDeviceShape {
    RECTANGULAR,
    ROUND,
}

data class NativeAppListRowBorder(
    val enabled: Boolean = false,
    val colorArgb: Long = 0L,
    val widthPx: Float = 0f,
    val cornerRadiusPx: Float = 0f,
)

data class NativeAppListPalette(
    val rowBackgroundColorArgb: Long = 0x00000000L,
    val labelColorArgb: Long = 0xffffffffL,
    val pressedOverlayColorArgb: Long = 0x24ffffffL,
    val fallbackIconBackgroundColorArgb: Long = 0x33ffffffL,
    val fallbackIconForegroundColorArgb: Long = 0xccffffffL,
    val fadeEdgeColorArgb: Long = 0xff000000L,
)

data class NativeAppListConfig(
    val presentation: NativeAppListPresentation = NativeAppListPresentation.LINEAR,
    val active: Boolean = true,
    val initialResetToken: Long = 0L,
    val leftSafeInsetPx: Int = 0,
    val rowHeightPx: Int = DEFAULT_ROW_HEIGHT_PX,
    val iconSizePx: Int = DEFAULT_ICON_SIZE_PX,
    val rowPaddingStartPx: Int = DEFAULT_HORIZONTAL_PADDING_PX,
    val rowPaddingTopPx: Int = DEFAULT_VERTICAL_PADDING_PX,
    val rowPaddingEndPx: Int = DEFAULT_HORIZONTAL_PADDING_PX,
    val rowPaddingBottomPx: Int = DEFAULT_VERTICAL_PADDING_PX,
    val labelTextSizePx: Float = DEFAULT_LABEL_TEXT_SIZE_PX,
    val iconLabelGapPx: Int = DEFAULT_ICON_LABEL_GAP_PX,
    val iconShape: NativeAppListIconShape = NativeAppListIconShape.CIRCLE,
    val iconCornerRadiusPx: Float = 0f,
    val honeycombCellSizePx: Float = 0f,
    val honeycombNarrowColumns: Int = 4,
    val honeycombFisheyeRangeRows: Int = 4,
    /** MD3 密集蜂窝顶部鱼眼开关；默认关闭，底部鱼眼仍由 [fisheyeEnabled] 控制。 */
    val honeycombTopFisheyeEnabled: Boolean = false,
    /** 心流每行列数。等列，不做蜂窝那样的宽窄交替。 */
    val flowColumns: Int = 4,
    /** 心流格心间距；0 时按 `iconSizePx * 1.02f` 兜（与蜂窝同一惯例）。 */
    val flowCellSizePx: Float = 0f,
    /**
     * 月牙峰值鼓出量，按**「峰值正好贴边」余量的倍数**给。0 = 关掉月牙，心流退化成规则网格；
     * 1.0 = 谁都不越界的那一档；最多到 [NativeFlowGeometry.MAX_CRESCENT_BULGE_FRACTION]。
     *
     * 方向单列在 [flowCrescentDirection]，所以这里恒为非负 —— 一个量一个含义，
     * 免得「负的鼓出量」和「朝左」两种表达同时存在。
     *
     * 收倍数而不是像素：像素得由调用方按内容宽度估一遍，而真正的可用余量要等布局时
     * 知道行宽才算得出来，两个上限对不上就会出现「拉杆过半之后毫无变化」。
     */
    val flowCrescentBulgeFraction: Float = 0f,
    /** 月牙朝哪一侧鼓出。 */
    val flowCrescentDirection: FlowCrescentDirection = FlowCrescentDirection.Right,
    val flowShapeCurve: FlowShapeCurve = FlowShapeCurve.Crescent,
    /**
     * 星环格心间距（= 正方格边长）。0 时按 [nativeRingCellSizePx] 现算。
     *
     * 与蜂窝 / 心流那两项不同，星环这一项**不是** `iconSizePx * 1.02`：星环要吃满可用宽度
     * （`availableWidth / columns`），图标只占格子的一部分（[nativeRingIconSizePx]），
     * 格子之间的空隙由那个比例决定。两个数各自独立，所以必须单列一个字段 ——
     * 让格子跟着图标走的话，列数一多就再也铺不满屏。
     */
    val ringCellSizePx: Float = 0f,
    val denseGridColumns: Int = 5,
    val denseGridHorizontalPaddingPx: Float = 8f,
    val denseGridItemSpacingPx: Float = 4f,
    val denseGridItemAspectRatio: Float = 0.92f,
    val denseGridMinimumItemWidthPx: Float = 48f,
    val denseGridMinimumItemHeightPx: Float = 48f,
    val denseGridMinimumVerticalPaddingPx: Float = 8f,
    val denseGridIconInnerPaddingPx: Float = 3f,
    val centerEdgeItems: Boolean = true,
    val allowFolderCreation: Boolean = true,
    /**
     * 覆层（长按菜单 / 文件夹展开）在场时关掉列表拖拽。覆层是 Compose 层，
     * 底下的 RecyclerView 仍然收得到长按并起拖，会出现「文件夹开着，背景
     * 图标却被拖走」以及拖拽残影粘在背景上。
     */
    val dragEnabled: Boolean = true,
    val iconShadowEnabled: Boolean = true,
    /**
     * 屏幕右侧的滑动进度指示器。
     *
     * 放在 config 而不是 View 的独立 setter 里，是因为它必须和 `deviceShape`、`palette`
     * 一起构成同一份快照：颜色取自 [palette]、弧还是直线取自 [deviceShape]，分两条通道
     * 下发就会出现「主题已经切了、指示器还是上一套颜色」这种一帧不一致。
     */
    val scrollIndicatorEnabled: Boolean = true,
    val edgeSpacingCompressionEnabled: Boolean = true,
    /** Classic (Beta 1.2) keeps content stable; Next animates entry progress. */
    val entryMotionEnabled: Boolean = true,
    /**
     * Monotonic token for a new app-list entry. Compose changes this once per
     * transition; the native host owns the frame clock for the actual motion.
     */
    val entryAnimationToken: Long = 0L,
    val entryProgress: Float = 1f,
    /** 甩尾入场强度：0 = 全部同时到位，1 = 底部行最多晚 42% 行程。 */
    val entryStaggerStrength: Float = 0f,
    val palette: NativeAppListPalette = NativeAppListPalette(),
    val appScale: Float = 1f,
    val fisheyeEnabled: Boolean = false,
    val fisheyeRange: Float = 0.4f,
    val fisheyeStrength: Float = 1f,
    val topFadeRangePx: Float = 0f,
    val bottomFadeRangePx: Float = 0f,
    /** Draw the edge mask in this View; production hosts place it outside blur. */
    val drawEdgeFadeMask: Boolean = true,
    val topBlurRadiusPx: Float = 0f,
    val bottomBlurRadiusPx: Float = 0f,
    val contentOverlayBlurRadiusPx: Float = 0f,
    /**
     * 覆层背景模糊（长按菜单 / 文件夹打开）只跟「高级材质」走；以前被
     * 「边缘模糊」开关经 blurTier 连坐关闭——Compose 版菜单背后一直有模糊。
     */
    val contentOverlayBlurEligible: Boolean = false,
    /** Shared DrawerLayoutEngine profile; defaults to the Flue Next curve. */
    val fisheyePreset: NativeFisheyePreset = NativeFisheyePreset.NEXT,
    val deviceShape: NativeDeviceShape = NativeDeviceShape.RECTANGULAR,
    val topSafeInsetPx: Int = 0,
    val rightSafeInsetPx: Int = 0,
    val bottomSafeInsetPx: Int = 0,
    val blurTier: NativeBlurTier = NativeBlurTier.OFF,
    val rotaryHapticsEnabled: Boolean = true,
    val theme: NativeAppListTheme = NativeAppListTheme.DARK,
    val rowBorder: NativeAppListRowBorder = NativeAppListRowBorder(),
    /** Selected profile snapshot; native clocks consume it without Compose frames. */
    val motionProfile: FlueMotionProfile = FlueMotionProfiles.Gentle,
){
    /**
     * Frame-local entry progress owned by [NativeAppListView]. It deliberately
     * lives outside the data-class constructor so changing it does not
     * invalidate a config snapshot or cause Adapter/Compose work on every
     * animation frame. Legacy callers can still seed [entryProgress].
     */
    @Transient
    internal var runtimeEntryProgress: Float = entryProgress

    /** Runtime-only GPU load shedding while RecyclerView is dragging/flinging. */
    @Transient
    internal var runtimeBlurSuppressed: Boolean = false
}

internal fun NativeAppListConfig.effectiveBlurTier(): NativeBlurTier =
    if (runtimeBlurSuppressed) NativeBlurTier.OFF else blurTier

internal class NativeAppListResolvedConfig private constructor(
    val value: NativeAppListConfig,
) {
    companion object {
        fun from(config: NativeAppListConfig): NativeAppListResolvedConfig =
            NativeAppListResolvedConfig(config.legalized())
    }
}

/** Fields consumed by [NativeAppListRowView] drawing/measurement, not LayoutManager visuals. */
internal fun hasDifferentNativeRowRendering(
    current: NativeAppListConfig,
    next: NativeAppListConfig,
): Boolean =
    current.presentation != next.presentation ||
        current.leftSafeInsetPx != next.leftSafeInsetPx ||
        current.rowHeightPx != next.rowHeightPx ||
        current.iconSizePx != next.iconSizePx ||
        current.rowPaddingStartPx != next.rowPaddingStartPx ||
        current.rowPaddingTopPx != next.rowPaddingTopPx ||
        current.rowPaddingEndPx != next.rowPaddingEndPx ||
        current.rowPaddingBottomPx != next.rowPaddingBottomPx ||
        current.labelTextSizePx != next.labelTextSizePx ||
        current.iconLabelGapPx != next.iconLabelGapPx ||
        current.iconShape != next.iconShape ||
        current.iconCornerRadiusPx != next.iconCornerRadiusPx ||
        current.iconShadowEnabled != next.iconShadowEnabled ||
        current.honeycombCellSizePx != next.honeycombCellSizePx ||
        // 星环的格边长决定 onMeasure 出来的格子大小与图标内嵌比例，
        // 改了必须重新走行内测量，否则新列数下图标还按旧格心画。
        current.ringCellSizePx != next.ringCellSizePx ||
        current.denseGridIconInnerPaddingPx != next.denseGridIconInnerPaddingPx ||
        current.palette != next.palette ||
        current.rowBorder != next.rowBorder

internal fun hasDifferentNativeEdgeFade(
    current: NativeAppListConfig,
    next: NativeAppListConfig,
): Boolean =
    current.topFadeRangePx != next.topFadeRangePx ||
        current.bottomFadeRangePx != next.bottomFadeRangePx ||
        current.drawEdgeFadeMask != next.drawEdgeFadeMask ||
        current.palette.fadeEdgeColorArgb != next.palette.fadeEdgeColorArgb

internal fun hasDifferentNativeContentOverlayEffect(
    current: NativeAppListConfig,
    next: NativeAppListConfig,
): Boolean =
    current.blurTier != next.blurTier ||
        current.contentOverlayBlurRadiusPx != next.contentOverlayBlurRadiusPx ||
        current.contentOverlayBlurEligible != next.contentOverlayBlurEligible

/**
 * 指示器重绘 / 换笔触的判据。
 *
 * 单列一条而不是塞进 [hasDifferentNativeRowRendering]：那条会连带 `applyConfigToAttachedRows`
 * 把每个可见行的配置重新灌一遍，而指示器只画在宿主自己的 `dispatchDraw` 上，
 * 换个颜色不该让所有行重新测量。
 */
internal fun hasDifferentNativeScrollIndicator(
    current: NativeAppListConfig,
    next: NativeAppListConfig,
): Boolean =
    current.scrollIndicatorEnabled != next.scrollIndicatorEnabled ||
        current.deviceShape != next.deviceShape ||
        current.palette.labelColorArgb != next.palette.labelColorArgb

internal fun NativeAppListConfig.legalized(): NativeAppListConfig {
    val safeIconSize = iconSizePx.positiveMetricOr(DEFAULT_ICON_SIZE_PX)
    val safePaddingTop = rowPaddingTopPx.nonNegativeMetric()
    val safePaddingBottom = rowPaddingBottomPx.nonNegativeMetric()
    val minimumRowHeight =
        safeIconSize + safePaddingTop + safePaddingBottom
    val safeRowHeight = (
        rowHeightPx
            .takeIf { it > 0 }
            ?.coerceAtMost(MAX_ROW_HEIGHT_PX)
            ?: DEFAULT_ROW_HEIGHT_PX
        ).coerceAtLeast(minimumRowHeight)

    return copy(
        initialResetToken = initialResetToken.coerceAtLeast(0L),
        leftSafeInsetPx = leftSafeInsetPx.nonNegativeMetric(),
        rowHeightPx = safeRowHeight,
        iconSizePx = safeIconSize,
        rowPaddingStartPx = rowPaddingStartPx.nonNegativeMetric(),
        rowPaddingTopPx = safePaddingTop,
        rowPaddingEndPx = rowPaddingEndPx.nonNegativeMetric(),
        rowPaddingBottomPx = safePaddingBottom,
        labelTextSizePx = labelTextSizePx
            .finiteOr(DEFAULT_LABEL_TEXT_SIZE_PX)
            .coerceIn(MIN_POSITIVE_DRAWING_VALUE, MAX_NATIVE_METRIC_PX.toFloat()),
        iconLabelGapPx = iconLabelGapPx.nonNegativeMetric(),
        iconCornerRadiusPx = iconCornerRadiusPx.nonNegativeFiniteMetric(),
        honeycombCellSizePx = honeycombCellSizePx.nonNegativeFiniteMetric(),
        honeycombNarrowColumns = honeycombNarrowColumns.coerceIn(1, 10),
        honeycombFisheyeRangeRows = honeycombFisheyeRangeRows.coerceIn(1, 8),
        flowColumns = flowColumns.coerceIn(1, 10),
        flowCellSizePx = flowCellSizePx.nonNegativeFiniteMetric(),
        flowCrescentBulgeFraction = flowCrescentBulgeFraction
            .nonNegativeFiniteMetric()
            .coerceAtMost(NativeFlowGeometry.MAX_CRESCENT_BULGE_FRACTION),
        ringCellSizePx = ringCellSizePx.nonNegativeFiniteMetric(),
        denseGridColumns = denseGridColumns.coerceIn(1, 10),
        denseGridHorizontalPaddingPx = denseGridHorizontalPaddingPx.nonNegativeFiniteMetric(),
        denseGridItemSpacingPx = denseGridItemSpacingPx.nonNegativeFiniteMetric(),
        denseGridItemAspectRatio = denseGridItemAspectRatio
            .finiteOr(DEFAULT_DENSE_GRID_ASPECT_RATIO)
            .coerceIn(MIN_POSITIVE_DRAWING_VALUE, MAX_DENSE_GRID_ASPECT_RATIO),
        denseGridMinimumItemWidthPx = denseGridMinimumItemWidthPx
            .positiveFiniteMetricOr(DEFAULT_DENSE_GRID_MINIMUM_ITEM_PX),
        denseGridMinimumItemHeightPx = denseGridMinimumItemHeightPx
            .positiveFiniteMetricOr(DEFAULT_DENSE_GRID_MINIMUM_ITEM_PX),
        denseGridMinimumVerticalPaddingPx =
            denseGridMinimumVerticalPaddingPx.nonNegativeFiniteMetric(),
        denseGridIconInnerPaddingPx = denseGridIconInnerPaddingPx.nonNegativeFiniteMetric(),
        entryAnimationToken = entryAnimationToken.coerceAtLeast(0L),
        entryProgress = entryProgress.finiteOr(1f).coerceIn(0f, 1f),
        entryStaggerStrength = entryStaggerStrength.finiteOr(0f).coerceIn(0f, 1f),
        palette = palette.legalized(),
        appScale = appScale
            .finiteOr(1f)
            .coerceIn(MIN_POSITIVE_DRAWING_VALUE, MAX_APP_SCALE),
        fisheyeRange = fisheyeRange
            .finiteOr(DEFAULT_FISHEYE_RANGE)
            .coerceIn(MIN_FISHEYE_RANGE, 1f),
        fisheyeStrength = fisheyeStrength
            .finiteOr(0f)
            .coerceIn(0f, 2f),
        topFadeRangePx = topFadeRangePx.nonNegativeFiniteMetric(),
        bottomFadeRangePx = bottomFadeRangePx.nonNegativeFiniteMetric(),
        drawEdgeFadeMask = drawEdgeFadeMask,
        topBlurRadiusPx = topBlurRadiusPx.nonNegativeFiniteMetric(),
        bottomBlurRadiusPx = bottomBlurRadiusPx.nonNegativeFiniteMetric(),
        contentOverlayBlurRadiusPx = contentOverlayBlurRadiusPx.nonNegativeFiniteMetric(),
        topSafeInsetPx = topSafeInsetPx.nonNegativeMetric(),
        rightSafeInsetPx = rightSafeInsetPx.nonNegativeMetric(),
        bottomSafeInsetPx = bottomSafeInsetPx.nonNegativeMetric(),
        motionProfile = motionProfile.nativeSafeProfile(),
        rowBorder = rowBorder.copy(
            colorArgb = rowBorder.colorArgb.sanitizedArgb(),
            widthPx = rowBorder.widthPx
                .finiteOr(0f)
                .coerceIn(0f, MAX_NATIVE_METRIC_PX.toFloat()),
            cornerRadiusPx = rowBorder.cornerRadiusPx
                .finiteOr(0f)
                .coerceIn(0f, MAX_NATIVE_METRIC_PX.toFloat()),
        ),
    )
}

private fun NativeAppListPalette.legalized(): NativeAppListPalette = copy(
    rowBackgroundColorArgb = rowBackgroundColorArgb.sanitizedArgb(),
    labelColorArgb = labelColorArgb.sanitizedArgb(),
    pressedOverlayColorArgb = pressedOverlayColorArgb.sanitizedArgb(),
    fallbackIconBackgroundColorArgb = fallbackIconBackgroundColorArgb.sanitizedArgb(),
    fallbackIconForegroundColorArgb = fallbackIconForegroundColorArgb.sanitizedArgb(),
    fadeEdgeColorArgb = fadeEdgeColorArgb.sanitizedArgb(),
)

private fun Int.nonNegativeMetric(): Int = coerceIn(0, MAX_NATIVE_METRIC_PX)

private fun Int.positiveMetricOr(fallback: Int): Int =
    takeIf { it > 0 }?.coerceAtMost(MAX_NATIVE_METRIC_PX) ?: fallback

private fun Float.finiteOr(fallback: Float): Float = takeIf(Float::isFinite) ?: fallback

private fun Float.nonNegativeFiniteMetric(): Float =
    finiteOr(0f).coerceIn(0f, MAX_NATIVE_METRIC_PX.toFloat())

private fun Float.positiveFiniteMetricOr(fallback: Float): Float =
    takeIf { it.isFinite() && it > 0f }
        ?.coerceAtMost(MAX_NATIVE_METRIC_PX.toFloat())
        ?: fallback

private fun Long.sanitizedArgb(): Long = this and 0xffffffffL

private const val MAX_NATIVE_METRIC_PX = 4096
private const val MAX_ROW_HEIGHT_PX = MAX_NATIVE_METRIC_PX * 3
private const val MIN_POSITIVE_DRAWING_VALUE = 0.01f
private const val MAX_APP_SCALE = 4f
private const val MIN_FISHEYE_RANGE = 0.01f
private const val DEFAULT_FISHEYE_RANGE = 0.4f
private const val DEFAULT_ROW_HEIGHT_PX = 64
private const val DEFAULT_ICON_SIZE_PX = 40
private const val DEFAULT_HORIZONTAL_PADDING_PX = 16
private const val DEFAULT_VERTICAL_PADDING_PX = 8
private const val DEFAULT_LABEL_TEXT_SIZE_PX = 16f
private const val DEFAULT_ICON_LABEL_GAP_PX = 12
private const val DEFAULT_DENSE_GRID_ASPECT_RATIO = 0.92f
private const val DEFAULT_DENSE_GRID_MINIMUM_ITEM_PX = 48f
private const val MAX_DENSE_GRID_ASPECT_RATIO = 10f

internal data class NativeLinearChildVisual(
    var scale: Float,
    var alpha: Float,
    var blurRadiusPx: Float = 0f,
    var translationY: Float = 0f,
    var saturation: Float = 1f,
)

internal fun nativeLinearChildVisual(
    childCenterY: Float,
    viewportHeight: Int,
    config: NativeAppListConfig,
    output: NativeLinearChildVisual = NativeLinearChildVisual(scale = 1f, alpha = 1f),
    edgeCenterY: Float = childCenterY,
): NativeLinearChildVisual {
    val baseScale = config.appScale.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 1f
    if (!childCenterY.isFinite() || viewportHeight <= 0) {
        output.scale = baseScale
        output.alpha = 1f
        output.blurRadiusPx = 0f
        output.translationY = 0f
        output.saturation = 1f
        return output
    }

    val effectProgress = nativeLinearFisheyeProgress(
        childCenterY = childCenterY,
        viewportHeight = viewportHeight,
        config = config,
    )

    val topEdgeProgress = edgeProgress(
        coordinate = edgeCenterY,
        edgeRange = config.topFadeRangePx,
    )
    val bottomEdgeProgress = edgeProgress(
        coordinate = viewportHeight - edgeCenterY,
        edgeRange = config.bottomFadeRangePx,
    )
    val fadeProgress = maxOf(topEdgeProgress, bottomEdgeProgress)
    val entryProgress = nativeStaggeredEntryProgress(
        config = config,
        childCenterY = childCenterY,
        viewportHeight = viewportHeight.toFloat(),
    )
    // 位移与缩放吃回弹（可以过冲），透明度只吃原始进度（>1 无意义）。
    val entryEased = nativeEntryOvershoot(entryProgress)
    val entryOffset = (1f - entryEased) * 0.08f * viewportHeight
    val entryScale = nativeEntryScale(entryEased)
    val edgeScaleCoefficient = when (config.fisheyePreset) {
        NativeFisheyePreset.CLASSIC -> 0.42f
        NativeFisheyePreset.NEXT -> 0.55f
    }
    val edgeAlphaCoefficient = when (config.fisheyePreset) {
        NativeFisheyePreset.CLASSIC -> 0.35f
        NativeFisheyePreset.NEXT -> 0.45f
    }
    val fisheyeScale = (1f - edgeScaleCoefficient * effectProgress).coerceIn(0.16f, 1f)
    val fisheyeAlpha = (1f - edgeAlphaCoefficient * effectProgress).coerceIn(0.10f, 1f)
    output.scale = baseScale * fisheyeScale * entryScale
    val compatibilityFade = if (config.blurTier == NativeBlurTier.LITE) {
        nativeLiteEdgeAlpha(fadeProgress)
    } else {
        1f
    }
    output.alpha = fisheyeAlpha * compatibilityFade * entryProgress
    output.blurRadiusPx = if (config.blurTier == NativeBlurTier.SYSTEM) {
        maxOf(
            config.topBlurRadiusPx * topEdgeProgress,
            config.bottomBlurRadiusPx * bottomEdgeProgress,
        )
    } else {
        0f
    }
    output.translationY = entryOffset
    output.saturation = nativeLiteEdgeSaturation(fadeProgress, config.blurTier)
    return output
}

private fun nativeLinearFisheyeProgress(
    childCenterY: Float,
    viewportHeight: Int,
    config: NativeAppListConfig,
): Float {
    if (!config.fisheyeEnabled || !childCenterY.isFinite() || viewportHeight <= 0) return 0f
    val halfHeight = viewportHeight / 2f
    val normalizedDistance = (abs(childCenterY - halfHeight) / halfHeight).coerceIn(0f, 1f)
    val range = config.fisheyeRange.takeIf(Float::isFinite)?.coerceIn(0.01f, 1f) ?: 0.4f
    val strength = config.fisheyeStrength.takeIf(Float::isFinite)?.coerceIn(0f, 2f) ?: 0f
    return when (config.fisheyePreset) {
        NativeFisheyePreset.CLASSIC -> (normalizedDistance / range).coerceIn(0f, 1f) * strength
        NativeFisheyePreset.NEXT -> {
            val edgeStart = 1f - range
            val linearProgress = ((normalizedDistance - edgeStart) / range).coerceIn(0f, 1f)
            linearProgress * linearProgress * (3f - 2f * linearProgress) * strength
        }
    }
}

/**
 * 入场进度的回弹映射。
 *
 * ## 为什么要它
 *
 * 入场以前是**线性**地把偏移和缩放推到位，观感上是「滑进来然后停住」，没有落定感。
 * 老师的原话是「给加上个回弹和大小缩放就差不多」。
 *
 * ## 为什么不是现成的 back-out / OvershootInterpolator
 *
 * 那类曲线是给**时间轴**用的，而动画器（`startEntryAnimation`）已经把
 * `motionProfile.appListEnter.easing` 施加在时间上了。再套一条 ease-out 就是双重缓动：
 * 实测 t=0.5 时值已经到 0.987 —— 动画前半程就基本结束，甩尾（[nativeStaggeredEntryProgress]
 * 的逐行延迟）跟着一起被压没了，等于白做。
 *
 * 所以这里做的是**值域**上的回弹：前 [OVERSHOOT_RAMP] 段保持线性（甩尾的行间差异因此
 * 完整保留），到位之后用一个半周正弦的衰减包把值顶过 1 再落回。返回值**可以大于 1**，
 * 那正是过冲的那一段；调用方按用途决定要不要夹（位移和缩放保留过冲，透明度不能超 1）。
 */
internal fun nativeEntryOvershoot(progress: Float): Float {
    val t = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    if (t <= OVERSHOOT_RAMP) return t / OVERSHOOT_RAMP
    // 回弹段：单个衰减到 0 的正弦鼓包，端点严格回到 1。
    val settle = (t - OVERSHOOT_RAMP) / (1f - OVERSHOOT_RAMP)
    val envelope = 1f - settle
    return 1f + OVERSHOOT_AMPLITUDE * kotlin.math.sin(Math.PI.toFloat() * settle) * envelope
}

/** 入场缩放：从 [ENTRY_MIN_SCALE] 弹到 1，过冲夹在 [ENTRY_MAX_SCALE] 以内。 */
internal fun nativeEntryScale(easedProgress: Float): Float =
    (ENTRY_MIN_SCALE + (1f - ENTRY_MIN_SCALE) * easedProgress)
        .coerceIn(ENTRY_MIN_SCALE, ENTRY_MAX_SCALE)

/** 线性推进段占整段时长的比例，其余留给回弹。 */
private const val OVERSHOOT_RAMP = 0.82f

/** 过冲幅度。手表屏幕小、图标密，超过 8% 会被看成抖动。 */
private const val OVERSHOOT_AMPLITUDE = 0.06f
private const val ENTRY_MIN_SCALE = 0.90f
private const val ENTRY_MAX_SCALE = 1.05f

/**
 * 甩尾入场：按子项的纵向位置给全局入场进度加延迟 —— 第一行先入屏，
 * 越往下越慢，最后一行也保证在全局进度 1.0 时到位。位置是连续量，
 * 三种布局（线性 / 蜂窝 / 密集网格）不需要各算行号。
 */
internal fun nativeStaggeredEntryProgress(
    config: NativeAppListConfig,
    childCenterY: Float,
    viewportHeight: Float,
): Float {
    val global = if (config.entryMotionEnabled) {
        config.runtimeEntryProgress.coerceIn(0f, 1f)
    } else {
        return 1f
    }
    val strength = config.entryStaggerStrength
    if (strength <= 0f || viewportHeight <= 0f || !childCenterY.isFinite()) return global
    val position = (childCenterY / viewportHeight).coerceIn(0f, 1f)
    val maxDelay = (0.42f * strength).coerceIn(0f, 0.6f)
    val delay = maxDelay * position
    return ((global - delay) / (1f - maxDelay)).coerceIn(0f, 1f)
}

internal fun nativeEntryTranslationY(
    config: NativeAppListConfig,
    viewportHeight: Float,
): Float {
    if (!config.entryMotionEnabled || !viewportHeight.isFinite() || viewportHeight <= 0f) {
        return 0f
    }
    return (1f - config.runtimeEntryProgress.coerceIn(0f, 1f)) *
        0.08f * viewportHeight
}

internal fun nativeInteractionScale(
    pressed: Boolean,
    dragged: Boolean,
    folderHover: Boolean,
    glidePressed: Boolean = false,
    presentation: NativeAppListPresentation = NativeAppListPresentation.LINEAR,
): Float = when {
    dragged -> 0.965f
    glidePressed -> 0.992f
    // 文件夹候选高亮只保留 pressOverlayPath 上色，不再缩放——
    // 缩小到 0.90 会被用户感知成「图标在躲避」。
    folderHover -> 1f
    // Compose 版的按压分档：蜂窝图标 0.90、密集网格 0.96、列表行 0.97。
    // 原生化时被压成了一刀切的 0.97，蜂窝图标的按压反馈基本不可见。
    pressed -> when (presentation) {
        NativeAppListPresentation.HONEYCOMB,
        // 心流也是纯图标格，按压反馈与蜂窝同档。
        NativeAppListPresentation.FLOW,
        // 星环同理：格子里就是一个圆图标，按压要看得见就得缩到 0.90。
        NativeAppListPresentation.RING -> 0.90f
        NativeAppListPresentation.DENSE_GRID -> 0.96f
        NativeAppListPresentation.LINEAR -> 0.97f
    }
    else -> 1f
}

// The unbounded fallback radius is deliberate for the non-honeycomb layouts:
// nearest-neighbour distances are measured against translation-inclusive child
// centres, so after a preview move the latched target sits directly under the
// pointer and the machine's de-dupe keeps it quiet — a parked pointer is
// stable. Bounding the radius instead regressed legitimate drops whose pointer
// sat between cells (or whose layout shifted mid-gesture) by making
// reorderKeyAt return null on release.
internal fun nativeSpatialReorderRadiusPx(config: NativeAppListConfig): Float =
    if (config.presentation == NativeAppListPresentation.HONEYCOMB) {
        config.honeycombCellSizePx.takeIf { it.isFinite() && it > 0f }
            ?.times(0.95f)
            ?: config.iconSizePx.coerceAtLeast(1) * 0.95f
    } else {
        Float.MAX_VALUE
    }

internal class NativeSpatialChildVisual(
    var scale: Float = 1f,
    var alpha: Float = 1f,
    var translationX: Float = 0f,
    var translationY: Float = 0f,
    var blurRadiusPx: Float = 0f,
    var saturation: Float = 1f,
) {
    fun set(
        scale: Float,
        alpha: Float,
        translationX: Float,
        translationY: Float,
        blurRadiusPx: Float,
        saturation: Float = 1f,
    ): NativeSpatialChildVisual {
        this.scale = scale
        this.alpha = alpha
        this.translationX = translationX
        this.translationY = translationY
        this.blurRadiusPx = blurRadiusPx
        this.saturation = saturation
        return this
    }
}

/**
 * 左侧安全内缩，夹到视口内。
 *
 * 抽出来是为了让左右两侧只有一处夹取实现 —— 原先五个几何各写一遍
 * `leftSafeInsetPx.coerceIn(0, viewportWidthPx - 1)`，右侧那份则压根不存在。
 */
internal fun nativeSafeLeftPx(leftSafeInsetPx: Int, viewportWidthPx: Float): Float {
    if (!viewportWidthPx.isFinite() || viewportWidthPx <= 0f) return 0f
    return leftSafeInsetPx.coerceAtLeast(0).toFloat()
        .coerceAtMost((viewportWidthPx - 1f).coerceAtLeast(0f))
}

/**
 * 内容真正能用的横向宽度 = 视口宽 − 左安全内缩 − 右安全内缩。
 *
 * 右侧那一份以前全体系恒为 0（`NativeAppListConfig.rightSafeInsetPx` 从来没被赋过非零值），
 * 于是滚动指示器画在哪里、图标就压在哪里。心流最明显：它的基准 X 被刻意定成
 * 「月牙峰值贴住可用区右边界」，可用区不减掉指示器就永远贴着最右。
 *
 * 所有布局都必须走这一个函数，不要各自再减一遍 —— 两处夹取不一致会给
 * `SafeOutline.create` 送进互相冲突的 safeBounds，圆屏布局期直接 require 失败。
 */
internal fun nativeSafeContentWidthPx(
    leftSafeInsetPx: Int,
    rightSafeInsetPx: Int,
    viewportWidthPx: Float,
): Float {
    if (!viewportWidthPx.isFinite() || viewportWidthPx <= 0f) return 1f
    val left = nativeSafeLeftPx(leftSafeInsetPx, viewportWidthPx)
    val right = rightSafeInsetPx.coerceAtLeast(0).toFloat()
        .coerceAtMost((viewportWidthPx - left - 1f).coerceAtLeast(0f))
    return (viewportWidthPx - left - right).coerceAtLeast(1f)
}

/** [nativeSafeContentWidthPx] 的整数视口重载，给几何层用。 */
internal fun nativeSafeContentWidthPx(
    leftSafeInsetPx: Int,
    rightSafeInsetPx: Int,
    viewportWidthPx: Int,
): Float = nativeSafeContentWidthPx(
    leftSafeInsetPx = leftSafeInsetPx,
    rightSafeInsetPx = rightSafeInsetPx,
    viewportWidthPx = viewportWidthPx.toFloat(),
)

internal fun nativeHoneycombChildVisual(
    centerX: Float,
    centerY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    config: NativeAppListConfig,
    output: NativeSpatialChildVisual = NativeSpatialChildVisual(),
    /**
     * 只按**垂直**距离算鱼眼半径，忽略横向偏移。心流布局要传 true。
     *
     * 蜂窝的图标网格是绕着屏幕中心铺开的，二维半径就是它想要的「离中心越远越小」。
     * 心流不是：它是一列（或几列）竖着排、再整体按月牙横向推开，而基准列的 x 被
     * [NativeFlowGeometry] 故意放在「月牙峰值正好贴边」的位置 —— 也就是**恒定地**
     * 偏离屏幕中线。二维半径于是给整列加了一份常量惩罚，并且把亮度和月牙的横向
     * 位移绑在了一起：月牙峰（视觉最右）恰好落在垂直中心 → 最亮；月牙两端（视觉
     * 最左）落在垂直边缘 → 最暗。老师报的「心流越靠左越暗淡越透明，一列时右边
     * 更亮」就是这个耦合，不是真的在按横坐标调亮度。
     *
     * 去掉横向分量之后，心流的鱼眼变成纯粹的「离垂直中心越远越小」，左右对称，
     * 月牙只管位移不再影响明暗。缩放本身保留 —— 那是老师要的效果。
     */
    verticalOnlyFisheye: Boolean = false,
): NativeSpatialChildVisual {
    if (
        viewportWidth <= 0f || viewportHeight <= 0f ||
        !centerX.isFinite() || !centerY.isFinite()
    ) {
        return output.set(1f, 1f, 0f, 0f, 0f, 1f)
    }
    val contentWidth = nativeSafeContentWidthPx(
        leftSafeInsetPx = config.leftSafeInsetPx,
        rightSafeInsetPx = config.rightSafeInsetPx,
        viewportWidthPx = viewportWidth,
    )
    val screenCenterX = nativeSafeLeftPx(config.leftSafeInsetPx, viewportWidth) +
        contentWidth / 2f
    val screenCenterY = viewportHeight / 2f
    val strength = config.fisheyeStrength.coerceIn(0f, 2f)
    val minimumScale = (1f - 0.42f * strength).coerceIn(0.16f, 1f)
    val screenRadius = minOf(contentWidth, viewportHeight) / 2f
    val maximumDistance = screenRadius *
        (config.honeycombFisheyeRangeRows.coerceIn(1, 8) * 0.42f).coerceAtLeast(0.42f)
    val distance = if (verticalOnlyFisheye) {
        abs(centerY - screenCenterY)
    } else {
        hypot(centerX - screenCenterX, centerY - screenCenterY)
    }
    val scale = if (config.fisheyeEnabled && maximumDistance > 0f) {
        val progress = (distance / maximumDistance).coerceIn(0f, 1f)
        1f - (1f - minimumScale) * progress
    } else {
        1f
    }
    val edgeProgress = (abs(centerY - screenCenterY) / (viewportHeight / 2f)).coerceIn(0f, 1f)
    val easedEdge = edgeProgress * edgeProgress * (3f - 2f * edgeProgress)
    val compression = if (
        config.fisheyeEnabled && config.edgeSpacingCompressionEnabled
    ) {
        (0.18f * strength * easedEdge).coerceIn(0f, 0.32f)
    } else {
        0f
    }
    val translationX = -(centerX - screenCenterX) * compression
    val entryProgress = nativeStaggeredEntryProgress(
        config = config,
        childCenterY = centerY,
        viewportHeight = viewportHeight.toFloat(),
    )
    // 位移与缩放吃回弹（见 nativeEntryOvershoot），透明度只吃原始进度。
    val entryEased = nativeEntryOvershoot(entryProgress)
    val translationY = (1f - entryEased) * 0.08f * viewportHeight
    val topProgress = edgeProgress(centerY + translationY, config.topFadeRangePx)
    val bottomProgress = edgeProgress(
        viewportHeight - centerY - translationY,
        config.bottomFadeRangePx,
    )
    val blur = if (config.blurTier == NativeBlurTier.SYSTEM) {
        maxOf(
            config.topBlurRadiusPx * topProgress,
            config.bottomBlurRadiusPx * bottomProgress,
        )
    } else {
        0f
    }
    val fadeProgress = maxOf(topProgress, bottomProgress)
    val compatibilityFade = if (config.blurTier == NativeBlurTier.LITE) {
        nativeLiteEdgeAlpha(fadeProgress)
    } else {
        1f
    }
    // 图标格也要跟着入场缩放：以前这两条路只做位移，图标是「平移进来」而不是
    // 「弹出来」（老师要的大小缩放）。
    return output.set(
        scale = scale * nativeEntryScale(entryEased),
        alpha = (if (config.fisheyeEnabled) scale.coerceIn(0.24f, 1f) else 1f) *
            entryProgress * compatibilityFade,
        translationX = translationX,
        translationY = translationY,
        blurRadiusPx = blur,
        saturation = nativeLiteEdgeSaturation(fadeProgress, config.blurTier),
    )
}

/**
 * 心流的月牙偏移：整行按离屏幕中线的距离往一侧平移多少像素。
 *
 * 偏移恒为 `0..bulge`（不是 ±bulge）：基准 x 已经由 [NativeFlowGeometry] 放在
 * 「峰值正好贴边」的位置，这里只负责从那条边往回收。于是任何一行都不会越过屏幕边界。
 *
 * 单独一个函数、不并进子项视觉里，是因为圆屏那条路会用 [NativeDrawerGeometryAdapter]
 * **整体替换** translationX/Y（把图标收进圆内的安全轮廓）。弧要是先加进视觉里，圆屏上
 * 就会被那次替换直接吃掉 —— 而圆屏正是目标机型。所以调用方在**选定基准 translationX
 * 之后**再叠这一项。
 *
 * 鱼眼刻意仍按**布局位置**算，不看月牙推完之后的 x：若让鱼眼看见月牙，鼓出最多的中间行
 * 离屏心最远、于是被缩得最小，正好跟月牙对着干 —— 弧会被自己的缩放吃掉。
 *
 * @param bulgePx 必须是 [NativeFlowGeometry.crescentBulgePx] 那份**夹过**的值。
 */
internal fun nativeFlowCrescentTranslationX(
    bulgePx: Float,
    toRight: Boolean,
    childCenterY: Float,
    viewportHeight: Float,
    curve: FlowShapeCurve = FlowShapeCurve.Crescent,
): Float = flowCrescentOffsetPx(
    rowCenterY = childCenterY,
    viewportCenterY = viewportHeight / 2f,
    viewportHeight = viewportHeight,
    bulgePx = bulgePx,
    direction = if (toRight) FlowCrescentDirection.Right else FlowCrescentDirection.Left,
    curve = curve,
)

internal fun nativeDenseGridChildVisual(
    centerX: Float,
    centerY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    config: NativeAppListConfig,
    output: NativeSpatialChildVisual = NativeSpatialChildVisual(),
): NativeSpatialChildVisual {
    if (
        viewportWidth <= 0f || viewportHeight <= 0f ||
        !centerX.isFinite() || !centerY.isFinite()
    ) {
        return output.set(1f, 1f, 0f, 0f, 0f, 1f)
    }

    val safeLeft = nativeSafeLeftPx(config.leftSafeInsetPx, viewportWidth)
    val screenCenterX = safeLeft + nativeSafeContentWidthPx(
        leftSafeInsetPx = config.leftSafeInsetPx,
        rightSafeInsetPx = config.rightSafeInsetPx,
        viewportWidthPx = viewportWidth,
    ) / 2f
    val screenCenterY = viewportHeight / 2f
    val bottomFisheyeStartY = viewportHeight * 0.52f
    val topFisheyeStartY = viewportHeight * 0.48f
    val columns = config.denseGridColumns.coerceIn(1, 10)
    val rangeRows = config.honeycombFisheyeRangeRows.coerceIn(1, 8)
    val minimumRange = viewportHeight / columns
    val fisheyeRange = (minimumRange * rangeRows).coerceIn(minimumRange, viewportHeight)
    val strength = config.fisheyeStrength.coerceIn(0f, 2f)
    val minimumScale = (1f - 0.42f * strength).coerceIn(0.16f, 1f)
    val inBottomBand = config.fisheyeEnabled && centerY > bottomFisheyeStartY
    val inTopBand = config.fisheyeEnabled &&
        config.honeycombTopFisheyeEnabled &&
        centerY < topFisheyeStartY
    val fisheyeBottomProgress = if (inBottomBand && fisheyeRange > 0f) {
        ((centerY - bottomFisheyeStartY) / fisheyeRange).coerceIn(0f, 1f)
    } else {
        0f
    }
    val fisheyeTopProgress = if (inTopBand && fisheyeRange > 0f) {
        ((topFisheyeStartY - centerY) / fisheyeRange).coerceIn(0f, 1f)
    } else {
        0f
    }
    val fisheyeProgress = maxOf(fisheyeBottomProgress, fisheyeTopProgress)
    val fisheyeActive = fisheyeProgress > 0f
    val scale = 1f - (1f - minimumScale) * fisheyeProgress

    val translationX = if (fisheyeActive && config.edgeSpacingCompressionEnabled) {
        val edgeProgress = (
            kotlin.math.abs(centerY - screenCenterY) / (viewportHeight / 2f)
            ).coerceIn(0f, 1f)
        val easedEdge = edgeProgress * edgeProgress * (3f - 2f * edgeProgress)
        val compression = (0.18f * strength * easedEdge).coerceIn(0f, 0.32f)
        -(centerX - screenCenterX) * compression
    } else {
        0f
    }
    val entryProgress = nativeStaggeredEntryProgress(
        config = config,
        childCenterY = centerY,
        viewportHeight = viewportHeight,
    )
    // 位移与缩放吃回弹（见 nativeEntryOvershoot），透明度只吃原始进度。
    val entryEased = nativeEntryOvershoot(entryProgress)
    val translationY = (1f - entryEased) * 0.08f * viewportHeight
    val visualCenterY = centerY + translationY
    val topProgress = edgeProgress(visualCenterY, config.topFadeRangePx)
    val bottomProgress = edgeProgress(
        viewportHeight - visualCenterY,
        config.bottomFadeRangePx,
    )
    val blur = if (config.blurTier == NativeBlurTier.SYSTEM) {
        maxOf(
            config.topBlurRadiusPx * topProgress,
            config.bottomBlurRadiusPx * bottomProgress,
        )
    } else {
        0f
    }
    val fadeProgress = maxOf(topProgress, bottomProgress)
    val compatibilityFade = if (config.blurTier == NativeBlurTier.LITE) {
        nativeLiteEdgeAlpha(fadeProgress)
    } else {
        1f
    }
    val fisheyeAlpha = if (config.fisheyeEnabled) scale.coerceIn(0.24f, 1f) else 1f
    // 图标格也要跟着入场缩放：以前这两条路只做位移，图标是「平移进来」而不是
    // 「弹出来」（老师要的大小缩放）。
    // 图标格也跟着入场缩放：以前这两条路只做位移，图标是「平移进来」而不是「弹出来」。
    return output.set(
        scale = scale * nativeEntryScale(entryEased),
        alpha = fisheyeAlpha * entryProgress * compatibilityFade,
        translationX = translationX,
        translationY = translationY,
        blurRadiusPx = blur,
        saturation = nativeLiteEdgeSaturation(fadeProgress, config.blurTier),
    )
}

/**
 * 星环的径向进度：格心离屏心多远，按内切圆半径归一化。
 *
 * ## 为什么按内切圆而不是「几行」
 *
 * 鱼眼那条「生效行数」在线性 / 蜂窝里是纵向行数，星环是二维的，硬换算成半径会让
 * 同一个数字在不同列数下含义完全不同（列数一多格子就小，四行的半径连屏心那块都盖不住，
 * 弧直接消失）。所以半径固定取屏幕内切圆 —— 屏幕形状是这套布局唯一的几何基准 ——
 * 而「生效行数」改去控制**中心那块不收拢的稳定核**有多大（见 [nativeRingStableCoreFraction]）。
 * 行数越多 = 参与的圈越多 = 稳定核越小 = 弧越深，与滑杆方向一致。
 *
 * 返回值**可以大于 1**：圆屏四个角上的格子本来就在内切圆之外，那一段正是要被收得最狠的。
 * 缩放侧自己夹到 1（否则角上图标会缩到看不见），位移侧刻意不夹（见
 * [nativeRingConvergenceFraction]）。
 */
internal fun nativeRingRadialProgress(
    centerX: Float,
    centerY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    config: NativeAppListConfig,
): Float {
    if (
        viewportWidth <= 0f || viewportHeight <= 0f ||
        !centerX.isFinite() || !centerY.isFinite()
    ) {
        return 0f
    }
    val safeLeft = nativeSafeLeftPx(config.leftSafeInsetPx, viewportWidth)
    val contentWidth = nativeSafeContentWidthPx(
        leftSafeInsetPx = config.leftSafeInsetPx,
        rightSafeInsetPx = config.rightSafeInsetPx,
        viewportWidthPx = viewportWidth,
    )
    val screenCenterX = safeLeft + contentWidth / 2f
    val screenCenterY = viewportHeight / 2f
    val screenRadius = minOf(contentWidth, viewportHeight) / 2f
    if (screenRadius <= 0f) return 0f
    val normalized = hypot(centerX - screenCenterX, centerY - screenCenterY) / screenRadius
    val core = nativeRingStableCoreFraction(config)
    return ((normalized - core) / (1f - core)).coerceAtLeast(0f)
}

/**
 * 直屏星环的上下边缘触发进度。
 *
 * 直屏不是把整张网按二维半径缩放，而是让滚动到上下边缘的行进入鱼眼带。触发带宽度
 * 取一行真实格边长，因此格心在带外时严格返回 0，中心行不会被横向位置误判成鱼眼。
 * 圆屏仍使用 [nativeRingRadialProgress]，不经过这条直屏规则。
 */
internal fun nativeRingFisheyeProgress(
    centerY: Float,
    viewportHeight: Float,
    config: NativeAppListConfig,
    viewportWidth: Float = viewportHeight,
): Float {
    if (
        config.deviceShape != NativeDeviceShape.RECTANGULAR ||
        !config.fisheyeEnabled ||
        !centerY.isFinite() ||
        !viewportHeight.isFinite() ||
        viewportHeight <= 0f
    ) {
        return 0f
    }
    val availableWidth = nativeSafeContentWidthPx(
        leftSafeInsetPx = config.leftSafeInsetPx,
        rightSafeInsetPx = config.rightSafeInsetPx,
        viewportWidthPx = viewportWidth,
    )
    val cellSize = config.ringCellSizePx.takeIf { it.isFinite() && it > 0f }
        ?: availableWidth / config.honeycombNarrowColumns.coerceIn(1, NATIVE_RING_MAX_COLUMNS)
    // A rectangular ring is a tiled grid, not a radial lens. Keep the
    // trigger to the row touching the edge even when the shared honeycomb
    // setting is four or more rows. The setting remains meaningful for the
    // circular ring, while a straight screen stays flat in its stable area.
    val triggerRange = cellSize.coerceAtMost(viewportHeight / 2f)
    if (!triggerRange.isFinite() || triggerRange <= 0f) return 0f
    val distanceToNearestEdge = minOf(centerY, viewportHeight - centerY)
    return ((triggerRange - distanceToNearestEdge) / triggerRange).coerceIn(0f, 1f)
}

/**
 * 中心那块「完全不收拢」的稳定核，占内切圆半径的比例。
 *
 * 由鱼眼的「生效行数」换算：8 行 = 整屏都参与（核为 0），1 行 = 只有最外一圈卷边（核 0.75）。
 * 上限刻意留在 0.75 而不是更高 —— 再大就只剩贴边那一圈动，看起来是「图标被边缘吸住」
 * 而不是一张收拢的网。
 */
private fun nativeRingStableCoreFraction(config: NativeAppListConfig): Float =
    (1f - config.honeycombFisheyeRangeRows.coerceIn(1, 8) / 8f).coerceIn(0f, 0.75f)

/**
 * 圆屏星环的收拢比例：格子往屏心方向移动自身径向距离的百分之几。
 *
 * 形状是 `k·t² / (1 + k·t²)`（桶形畸变那一族），选它有三个原因：
 *
 * 1. **恒小于 1**，所以任何设置下格子都只会靠近屏心，绝不会穿过屏心翻到对面 ——
 *    线性的 `k·t²` 一旦 k 调大就会翻面，那是纯粹的乱码画面；
 * 2. **t 不必夹**：角上的格子（t > 1）继续被收得更狠，正好把圆外那圈拉回可视区。
 *    夹到 1 的话角上格子会永远停在圆外，圆屏那条安全轮廓只能把它们堆在弧边上；
 * 3. 中心附近 ≈ `k·t²`，二次起步，屏心那几个格子几乎不动 —— 弧才有「从中间鼓出去」
 *    的观感，而不是整张网平移。
 *
 * 但 2 有个代价：位移侧靠 t > 1 继续加深，缩放侧的 t 已经夹在 1（[nativeRingChildVisual]），
 * 于是收拢速度会超过缩小速度，相邻两圈的行距被压到小于图标本身 —— 图标叠图标。所以最终
 * 结果再取一道 [nativeRingSpacingCeiling]（行距不得小于视觉图标高度）的上限。
 *
 * 强度取鱼眼强度的一半（0..200% → 0..1）：100% 是出厂档，还留一倍的余量给老师往上调。
 *
 * 这条函数只服务圆屏的径向收拢；直屏触发行使用单独的边缘进度函数和轻量收拢上限。
 * 形状判定仍收口在纯函数的调用层，保证直屏平铺规则可以脱离 View 单测。
 */
internal fun nativeRingConvergenceFraction(
    radialProgress: Float,
    config: NativeAppListConfig,
): Float {
    if (!config.fisheyeEnabled || config.deviceShape != NativeDeviceShape.ROUND) return 0f
    val progress = radialProgress.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: return 0f
    val strength = config.fisheyeStrength.takeIf(Float::isFinite)?.coerceIn(0f, 2f) ?: return 0f
    val curvature = RING_MAX_CURVATURE * (strength / 2f)
    if (curvature <= 0f) return 0f
    val weighted = curvature * progress * progress
    val barrel = weighted / (1f + weighted)
    return minOf(barrel, nativeRingSpacingCeiling(progress, strength, config))
}

/**
 * 收拢比例的**行距上限**：保证收完之后相邻两圈的间距还装得下画出来的图标。
 *
 * ## 为什么必须有这条上限
 *
 * 收拢只压位置、不压格距，压过头就是图标叠图标。而 `k·t²/(1+k·t²)` 这一族的 `t`
 * **刻意不夹**（角上可到 1.8），缩放侧的 `t` 却夹到 1（见 [nativeRingChildVisual]）——
 * 于是「收拢速度」在 t > 1 之后继续加深，「缩小速度」已经停住了。实算 454×454 / 4 列 /
 * 强度 100%：行心间距被从 113.5 压到 51.6（−55%），而两行视觉半高之和是 58.7，
 * **重叠约 7px**。列数越多、强度越大，重叠越深。这就是老师报的「圆屏星环顶上和底下
 * 会有图标重叠」。
 *
 * ## 上限的形状
 *
 * 令 `core` 为中心稳定核占内切圆半径的比例（[nativeRingStableCoreFraction]），
 * `A = core / (1 - core)`。收拢把「离核边缘的径向距离」`(A + t)` 映射成
 * `(A + t)·(1 - f)`。要求这个映射相对原距离**至少保留 s 倍**（s = 一格里图标占的比例
 * × 图标可能被画出的缩放），即 `(A + t)(1 - f) >= A + s·t`，解出来正是
 *
 * ```
 * f <= t·(1 - s) / (A + t)
 * ```
 *
 * 这条上限有三个好处：
 *
 * 1. **闭式、单调**：对 t 严格递增（A > 0 时），所以「越外圈收得越狠」这个观感不变，
 *    只是被封了顶；夹 k 那种做法会把角上收拢从 43% 一路砍到 13%，弧基本消失；
 * 2. `t → ∞` 时趋于 `1 - s`，映射退化成**等比缩放** —— 仍然单调，行距按 s 保留；
 * 3. 只在 `barrel` 越过它时才生效，屏心那一片完全不受影响（barrel 在小 t 是二次的，
 *    这条是线性的，小 t 恒有 barrel 更小）。
 *
 * s 里的「可能被画出的缩放」取 **1 与最小缩放的中值**：约束真正吃紧的是 t ≳ 0.6 那几圈，
 * 那里缩放已经掉到 0.72 以下，用中值是偏保守的一侧；取最小缩放本身则偏乐观（相邻两行
 * 里靠内那行明显大于最小缩放），实算只剩不到 1px 余量，浮点一抖就翻。
 */
private fun nativeRingSpacingCeiling(
    radialProgress: Float,
    strength: Float,
    config: NativeAppListConfig,
): Float {
    val core = nativeRingStableCoreFraction(config)
    // core 的上限夹在 0.75，不可能到 1；这里只是防除零。
    if (core >= 1f) return 0f
    val shoulder = core / (1f - core)
    val iconCellRatio = nativeRingIconCellRatio(config)
    val minimumScale = (1f - 0.42f * strength).coerceIn(0.16f, 1f)
    val requiredStretch = (iconCellRatio * (1f + minimumScale) / 2f).coerceIn(0f, 1f)
    val denominator = shoulder + radialProgress
    if (denominator <= 0f) return 0f
    return (radialProgress * (1f - requiredStretch) / denominator).coerceIn(0f, 1f)
}

/**
 * 图标边长占格边长的比例。
 *
 * `ringCellSizePx` 为 0 表示「格边长按视口现算」（见 [nativeRingCellSizePx]），那时这里
 * 拿不到真实格边长，退回 [RING_ICON_CELL_FRACTION] —— 那本来就是 `nativeRingIconSizePx`
 * 在缩放 100% 时用的比例，两处同源。
 */
private fun nativeRingIconCellRatio(config: NativeAppListConfig): Float {
    val cell = config.ringCellSizePx.takeIf { it.isFinite() && it > 0f }
        ?: return RING_ICON_CELL_FRACTION
    val icon = config.iconSizePx.toFloat().takeIf { it > 0f } ?: return RING_ICON_CELL_FRACTION
    return (icon / cell).coerceIn(0f, 1f)
}

/** 星环收拢位移的两个分量。复用同一个对象，热路径不分配。 */
internal class NativeRingOffset(
    var x: Float = 0f,
    var y: Float = 0f,
) {
    fun set(x: Float, y: Float): NativeRingOffset {
        this.x = x
        this.y = y
        return this
    }
}

/**
 * 星环的收拢位移：仅圆屏按二维径向收拢。
 *
 * ## 圆屏为什么是四条边一起弯
 *
 * 收的是**径向**分量，而径向距离里含着另一个轴的偏移。于是同一行里越靠外的列
 * `t` 越大、被拉得越多 —— 纵向分量把那一行拽成一道弧，横向分量把它收窄。列同理。
 * 一个公式同时给出上、下、左、右四条弧，不需要按边分四段（分段必然在四个角上接不平）。
 *
 * ## 为什么单独一个函数、不并进 [nativeRingChildVisual]
 *
 * 与心流的月牙同一个原因：圆屏那条路会用 [NativeDrawerGeometryAdapter] **整体替换**
 * translationX/Y（把图标收进内切圆的安全轮廓）。位移先加进视觉里就会被那次替换吃掉，
 * 而圆屏正是这套布局的目标机型。调用方的做法是把「收拢后的位置」当作期望格心喂给
 * 那个适配器，再把这一份位移加回它给出的 translation 上（见 [NativeRingLayoutManager]）。
 *
 * ## 直屏为什么不收拢
 *
 * 参考布局里直屏每一排格心始终水平等距，触发行只统一缩放。把各列往屏心拉会让整排
 * 变窄，重新出现蜂窝/径向布局的观感，所以直屏在这里严格返回零位移。
 */
internal fun nativeRingConvergenceOffset(
    centerX: Float,
    centerY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    config: NativeAppListConfig,
    output: NativeRingOffset = NativeRingOffset(),
): NativeRingOffset {
    if (
        viewportWidth <= 0f || viewportHeight <= 0f ||
        !centerX.isFinite() || !centerY.isFinite()
    ) {
        return output.set(0f, 0f)
    }
    if (config.deviceShape != NativeDeviceShape.ROUND) return output.set(0f, 0f)
    val fraction = nativeRingConvergenceFraction(
        radialProgress = nativeRingRadialProgress(
            centerX = centerX,
            centerY = centerY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            config = config,
        ),
        config = config,
    )
    if (fraction <= 0f) return output.set(0f, 0f)
    val safeLeft = nativeSafeLeftPx(config.leftSafeInsetPx, viewportWidth)
    val screenCenterX = safeLeft + nativeSafeContentWidthPx(
        leftSafeInsetPx = config.leftSafeInsetPx,
        rightSafeInsetPx = config.rightSafeInsetPx,
        viewportWidthPx = viewportWidth,
    ) / 2f
    val screenCenterY = viewportHeight / 2f
    return output.set(
        x = -(centerX - screenCenterX) * fraction,
        y = -(centerY - screenCenterY) * fraction,
    )
}

/**
 * 星环的子项视觉：圆屏径向鱼眼、直屏边缘鱼眼 + 入场动效 + 边缘模糊。
 *
 * 圆屏缩放曲线与蜂窝同档（`1 - 0.42·强度` 的最小缩放），按二维径向且以内切圆归一化；
 * 直屏则只按行心到上下边缘的距离计算，避免横向离中心远的普通行被误缩放。
 *
 * 缩放这里的径向进度**必须夹到 1**：圆屏角上的格子 t 可以到 1.8，不夹的话它们会被缩到
 * 最小缩放以下（实测缩成一个点），而位移侧刚好相反 —— 那边靠 t > 1 把它们拉回可视区。
 */
internal fun nativeRingChildVisual(
    centerX: Float,
    centerY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    config: NativeAppListConfig,
    output: NativeSpatialChildVisual = NativeSpatialChildVisual(),
): NativeSpatialChildVisual {
    if (
        viewportWidth <= 0f || viewportHeight <= 0f ||
        !centerX.isFinite() || !centerY.isFinite()
    ) {
        return output.set(1f, 1f, 0f, 0f, 0f, 1f)
    }
    val strength = config.fisheyeStrength.takeIf(Float::isFinite)?.coerceIn(0f, 2f) ?: 0f
    val minimumScale = (1f - 0.42f * strength).coerceIn(0.16f, 1f)
    val fisheyeProgress = if (config.deviceShape == NativeDeviceShape.ROUND) {
        nativeRingRadialProgress(
            centerX = centerX,
            centerY = centerY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            config = config,
        ).coerceIn(0f, 1f)
    } else {
        nativeRingFisheyeProgress(
            centerY = centerY,
            viewportHeight = viewportHeight,
            config = config,
            viewportWidth = viewportWidth,
        )
    }
    val eased = fisheyeProgress * fisheyeProgress * (3f - 2f * fisheyeProgress)
    val scale = if (config.fisheyeEnabled) {
        1f - (1f - minimumScale) * eased
    } else {
        1f
    }
    val entryProgress = nativeStaggeredEntryProgress(
        config = config,
        childCenterY = centerY,
        viewportHeight = viewportHeight,
    )
    // 位移与缩放吃回弹（见 nativeEntryOvershoot），透明度只吃原始进度。
    val entryEased = nativeEntryOvershoot(entryProgress)
    val translationY = (1f - entryEased) * 0.08f * viewportHeight
    val visualCenterY = centerY + translationY
    val topProgress = edgeProgress(visualCenterY, config.topFadeRangePx)
    val bottomProgress = edgeProgress(
        viewportHeight - visualCenterY,
        config.bottomFadeRangePx,
    )
    val blur = if (config.blurTier == NativeBlurTier.SYSTEM) {
        maxOf(
            config.topBlurRadiusPx * topProgress,
            config.bottomBlurRadiusPx * bottomProgress,
        )
    } else {
        0f
    }
    val fadeProgress = maxOf(topProgress, bottomProgress)
    val compatibilityFade = if (config.blurTier == NativeBlurTier.LITE) {
        nativeLiteEdgeAlpha(fadeProgress)
    } else {
        1f
    }
    return output.set(
        scale = scale * nativeEntryScale(entryEased),
        alpha = (if (config.fisheyeEnabled) scale.coerceIn(0.24f, 1f) else 1f) *
            entryProgress * compatibilityFade,
        // 收拢位移不在这里：它必须叠在圆屏安全轮廓之后（见 nativeRingConvergenceOffset）。
        translationX = 0f,
        translationY = translationY,
        blurRadiusPx = blur,
        saturation = nativeLiteEdgeSaturation(fadeProgress, config.blurTier),
    )
}

/**
 * 星环收拢的曲率上限（鱼眼强度 200% 那一档）。
 *
 * 0.45 对应「最外圈约收进 31%、角上约收进 59%」。再大下去内切圆里的格子会互相重叠 ——
 * 收拢只压位置不压格距，重叠了就是图标叠图标。
 */
private const val RING_MAX_CURVATURE = 0.45f

internal fun nativeLiteEdgeAlpha(progress: Float): Float =
    1f - 0.38f * progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f).orZero()

internal fun nativeLiteEdgeSaturation(progress: Float, tier: NativeBlurTier): Float =
    if (tier == NativeBlurTier.LITE) {
        1f - 0.55f * progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f).orZero()
    } else {
        1f
    }

private fun Float?.orZero(): Float = this ?: 0f

private fun edgeProgress(coordinate: Float, edgeRange: Float): Float {
    if (!coordinate.isFinite() || !edgeRange.isFinite() || edgeRange <= 0f) return 0f
    return (1f - coordinate / edgeRange).coerceIn(0f, 1f)
}
