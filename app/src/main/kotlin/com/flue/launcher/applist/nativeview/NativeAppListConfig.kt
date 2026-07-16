package com.flue.launcher.applist.nativeview

import kotlin.math.abs

internal enum class NativeAppListTheme {
    LIGHT,
    DARK,
}

internal data class NativeAppListRowBorder(
    val enabled: Boolean = false,
    val colorArgb: Long = 0L,
    val widthPx: Float = 0f,
    val cornerRadiusPx: Float = 0f,
)

internal data class NativeAppListConfig(
    val active: Boolean = true,
    val leftSafeInsetPx: Int = 0,
    val appScale: Float = 1f,
    val fisheyeEnabled: Boolean = false,
    val fisheyeRange: Float = 0.4f,
    val fisheyeStrength: Float = 1f,
    val blurTier: NativeBlurTier = NativeBlurTier.OFF,
    val rotaryHapticsEnabled: Boolean = true,
    val theme: NativeAppListTheme = NativeAppListTheme.DARK,
    val rowBorder: NativeAppListRowBorder = NativeAppListRowBorder(),
)

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
