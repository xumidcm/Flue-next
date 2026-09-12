package com.flue.launcher.watchface.selection

data class WatchFaceCardLayout(
    val previewSideDp: Float,
    val availablePreviewWidthDp: Float,
    val availablePreviewHeightDp: Float,
    val reservedVerticalDp: Float,
    val verticalInsetDp: Float = 0f,
)

fun watchFaceCardLayout(
    availableWidthDp: Float,
    availableHeightDp: Float,
    isRoundScreen: Boolean,
    actionCount: Int,
): WatchFaceCardLayout {
    val safeWidth = availableWidthDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val safeHeight = availableHeightDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val inset = safeHeight * if (isRoundScreen) 0.10f else 0.025f
    val header = 48f
    val actions = if (actionCount > 0) 44f else 0f
    val gaps = (safeHeight * 0.06f).coerceIn(8f, 32f)
    val reserved = (inset * 2f + header + actions + 16f + gaps).coerceAtMost(safeHeight)
    val availableWidth = safeWidth * if (isRoundScreen) 0.58f else 0.76f
    val availableHeight = (safeHeight - reserved).coerceAtLeast(0f)
    return WatchFaceCardLayout(
        previewSideDp = minOf(availableWidth, availableHeight),
        availablePreviewWidthDp = availableWidth,
        availablePreviewHeightDp = availableHeight,
        reservedVerticalDp = reserved,
        verticalInsetDp = inset,
    )
}

/**
 * Picks whichever monochrome foreground has the stronger WCAG contrast against
 * the sampled watch-face background. Black overtakes white at roughly 0.179
 * relative luminance.
 */
fun watchFaceCardUseDarkForeground(backgroundLuminance: Float): Boolean =
    backgroundLuminance.takeIf(Float::isFinite)?.let { it >= 0.179f } ?: false

data class WatchFaceCardVisualTransform(
    val scale: Float,
    val imageScale: Float,
    val maskScale: Float,
    val borderScale: Float,
    val hitTargetScale: Float,
)

fun watchFaceCardVisualTransform(pageOffset: Float, pressed: Boolean): WatchFaceCardVisualTransform {
    val pageScale = 1f - kotlin.math.abs(pageOffset).coerceIn(0f, 1f) * 0.10f
    val scale = pageScale * if (pressed) 0.965f else 1f
    return WatchFaceCardVisualTransform(scale, scale, scale, scale, scale)
}
