package com.flue.launcher.applist.nativeview

import com.flue.launcher.util.FlowShapeCurve
import kotlin.math.abs
import kotlin.math.hypot

/** Only settled visual inputs belong in the geometry cache, never animation frame progress. */
internal data class NativeEdgeVisibilityKey(
    val shape: NativeDeviceShape,
    val iconSize: Int,
    val iconShape: NativeAppListIconShape,
    val fisheye: Boolean,
    val strength: Float,
    val range: Int,
    val compression: Boolean,
    val curve: FlowShapeCurve,
    val topFisheye: Boolean,
    val iconPadding: Float,
)

internal fun NativeAppListConfig.edgeVisibilityKey() = NativeEdgeVisibilityKey(
    deviceShape, iconSizePx, iconShape, fisheyeEnabled, fisheyeStrength,
    honeycombFisheyeRangeRows, edgeSpacingCompressionEnabled, flowShapeCurve,
    honeycombTopFisheyeEnabled, denseGridIconInnerPaddingPx,
)

/** Find the nearest safe scroll endpoints without changing the grid or clamping moving icons. */
internal fun nativeVisibleEdgeBounds(
    geometry: NativeAppGridGeometry,
    viewportWidth: Float,
    config: NativeAppListConfig,
    iconSizePx: Float = config.iconSizePx.toFloat(),
    visualAt: (Int, Float) -> NativeSpatialChildVisual,
): Pair<Float, Float> {
    val original = geometry.minimumScrollOffsetPx to geometry.maximumScrollOffsetPx
    if (!config.edgeAlignEnabled || geometry.itemCount == 0) return original
    val height = geometry.viewportHeightPx.toFloat()
    val middle = height / 2f
    val radius = minOf(viewportWidth, height) / 2f
    fun safeAt(position: Int, y: Float): Boolean {
        val visual = visualAt(position, y)
        val x = geometry.centerX(position) + visual.translationX
        val actualY = y + visual.translationY
        val half = iconSizePx * visual.scale / 2f
        if (config.deviceShape == NativeDeviceShape.ROUND) {
            val dx = abs(x - viewportWidth / 2f)
            val dy = abs(actualY - middle)
            return if (config.iconShape == NativeAppListIconShape.CIRCLE) {
                hypot(dx, dy) + half <= radius + 0.01f
            } else {
                hypot(dx + half, dy + half) <= radius + 0.01f
            }
        }
        return x - half >= -0.01f && x + half <= viewportWidth + 0.01f &&
            actualY - half >= 0f && actualY + half <= height
    }
    fun safeScroll(top: Boolean): Float {
        val end = if (top) 0 else geometry.itemCount - 1
        val initial = if (top) original.second else original.first
        val endY = geometry.screenCenterY(end, initial)
        // Only move toward the centre. Oversized rows retain their best reachable position.
        val distance = abs(middle - endY)
        val steps = kotlin.math.ceil(distance * 2f).toInt().coerceAtLeast(1)
        val rowY = geometry.screenCenterY(end, 0f)
        val row = (0 until geometry.itemCount).filter {
            abs(geometry.screenCenterY(it, 0f) - rowY) < 0.01f
        }
        for (step in 0..steps) {
            val y = endY + (middle - endY) * step / steps
            if (row.all { safeAt(it, y) }) return initial + y - endY
        }
        return initial + middle - endY
    }
    val maximum = safeScroll(top = true)
    val minimum = safeScroll(top = false)
    return minOf(minimum, maximum) to maximum
}
