package com.flue.launcher.applist.nativeview

/**
 * Allocation-free geometry seam shared by the native non-linear app-list modes.
 *
 * Layout managers consume this contract for scrolling and recycling only. The
 * concrete geometry owns its placement formula; no UI state or item index is
 * used as an identity here.
 */
internal interface NativeAppGridGeometry {
    val itemCount: Int
    val viewportHeightPx: Int
    val itemWidthPx: Float
    val itemHeightPx: Float
    val minimumScrollOffsetPx: Float
    val maximumScrollOffsetPx: Float

    fun centerX(position: Int): Float

    fun screenCenterY(position: Int, scrollOffsetPx: Float): Float

    fun visiblePositionBounds(
        scrollOffsetPx: Float,
        extraLayoutSpacePx: Float,
        output: IntArray,
    )
}
