package com.flue.launcher.watchface.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure math for the image-import crop step: fitting the source image into the
 * viewport, constraining the crop frame (free / locked aspect), and mapping
 * the on-screen crop back to source pixel coordinates. Kept free of Android
 * types so it is unit-testable on the JVM; [WatchEditorImageCropper] owns the
 * actual bitmap decoding.
 */

data class WatchEditorCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** Crop aspect presets: free, 1:1, and the physical watch screen ratio. */
enum class WatchEditorCropAspect(val label: String) {
    FREE("自由"),
    SQUARE("1:1"),
    SCREEN("屏幕"),
}

/** Resolves the preset to a width/height ratio, or null for free cropping. */
fun watchEditorCropAspectRatio(
    aspect: WatchEditorCropAspect,
    screenWidthPx: Int,
    screenHeightPx: Int,
): Float? = when (aspect) {
    WatchEditorCropAspect.FREE -> null
    WatchEditorCropAspect.SQUARE -> 1f
    WatchEditorCropAspect.SCREEN ->
        if (screenWidthPx > 0 && screenHeightPx > 0) {
            screenWidthPx.toFloat() / screenHeightPx.toFloat()
        } else {
            1f
        }
}

/** Power-of-two sample size that keeps the decode within [maxDimension]. */
fun watchEditorCropSampleSize(sourceWidth: Int, sourceHeight: Int, maxDimension: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || maxDimension <= 0) return 1
    var sample = 1
    while (max(sourceWidth, sourceHeight) / (sample * 2) >= maxDimension) sample *= 2
    // The loop above keeps the *next* halving below the limit; step once more
    // when the current size still exceeds it.
    while (max(sourceWidth, sourceHeight) / sample > maxDimension) sample *= 2
    return sample
}

/** Letterboxes the source image into the viewport (fit-center). */
fun watchEditorFitImageRect(
    sourceWidth: Float,
    sourceHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): WatchEditorCropRect {
    if (sourceWidth <= 0f || sourceHeight <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) {
        return WatchEditorCropRect(0f, 0f, viewportWidth.coerceAtLeast(0f), viewportHeight.coerceAtLeast(0f))
    }
    val scale = min(viewportWidth / sourceWidth, viewportHeight / sourceHeight)
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    val left = (viewportWidth - width) / 2f
    val top = (viewportHeight - height) / 2f
    return WatchEditorCropRect(left, top, left + width, top + height)
}

/**
 * Clamps a candidate crop frame into [bounds], enforcing [minSize] and an
 * optional aspect [ratio] (width / height). The frame keeps its center where
 * possible so preset switches feel stable.
 */
fun constrainWatchEditorCropRect(
    candidate: WatchEditorCropRect,
    bounds: WatchEditorCropRect,
    minSize: Float,
    ratio: Float?,
): WatchEditorCropRect {
    val boundsWidth = bounds.width.coerceAtLeast(1f)
    val boundsHeight = bounds.height.coerceAtLeast(1f)
    val minimum = minSize.coerceIn(1f, min(boundsWidth, boundsHeight))
    var width = candidate.width.coerceIn(minimum, boundsWidth)
    var height = candidate.height.coerceIn(minimum, boundsHeight)
    if (ratio != null && ratio > 0f) {
        // Fit the ratio inside the bounds, preferring the candidate's area.
        width = min(width, boundsWidth)
        height = width / ratio
        if (height > boundsHeight) {
            height = boundsHeight
            width = height * ratio
        }
        if (width < minimum) {
            width = minimum
            height = width / ratio
        }
        if (height < minimum) {
            height = minimum
            width = height * ratio
        }
        width = width.coerceAtMost(boundsWidth)
        height = height.coerceAtMost(boundsHeight)
    }
    var left = candidate.centerX - width / 2f
    var top = candidate.centerY - height / 2f
    left = left.coerceIn(bounds.left, bounds.right - width)
    top = top.coerceIn(bounds.top, bounds.bottom - height)
    return WatchEditorCropRect(left, top, left + width, top + height)
}

/** Moves the crop frame by a drag delta, staying inside [bounds]. */
fun moveWatchEditorCropRect(
    rect: WatchEditorCropRect,
    deltaX: Float,
    deltaY: Float,
    bounds: WatchEditorCropRect,
): WatchEditorCropRect {
    val left = (rect.left + deltaX).coerceIn(bounds.left, bounds.right - rect.width)
    val top = (rect.top + deltaY).coerceIn(bounds.top, bounds.bottom - rect.height)
    return WatchEditorCropRect(left, top, left + rect.width, top + rect.height)
}

/** Scales the crop frame around its center (pinch), staying inside [bounds]. */
fun scaleWatchEditorCropRect(
    rect: WatchEditorCropRect,
    factor: Float,
    bounds: WatchEditorCropRect,
    minSize: Float,
    ratio: Float?,
): WatchEditorCropRect {
    val safeFactor = if (factor.isFinite() && factor > 0f) factor else 1f
    val candidate = WatchEditorCropRect(
        left = rect.centerX - rect.width * safeFactor / 2f,
        top = rect.centerY - rect.height * safeFactor / 2f,
        right = rect.centerX + rect.width * safeFactor / 2f,
        bottom = rect.centerY + rect.height * safeFactor / 2f,
    )
    return constrainWatchEditorCropRect(candidate, bounds, minSize, ratio)
}

