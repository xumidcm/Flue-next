package com.flue.launcher.applist.nativeview

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

internal data class NativeDenseGridPlacement(
    val position: Int,
    val row: Int,
    val column: Int,
    val centerX: Float,
    /** Content-space Y around zero; add viewport center and scroll offset. */
    val centerY: Float,
)

internal class NativeDenseGridGeometry private constructor(
    override val viewportHeightPx: Int,
    override val itemWidthPx: Float,
    override val itemHeightPx: Float,
    val itemSpacingPx: Float,
    val verticalEdgePaddingPx: Float,
    val placements: List<NativeDenseGridPlacement>,
    override val minimumScrollOffsetPx: Float,
    override val maximumScrollOffsetPx: Float,
) : NativeAppGridGeometry {
    private val columnCount = placements.firstOrNull()?.let { first ->
        placements.indexOfFirst { it.row != first.row }
            .takeIf { it > 0 }
            ?: placements.size
    } ?: 1

    override val itemCount: Int
        get() = placements.size

    override fun centerX(position: Int): Float = placements[position].centerX

    override fun screenCenterY(position: Int, scrollOffsetPx: Float): Float =
        viewportHeightPx / 2f + placements[position].centerY + scrollOffsetPx

    override fun visiblePositionBounds(
        scrollOffsetPx: Float,
        extraLayoutSpacePx: Float,
        output: IntArray,
    ) {
        require(output.size >= 2)
        if (placements.isEmpty()) {
            output[0] = -1
            output[1] = -1
            return
        }
        val halfItem = itemHeightPx / 2f
        val minimumY = -extraLayoutSpacePx - halfItem
        val maximumY = viewportHeightPx + extraLayoutSpacePx + halfItem
        val viewportCenterY = viewportHeightPx / 2f
        val firstPlacement = placements[0]
        val rowStride = itemHeightPx + itemSpacingPx
        val firstRowScreenCenterY = viewportCenterY + firstPlacement.centerY + scrollOffsetPx
        val firstVisibleRow = ceil(
            ((minimumY - firstRowScreenCenterY) / rowStride).toDouble(),
        ).toInt().coerceAtLeast(0)
        val lastVisibleRow = floor(
            ((maximumY - firstRowScreenCenterY) / rowStride).toDouble(),
        ).toInt().coerceAtMost(placements.last().row)
        if (lastVisibleRow < firstVisibleRow) {
            output[0] = -1
            output[1] = -1
            return
        }
        output[0] = (firstVisibleRow * columnCount).coerceAtMost(placements.lastIndex)
        output[1] = (((lastVisibleRow + 1) * columnCount) - 1)
            .coerceAtMost(placements.lastIndex)
    }

    companion object {
        fun create(
            itemCount: Int,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            leftSafeInsetPx: Int,
            columns: Int,
            horizontalPaddingPx: Float,
            itemSpacingPx: Float,
            itemAspectRatio: Float,
            minimumItemHeightPx: Float,
            minimumItemWidthPx: Float = minimumItemHeightPx,
            minimumVerticalPaddingPx: Float = 8f,
        ): NativeDenseGridGeometry {
            require(itemCount >= 0)
            require(viewportWidthPx > 0 && viewportHeightPx > 0)
            require(columns > 0)
            require(horizontalPaddingPx.isFinite() && horizontalPaddingPx >= 0f)
            require(itemSpacingPx.isFinite() && itemSpacingPx >= 0f)
            require(itemAspectRatio.isFinite() && itemAspectRatio > 0f)
            require(minimumItemWidthPx.isFinite() && minimumItemWidthPx > 0f)
            require(minimumItemHeightPx.isFinite() && minimumItemHeightPx > 0f)
            require(minimumVerticalPaddingPx.isFinite() && minimumVerticalPaddingPx >= 0f)

            val safeLeft = leftSafeInsetPx.coerceIn(0, viewportWidthPx - 1)
            val usableWidth = (
                viewportWidthPx - safeLeft - horizontalPaddingPx * 2f
                ).coerceAtLeast(1f)
            // Do not let a narrow round-screen viewport silently create cells
            // smaller than the configured minimum. Reduce the column count
            // first, then shrink only when even one minimum cell cannot fit.
            val maximumColumnsForMinimum = floor(
                ((usableWidth + itemSpacingPx) /
                    (minimumItemWidthPx + itemSpacingPx)).toDouble(),
            ).toInt().coerceAtLeast(1)
            val effectiveColumns = min(columns, maximumColumnsForMinimum)
            val availableWidth = (
                usableWidth - itemSpacingPx * (effectiveColumns - 1)
                ).coerceAtLeast(1f)
            val itemWidth = (availableWidth / effectiveColumns)
                .coerceAtLeast(minimumItemWidthPx)
                .coerceAtMost(usableWidth)
            val itemHeight = (itemWidth / itemAspectRatio).coerceAtLeast(minimumItemHeightPx)
            val rowStride = itemHeight + itemSpacingPx
            val rowCount = if (itemCount == 0) 0 else {
                (itemCount + effectiveColumns - 1) / effectiveColumns
            }
            val verticalPadding = (
                (viewportHeightPx - itemHeight) / 2f
                ).coerceAtLeast(minimumVerticalPaddingPx)
            val firstCenterY = verticalPadding + itemHeight / 2f
            val firstRelativeCenterY = firstCenterY - viewportHeightPx / 2f
            val placements = ArrayList<NativeDenseGridPlacement>(itemCount)
            repeat(itemCount) { position ->
                val row = position / effectiveColumns
                val column = position % effectiveColumns
                placements += NativeDenseGridPlacement(
                    position = position,
                    row = row,
                    column = column,
                    centerX = safeLeft + horizontalPaddingPx + itemWidth / 2f +
                        column * (itemWidth + itemSpacingPx),
                    centerY = firstRelativeCenterY + row * rowStride,
                )
            }
            val firstY = placements.firstOrNull()?.centerY ?: 0f
            val lastY = placements.lastOrNull()?.centerY ?: 0f
            val minimumScroll = if (placements.isEmpty()) {
                0f
            } else {
                (
                    viewportHeightPx / 2f - verticalPadding - itemHeight / 2f - lastY
                    ).coerceAtMost(0f)
            }
            return NativeDenseGridGeometry(
                viewportHeightPx = viewportHeightPx,
                itemWidthPx = itemWidth,
                itemHeightPx = itemHeight,
                itemSpacingPx = itemSpacingPx,
                verticalEdgePaddingPx = verticalPadding,
                placements = placements,
                minimumScrollOffsetPx = minimumScroll,
                maximumScrollOffsetPx = 0f,
            )
        }
    }

}
