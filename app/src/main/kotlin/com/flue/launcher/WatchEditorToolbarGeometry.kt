package com.flue.launcher

/** Layout choice for the editor's action strip. */
internal enum class WatchEditorToolbarLayout {
    INLINE,
    STACKED,
}

/**
 * Round screens have a much narrower chord at the top edge. Below this width the title and
 * five minimum-touch actions should occupy separate rows instead of competing for one line.
 */
internal fun watchEditorToolbarLayout(
    isRound: Boolean,
    maxWidthDp: Float,
): WatchEditorToolbarLayout = if (isRound && maxWidthDp < 300f) {
    WatchEditorToolbarLayout.STACKED
} else {
    WatchEditorToolbarLayout.INLINE
}

/** Keeps the scrollable action strip inside the width available to its parent. */
internal fun watchEditorToolbarViewportWidth(
    isRound: Boolean,
    maxWidthDp: Float,
): Float {
    val available = maxWidthDp.coerceAtLeast(0f)
    val preferred = if (isRound) 148f else 360f
    return minOf(available, preferred)
}
