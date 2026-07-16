package com.flue.launcher.applist.nativeview

import kotlin.math.sqrt

internal data class NativeHoneycombPlacement(
    val position: Int,
    val row: Int,
    val column: Int,
    val centerX: Float,
    /** Content-space Y around zero; add viewport center and scroll offset. */
    val centerY: Float,
)

internal class NativeHoneycombGeometry private constructor(
    val viewportHeightPx: Int,
    val iconSizePx: Float,
    val placements: List<NativeHoneycombPlacement>,
    val minimumScrollOffsetPx: Float,
    val maximumScrollOffsetPx: Float,
) {
    fun visiblePositions(
        scrollOffsetPx: Float,
        extraLayoutSpacePx: Float,
    ): List<Int> {
        if (placements.isEmpty()) return emptyList()
        val halfIcon = iconSizePx / 2f
        val minimumY = -extraLayoutSpacePx - halfIcon
        val maximumY = viewportHeightPx + extraLayoutSpacePx + halfIcon
        val viewportCenterY = viewportHeightPx / 2f
        return placements.mapNotNullTo(ArrayList()) { placement ->
            val screenY = viewportCenterY + placement.centerY + scrollOffsetPx
            placement.position.takeIf { screenY in minimumY..maximumY }
        }
    }

    fun visiblePositionBounds(
        scrollOffsetPx: Float,
        extraLayoutSpacePx: Float,
        output: IntArray,
    ) {
        require(output.size >= 2)
        val halfIcon = iconSizePx / 2f
        val minimumY = -extraLayoutSpacePx - halfIcon
        val maximumY = viewportHeightPx + extraLayoutSpacePx + halfIcon
        val viewportCenterY = viewportHeightPx / 2f
        var first = -1
        var last = -1
        var index = 0
        while (index < placements.size) {
            val screenY = viewportCenterY + placements[index].centerY + scrollOffsetPx
            if (screenY in minimumY..maximumY) {
                if (first < 0) first = index
                last = index
            } else if (last >= 0 && screenY > maximumY) {
                break
            }
            index++
        }
        output[0] = first
        output[1] = last
    }

    fun screenCenterY(position: Int, scrollOffsetPx: Float): Float =
        viewportHeightPx / 2f + placements[position].centerY + scrollOffsetPx

    companion object {
        fun create(
            itemCount: Int,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            leftSafeInsetPx: Int,
            narrowColumns: Int,
            iconSizePx: Float,
            cellSizePx: Float = iconSizePx * 1.02f,
        ): NativeHoneycombGeometry {
            require(itemCount >= 0)
            require(viewportWidthPx > 0 && viewportHeightPx > 0)
            require(iconSizePx.isFinite() && iconSizePx > 0f)
            require(cellSizePx.isFinite() && cellSizePx > 0f)
            val safeLeft = leftSafeInsetPx.coerceIn(0, viewportWidthPx - 1)
            val contentCenterX = safeLeft + (viewportWidthPx - safeLeft) / 2f
            val narrow = narrowColumns.coerceAtLeast(1)
            val wide = narrow + 1
            val rowHeight = cellSizePx * sqrt(3f) / 2f
            val mutable = ArrayList<NativeHoneycombPlacement>(itemCount)
            var position = 0
            var row = 0
            while (position < itemCount) {
                val columns = if (row % 2 == 1) wide else narrow
                val itemsInRow = minOf(columns, itemCount - position)
                val fullRowWidth = (columns - 1) * cellSizePx
                repeat(itemsInRow) { column ->
                    mutable += NativeHoneycombPlacement(
                        position = position++,
                        row = row,
                        column = column,
                        centerX = contentCenterX + column * cellSizePx - fullRowWidth / 2f,
                        centerY = row * rowHeight,
                    )
                }
                row++
            }
            val totalHeight = (row - 1).coerceAtLeast(0) * rowHeight
            val yCenterOffset = totalHeight / 2f
            val placements = mutable.map { it.copy(centerY = it.centerY - yCenterOffset) }
            val viewportCenterY = viewportHeightPx / 2f
            val firstY = placements.firstOrNull()?.centerY ?: 0f
            val lastY = placements.lastOrNull()?.centerY ?: 0f
            return NativeHoneycombGeometry(
                viewportHeightPx = viewportHeightPx,
                iconSizePx = iconSizePx,
                placements = placements,
                minimumScrollOffsetPx = viewportCenterY - (viewportCenterY + lastY),
                maximumScrollOffsetPx = viewportCenterY - (viewportCenterY + firstY),
            )
        }
    }
}
