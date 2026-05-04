package com.flue.launcher.ui.drawer

internal fun fisheyeMinScale(strengthPercent: Int): Float {
    val strength = strengthPercent.coerceIn(0, 200) / 100f
    return (1f - 0.42f * strength).coerceIn(0.16f, 1f)
}

internal fun edgeSpacingCompressionOffset(
    centerY: Float,
    screenCenterY: Float,
    screenHeight: Float,
    itemSize: Float,
    strengthPercent: Int,
    enabled: Boolean
): Float {
    if (!enabled || screenHeight <= 0f || itemSize <= 0f) return 0f
    val distanceFromCenter = centerY - screenCenterY
    val edgeProgress = (kotlin.math.abs(distanceFromCenter) / (screenHeight * 0.5f)).coerceIn(0f, 1f)
    val eased = edgeProgress * edgeProgress * (3f - 2f * edgeProgress)
    val strength = strengthPercent.coerceIn(0, 200) / 100f
    return -kotlin.math.sign(distanceFromCenter) * itemSize * 0.18f * strength * eased
}
