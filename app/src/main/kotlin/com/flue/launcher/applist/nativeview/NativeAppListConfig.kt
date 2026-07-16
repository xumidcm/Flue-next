package com.flue.launcher.applist.nativeview

import kotlin.math.abs

enum class NativeAppListTheme {
    LIGHT,
    DARK,
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
)

data class NativeAppListConfig(
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
    val palette: NativeAppListPalette = NativeAppListPalette(),
    val appScale: Float = 1f,
    val fisheyeEnabled: Boolean = false,
    val fisheyeRange: Float = 0.4f,
    val fisheyeStrength: Float = 1f,
    val blurTier: NativeBlurTier = NativeBlurTier.OFF,
    val rotaryHapticsEnabled: Boolean = true,
    val theme: NativeAppListTheme = NativeAppListTheme.DARK,
    val rowBorder: NativeAppListRowBorder = NativeAppListRowBorder(),
)

internal class NativeAppListResolvedConfig private constructor(
    val value: NativeAppListConfig,
) {
    companion object {
        fun from(config: NativeAppListConfig): NativeAppListResolvedConfig =
            NativeAppListResolvedConfig(config.legalized())
    }
}

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
        palette = palette.legalized(),
        appScale = appScale
            .finiteOr(1f)
            .coerceIn(MIN_POSITIVE_DRAWING_VALUE, MAX_APP_SCALE),
        fisheyeRange = fisheyeRange
            .finiteOr(DEFAULT_FISHEYE_RANGE)
            .coerceIn(MIN_FISHEYE_RANGE, 1f),
        fisheyeStrength = fisheyeStrength
            .finiteOr(0f)
            .coerceIn(0f, 1f),
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
)

private fun Int.nonNegativeMetric(): Int = coerceIn(0, MAX_NATIVE_METRIC_PX)

private fun Int.positiveMetricOr(fallback: Int): Int =
    takeIf { it > 0 }?.coerceAtMost(MAX_NATIVE_METRIC_PX) ?: fallback

private fun Float.finiteOr(fallback: Float): Float = takeIf(Float::isFinite) ?: fallback

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

internal data class NativeLinearChildVisual(
    val scale: Float,
    val alpha: Float,
)

internal fun nativeLinearChildVisual(
    childCenterY: Float,
    viewportHeight: Int,
    config: NativeAppListConfig,
): NativeLinearChildVisual {
    val baseScale = config.appScale.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 1f
    if (
        !config.active ||
        !config.fisheyeEnabled ||
        !childCenterY.isFinite() ||
        viewportHeight <= 0
    ) {
        return NativeLinearChildVisual(scale = baseScale, alpha = 1f)
    }

    val halfHeight = viewportHeight / 2f
    val normalizedDistance = (abs(childCenterY - halfHeight) / halfHeight).coerceIn(0f, 1f)
    val edgeBand = config.fisheyeRange.takeIf(Float::isFinite)?.coerceIn(0.01f, 1f) ?: 0.4f
    val edgeStart = 1f - edgeBand
    val linearProgress = ((normalizedDistance - edgeStart) / edgeBand).coerceIn(0f, 1f)
    val smoothProgress = linearProgress * linearProgress * (3f - 2f * linearProgress)
    val strength = config.fisheyeStrength.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val effectProgress = smoothProgress * strength

    return NativeLinearChildVisual(
        scale = baseScale * (1f - 0.45f * effectProgress),
        alpha = 1f - 0.45f * effectProgress,
    )
}
