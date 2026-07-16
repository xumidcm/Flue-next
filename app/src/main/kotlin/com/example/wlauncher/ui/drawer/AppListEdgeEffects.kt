package com.flue.launcher.ui.drawer

internal fun computeAppListEdgeBlur(
    centerY: Float,
    screenHeight: Float,
    topBlurZonePx: Float,
    bottomBlurZonePx: Float,
    topBlurDp: Float,
    bottomBlurDp: Float,
): Float {
    if (!centerY.isFinite() || !screenHeight.isFinite() || screenHeight <= 0f) return 0f
    val topStrength = if (topBlurZonePx.isFinite() && topBlurZonePx > 0f) {
        (1f - centerY / topBlurZonePx).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bottomDistance = screenHeight - centerY
    val bottomStrength = if (bottomBlurZonePx.isFinite() && bottomBlurZonePx > 0f) {
        (1f - bottomDistance / bottomBlurZonePx).coerceIn(0f, 1f)
    } else {
        0f
    }
    val safeTopBlur = topBlurDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val safeBottomBlur = bottomBlurDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    return maxOf(topStrength * safeTopBlur, bottomStrength * safeBottomBlur)
}