enum class WatchEditorCropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Returns the corner handle within [touchRadius] of the pointer, or null. */
fun resolveWatchEditorCropHandle(
    rect: WatchEditorCropRect,
    x: Float,
    y: Float,
    touchRadius: Float,
): WatchEditorCropHandle? {
    fun near(cornerX: Float, cornerY: Float): Boolean =
        abs(x - cornerX) <= touchRadius && abs(y - cornerY) <= touchRadius
    return when {
        near(rect.left, rect.top) -> WatchEditorCropHandle.TOP_LEFT
        near(rect.right, rect.top) -> WatchEditorCropHandle.TOP_RIGHT
        near(rect.left, rect.bottom) -> WatchEditorCropHandle.BOTTOM_LEFT
        near(rect.right, rect.bottom) -> WatchEditorCropHandle.BOTTOM_RIGHT
        else -> null
    }
}

/**
 * Resizes the frame by dragging one corner. The opposite corner stays fixed;
 * aspect-locked presets resolve the final rect through
 * [constrainWatchEditorCropRect] anchored on that fixed corner.
 */
fun resizeWatchEditorCropRect(
    rect: WatchEditorCropRect,
    handle: WatchEditorCropHandle,
    deltaX: Float,
    deltaY: Float,
    bounds: WatchEditorCropRect,
    minSize: Float,
    ratio: Float?,
): WatchEditorCropRect {
    var left = rect.left
    var top = rect.top
    var right = rect.right
    var bottom = rect.bottom
    when (handle) {
        WatchEditorCropHandle.TOP_LEFT -> {
            left += deltaX
            top += deltaY
        }
        WatchEditorCropHandle.TOP_RIGHT -> {
            right += deltaX
            top += deltaY
        }
        WatchEditorCropHandle.BOTTOM_LEFT -> {
            left += deltaX
            bottom += deltaY
        }
        WatchEditorCropHandle.BOTTOM_RIGHT -> {
            right += deltaX
            bottom += deltaY
        }
    }
    left = left.coerceIn(bounds.left, bounds.right)
    top = top.coerceIn(bounds.top, bounds.bottom)
    right = right.coerceIn(bounds.left, bounds.right)
    bottom = bottom.coerceIn(bounds.top, bounds.bottom)
    val boundsLimit = min(bounds.width, bounds.height).coerceAtLeast(1f)
    val minimum = minSize.coerceIn(1f, boundsLimit)
    var width = (right - left).coerceAtLeast(minimum)
    var height = (bottom - top).coerceAtLeast(minimum)
    if (ratio != null && ratio > 0f) {
        // Preserve the dominant drag axis, derive the other from the ratio.
        if (abs(deltaX) >= abs(deltaY)) height = width / ratio else width = height * ratio
    }
    // Re-anchor on the fixed corner so resizing never teleports the frame.
    val anchoredLeft: Float
    val anchoredTop: Float
    when (handle) {
        WatchEditorCropHandle.TOP_LEFT -> {
            anchoredLeft = rect.right - width
            anchoredTop = rect.bottom - height
        }
        WatchEditorCropHandle.TOP_RIGHT -> {
            anchoredLeft = rect.left
            anchoredTop = rect.bottom - height
        }
        WatchEditorCropHandle.BOTTOM_LEFT -> {
            anchoredLeft = rect.right - width
            anchoredTop = rect.top
        }
        WatchEditorCropHandle.BOTTOM_RIGHT -> {
            anchoredLeft = rect.left
            anchoredTop = rect.top
        }
    }
    val candidate = WatchEditorCropRect(
        left = anchoredLeft,
        top = anchoredTop,
        right = anchoredLeft + width,
        bottom = anchoredTop + height,
    )
    return constrainWatchEditorCropRect(candidate, bounds, minSize, ratio)
}

/** Integer pixel rectangle in source-image space. */
data class WatchEditorSourceRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Maps the on-screen crop frame back to source pixel coordinates. [fit] is the
 * rect the image occupies in the viewport ([watchEditorFitImageRect]); the
 * result is clamped to the source bounds and guaranteed non-empty.
 */
fun mapWatchEditorCropToSource(
    crop: WatchEditorCropRect,
    fit: WatchEditorCropRect,
    sourceWidth: Int,
    sourceHeight: Int,
): WatchEditorSourceRect {
    if (fit.width <= 0f || fit.height <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
        return WatchEditorSourceRect(0, 0, max(sourceWidth, 1), max(sourceHeight, 1))
    }
    val scaleX = sourceWidth / fit.width
    val scaleY = sourceHeight / fit.height
    var left = ((crop.left - fit.left) * scaleX).roundToInt().coerceIn(0, sourceWidth - 1)
    var top = ((crop.top - fit.top) * scaleY).roundToInt().coerceIn(0, sourceHeight - 1)
    var right = ((crop.right - fit.left) * scaleX).roundToInt().coerceIn(1, sourceWidth)
    var bottom = ((crop.bottom - fit.top) * scaleY).roundToInt().coerceIn(1, sourceHeight)
    if (right <= left) right = (left + 1).coerceAtMost(sourceWidth).also { left = it - 1 }
    if (bottom <= top) bottom = (top + 1).coerceAtMost(sourceHeight).also { top = it - 1 }
    return WatchEditorSourceRect(left, top, right, bottom)
}
