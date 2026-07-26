package com.flue.launcher.applist.nativeview

import com.flue.launcher.motion.FlueMotionProfile
import com.flue.launcher.motion.FlueMotionProfiles
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
    val iconShadowEnabled: Boolean = true,
    val edgeSpacingCompressionEnabled: Boolean = true,
    /** Classic (Beta 1.2) keeps content stable; Next animates entry progress. */
    val entryMotionEnabled: Boolean = true,
    /**
     * Monotonic token for a new app-list entry. Compose changes this once per
     * transition; the native host owns the frame clock for the actual motion.
     */
    val entryAnimationToken: Long = 0L,
    val entryProgress: Float = 1f,
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
        current.contentOverlayBlurRadiusPx != next.contentOverlayBlurRadiusPx

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
    val entryProgress = if (config.entryMotionEnabled) {
        config.runtimeEntryProgress.coerceIn(0f, 1f)
    } else {
        1f
    }
    val entryOffset = (1f - entryProgress) * 0.08f * viewportHeight
    val entryScale = 0.90f + 0.10f * entryProgress
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
): Float = when {
    dragged -> 0.965f
    glidePressed -> 0.992f
    folderHover -> 0.90f
    pressed -> 0.97f
    else -> 1f
}

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

internal fun nativeHoneycombChildVisual(
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
    val screenCenterX = config.leftSafeInsetPx +
        (viewportWidth - config.leftSafeInsetPx) / 2f
    val screenCenterY = viewportHeight / 2f
    val strength = config.fisheyeStrength.coerceIn(0f, 2f)
    val minimumScale = (1f - 0.42f * strength).coerceIn(0.16f, 1f)
    val screenRadius = minOf(
        viewportWidth - config.leftSafeInsetPx,
        viewportHeight,
    ) / 2f
    val maximumDistance = screenRadius *
        (config.honeycombFisheyeRangeRows.coerceIn(1, 8) * 0.42f).coerceAtLeast(0.42f)
    val distance = hypot(centerX - screenCenterX, centerY - screenCenterY)
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
    val entryProgress = if (config.entryMotionEnabled) {
        config.runtimeEntryProgress.coerceIn(0f, 1f)
    } else {
        1f
    }
    val translationY = (1f - entryProgress) * 0.08f * viewportHeight
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
    return output.set(
        scale = scale,
        alpha = (if (config.fisheyeEnabled) scale.coerceIn(0.24f, 1f) else 1f) *
            entryProgress * compatibilityFade,
        translationX = translationX,
        translationY = translationY,
        blurRadiusPx = blur,
        saturation = nativeLiteEdgeSaturation(fadeProgress, config.blurTier),
    )
}

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

    val safeLeft = config.leftSafeInsetPx.coerceAtLeast(0).toFloat()
        .coerceAtMost(viewportWidth)
    val screenCenterX = safeLeft + (viewportWidth - safeLeft) / 2f
    val screenCenterY = viewportHeight / 2f
    val bottomFisheyeStartY = viewportHeight * 0.52f
    val columns = config.denseGridColumns.coerceIn(1, 10)
    val rangeRows = config.honeycombFisheyeRangeRows.coerceIn(1, 8)
    val minimumRange = viewportHeight / columns
    val fisheyeRange = (minimumRange * rangeRows).coerceIn(minimumRange, viewportHeight)
    val strength = config.fisheyeStrength.coerceIn(0f, 2f)
    val minimumScale = (1f - 0.42f * strength).coerceIn(0.16f, 1f)
    val inBottomBand = config.fisheyeEnabled && centerY > bottomFisheyeStartY
    val scale = if (inBottomBand && fisheyeRange > 0f) {
        val progress = ((centerY - bottomFisheyeStartY) / fisheyeRange).coerceIn(0f, 1f)
        1f - (1f - minimumScale) * progress
    } else {
        1f
    }

    val translationX = if (inBottomBand && config.edgeSpacingCompressionEnabled) {
        val edgeProgress = (
            kotlin.math.abs(centerY - screenCenterY) / (viewportHeight / 2f)
            ).coerceIn(0f, 1f)
        val easedEdge = edgeProgress * edgeProgress * (3f - 2f * edgeProgress)
        val compression = (0.18f * strength * easedEdge).coerceIn(0f, 0.32f)
        -(centerX - screenCenterX) * compression
    } else {
        0f
    }
    val entryProgress = if (config.entryMotionEnabled) {
        config.runtimeEntryProgress.coerceIn(0f, 1f)
    } else {
        1f
    }
    val translationY = (1f - entryProgress) * 0.08f * viewportHeight
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
    return output.set(
        scale = scale,
        alpha = fisheyeAlpha * entryProgress * compatibilityFade,
        translationX = translationX,
        translationY = translationY,
        blurRadiusPx = blur,
        saturation = nativeLiteEdgeSaturation(fadeProgress, config.blurTier),
    )
}

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
