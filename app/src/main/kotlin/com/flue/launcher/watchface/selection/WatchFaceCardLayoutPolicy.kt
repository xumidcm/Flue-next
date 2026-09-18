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

/**
 * The compact pager indicator keeps a bounded number of dots while still
 * exposing the relative position of the focused page.  Small collections show
 * every page; larger collections use a nine-dot window that sticks to either
 * edge when the focused page is near the beginning or end.
 */
data class WatchFacePageIndicator(
    val page: Int,
    val alpha: Float,
)

private const val WATCH_FACE_PAGE_INDICATOR_MAX_VISIBLE = 9

fun watchFacePageIndicators(
    totalPages: Int,
    currentPage: Int,
    maxVisible: Int = WATCH_FACE_PAGE_INDICATOR_MAX_VISIBLE,
): List<WatchFacePageIndicator> {
    val pageCount = totalPages.coerceAtLeast(0)
    if (pageCount == 0) return emptyList()
    val visibleCount = maxVisible.coerceAtLeast(1).coerceAtMost(pageCount)
    val focusedPage = currentPage.coerceIn(0, pageCount - 1)
    val start = if (pageCount <= visibleCount) {
        0
    } else {
        (focusedPage - visibleCount / 2).coerceIn(0, pageCount - visibleCount)
    }
    val fadeWindow = pageCount > visibleCount
    return (start until (start + visibleCount)).map { page ->
        WatchFacePageIndicator(
            page = page,
            alpha = if (fadeWindow) {
                watchFacePageIndicatorAlpha(kotlin.math.abs(page - focusedPage))
            } else {
                1f
            },
        )
    }
}

fun watchFacePageIndicatorAlpha(distance: Int): Float = when (distance.coerceAtLeast(0)) {
    0 -> 1f
    1 -> 0.58f
    2 -> 0.32f
    3 -> 0.14f
    else -> 0f
}

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
