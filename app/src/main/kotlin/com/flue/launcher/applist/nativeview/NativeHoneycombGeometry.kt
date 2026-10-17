package com.flue.launcher.applist.nativeview

import kotlin.math.sqrt
import kotlin.math.ceil
import kotlin.math.floor

internal data class NativeHoneycombPlacement(
    val position: Int,
    val row: Int,
    val column: Int,
    val centerX: Float,
    /** Content-space Y around zero; add viewport center and scroll offset. */
    val centerY: Float,
)

internal class NativeHoneycombGeometry private constructor(
    override val viewportHeightPx: Int,
    val iconSizePx: Float,
    val placements: List<NativeHoneycombPlacement>,
    override val minimumScrollOffsetPx: Float,
    override val maximumScrollOffsetPx: Float,
) : NativeAppGridGeometry {
    fun withScrollBounds(bounds: Pair<Float, Float>) = NativeHoneycombGeometry(
        viewportHeightPx, iconSizePx, placements, bounds.first, bounds.second,
    )

    private val narrowRowColumns = placements.indexOfFirst { it.row != 0 }
        .takeIf { it > 0 }
        ?: placements.size
    private val wideRowColumns = placements.indexOfFirst { it.row > 1 }
        .takeIf { it > narrowRowColumns }
        ?.minus(narrowRowColumns)
        ?: (placements.size - narrowRowColumns).coerceAtLeast(narrowRowColumns)

    override val itemCount: Int
        get() = placements.size

    override val itemWidthPx: Float
        get() = iconSizePx

    override val itemHeightPx: Float
        get() = iconSizePx

    override fun centerX(position: Int): Float = placements[position].centerX

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
        val halfIcon = iconSizePx / 2f
        val minimumY = -extraLayoutSpacePx - halfIcon
        val maximumY = viewportHeightPx + extraLayoutSpacePx + halfIcon
        val viewportCenterY = viewportHeightPx / 2f
        val firstPlacement = placements[0]
        val lastPlacement = placements[placements.lastIndex]
        val rowCount = lastPlacement.row + 1
        val rowHeight = if (rowCount > 1) {
            (lastPlacement.centerY - firstPlacement.centerY) / (rowCount - 1)
        } else {
            iconSizePx
        }
        val firstRowScreenCenterY = viewportCenterY + firstPlacement.centerY + scrollOffsetPx
        val firstVisibleRow = ceil(
            ((minimumY - firstRowScreenCenterY) / rowHeight).toDouble(),
        ).toInt().coerceAtLeast(0)
        val lastVisibleRow = floor(
            ((maximumY - firstRowScreenCenterY) / rowHeight).toDouble(),
        ).toInt().coerceAtMost(lastPlacement.row)
        if (lastVisibleRow < firstVisibleRow) {
            output[0] = -1
            output[1] = -1
            return
        }
        output[0] = positionAtRowStart(firstVisibleRow)
        output[1] = (positionAtRowStart(lastVisibleRow + 1) - 1).coerceAtMost(placements.lastIndex)
    }

    override fun screenCenterY(position: Int, scrollOffsetPx: Float): Float =
        viewportHeightPx / 2f + placements[position].centerY + scrollOffsetPx

    private fun positionAtRowStart(row: Int): Int {
        if (row <= 0) return 0
        val pairs = row / 2
        val pairedItems = pairs * (narrowRowColumns + wideRowColumns)
        return if (row % 2 == 0) pairedItems else pairedItems + narrowRowColumns
    }

    companion object {
        fun create(
            itemCount: Int,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            leftSafeInsetPx: Int,
            /** 右侧安全内缩（滚动指示器占位）。0 = 指示器关掉，不留白边。 */
            rightSafeInsetPx: Int = 0,
            narrowColumns: Int,
            iconSizePx: Float,
            cellSizePx: Float = iconSizePx * 1.02f,
            /**
             * 贴边对齐时首末行保留的空白（像素）。[centerAligned] 为假时生效。
             */
            edgePaddingPx: Float = 0f,
            /** false = 贴边对齐（首行贴顶、末行贴底），true = 既有居中。 */
            centerAligned: Boolean = true,
        ): NativeHoneycombGeometry {
            require(itemCount >= 0)
            require(viewportWidthPx > 0 && viewportHeightPx > 0)
            require(iconSizePx.isFinite() && iconSizePx > 0f)
            require(cellSizePx.isFinite() && cellSizePx > 0f)
            val safeLeft = nativeSafeLeftPx(leftSafeInsetPx, viewportWidthPx.toFloat())
            val contentCenterX = safeLeft + nativeSafeContentWidthPx(
                leftSafeInsetPx = leftSafeInsetPx,
                rightSafeInsetPx = rightSafeInsetPx,
                viewportWidthPx = viewportWidthPx,
            ) / 2f
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
            val firstY = placements.firstOrNull()?.centerY ?: 0f
            val lastY = placements.lastOrNull()?.centerY ?: 0f
            // placement 的 y 保持「整张网格居中到 0」不变，只改滚动上下界：贴边档
            // 下首行贴顶、末行贴底，布局管理器那条「初始滚动 = maximumScrollOffsetPx」
            // 的兜底路径于是自动落到贴顶位置。
            val (minimumScroll, maximumScroll) = nativeAppListScrollBounds(
                viewportHeightPx = viewportHeightPx,
                firstY = firstY,
                lastY = lastY,
                itemHeightPx = iconSizePx,
                edgePaddingPx = edgePaddingPx,
                centerAligned = centerAligned,
            )
            return NativeHoneycombGeometry(
                viewportHeightPx = viewportHeightPx,
                iconSizePx = iconSizePx,
                placements = placements,
                minimumScrollOffsetPx = minimumScroll,
                maximumScrollOffsetPx = maximumScroll,
            )
        }
    }
}
