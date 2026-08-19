package com.flue.launcher.applist.nativeview

import com.flue.launcher.util.generateFlowRows
import kotlin.math.ceil
import kotlin.math.floor

internal data class NativeFlowPlacement(
    val position: Int,
    val row: Int,
    val column: Int,
    val centerX: Float,
    /** Content-space Y around zero; add viewport center and scroll offset. */
    val centerY: Float,
)

/**
 * 心流的绝对定位几何：等列网格。
 *
 * 排布公式**不在这里重写** —— 直接用 `util/FlowGridMath.kt` 的 [generateFlowRows]，
 * 于是「网格长什么样」只有一处定义，纯 JVM 可测（见 `FlowGridMathTest`），这里只负责
 * 把它搬到视口坐标里并回答 RecyclerView 的回收 / 滚动问题。
 *
 * 月牙那条弧**不进几何**：它是绘制期按视口中心算的 translationX（见
 * [nativeFlowChildVisual]）。进几何就会变成「偏移 → 重测量 → 位置变 → 算出不同偏移」
 * 的自反馈抽搐。
 */
internal class NativeFlowGeometry private constructor(
    override val viewportHeightPx: Int,
    val iconSizePx: Float,
    val columns: Int,
    val rowStridePx: Float,
    /**
     * 实际生效的月牙鼓出量，已按可用横向余量夹过。
     *
     * 渲染侧必须用**这一份**而不是 config 里那份原始值：基准 x 是按它算的，
     * 两边用不同的数就会重新溢出（正是老师报的「最右侧图标被截掉」）。
     */
    val crescentBulgePx: Float,
    val crescentToRight: Boolean,
    val placements: List<NativeFlowPlacement>,
    override val minimumScrollOffsetPx: Float,
    override val maximumScrollOffsetPx: Float,
) : NativeAppGridGeometry {

    override val itemCount: Int
        get() = placements.size

    override val itemWidthPx: Float
        get() = iconSizePx

    override val itemHeightPx: Float
        get() = iconSizePx

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
        val halfIcon = iconSizePx / 2f
        val minimumY = -extraLayoutSpacePx - halfIcon
        val maximumY = viewportHeightPx + extraLayoutSpacePx + halfIcon
        val firstRowScreenCenterY =
            viewportHeightPx / 2f + placements[0].centerY + scrollOffsetPx
        val lastRow = placements[placements.lastIndex].row
        val firstVisibleRow = ceil(
            ((minimumY - firstRowScreenCenterY) / rowStridePx).toDouble(),
        ).toInt().coerceAtLeast(0)
        val lastVisibleRow = floor(
            ((maximumY - firstRowScreenCenterY) / rowStridePx).toDouble(),
        ).toInt().coerceAtMost(lastRow)
        if (lastVisibleRow < firstVisibleRow) {
            output[0] = -1
            output[1] = -1
            return
        }
        output[0] = (firstVisibleRow * columns).coerceAtMost(placements.lastIndex)
        output[1] = (((lastVisibleRow + 1) * columns) - 1).coerceAtMost(placements.lastIndex)
    }

    companion object {
        fun create(
            itemCount: Int,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            leftSafeInsetPx: Int,
            columns: Int,
            iconSizePx: Float,
            cellSizePx: Float = iconSizePx * 1.02f,
            /**
             * 月牙鼓出量，按**「峰值正好贴边」那个余量的倍数**给。
             *
             * 1.0 = 峰值那几行的右缘正好压在可用区右边界、离中线最远的那几行左缘正好压在左边界
             * —— 任何一行都不越界。>1.0 会让弧比矩形余量更深：上下两端那几行被推到可视区左侧之外，
             * 换来中段更明显的弧。圆屏上那块区域本来就在圆之外，而且行会随滚动走到中段，
             * 所以不存在「永久看不见的图标」。上限 [MAX_CRESCENT_BULGE_FRACTION]。
             *
             * 以前这里收的是像素（内容宽度 × 0.75 × 百分比）再夹到余量：**两个上限打架**，
             * 窄行上百分比刚过一半就撞到余量、之后拖到 100% 也毫无变化。改成余量的倍数之后
             * 这个数就单调有效了。
             */
            requestedCrescentBulgeFraction: Float = 0f,
            crescentToRight: Boolean = true,
        ): NativeFlowGeometry {
            require(itemCount >= 0)
            require(viewportWidthPx > 0 && viewportHeightPx > 0)
            require(iconSizePx.isFinite() && iconSizePx > 0f)
            require(cellSizePx.isFinite() && cellSizePx > 0f)
            val safeLeft = leftSafeInsetPx.coerceIn(0, viewportWidthPx - 1)
            val availableWidth = (viewportWidthPx - safeLeft).toFloat().coerceAtLeast(1f)
            val effectiveColumns = columns.coerceAtLeast(1)
            val offsets = generateFlowRows(
                count = itemCount,
                cols = effectiveColumns,
                cellSize = cellSizePx,
            )
            // 一整行实际占用的宽度（含首末图标各半个图标身）。
            val rowWidth = (effectiveColumns - 1) * cellSizePx + iconSizePx
            // 月牙能摆动的余量：行宽之外剩下的横向空间。1.0 倍就是「谁都不越界」的那一档，
            // 老师要的 150% 是刻意越过它（见 requestedCrescentBulgeFraction 的注释）。
            val slack = (availableWidth - rowWidth).coerceAtLeast(0f)
            val bulge = requestedCrescentBulgeFraction
                .takeIf { it.isFinite() && it > 0f }
                ?.coerceAtMost(MAX_CRESCENT_BULGE_FRACTION)
                ?.times(slack)
                ?: 0f
            // 基准取「峰值正好贴边」：朝右的弧在屏幕中线那几行贴右边，往上下两端
            // 逐渐收回、左侧留空。于是**任何** t 都不会越过右边界，也不用再夹一次。
            val baseCenterX = if (crescentToRight) {
                safeLeft + availableWidth - rowWidth / 2f - bulge
            } else {
                safeLeft + rowWidth / 2f + bulge
            }
            val placements = offsets.mapIndexed { position, offset ->
                NativeFlowPlacement(
                    position = position,
                    row = position / effectiveColumns,
                    column = position % effectiveColumns,
                    centerX = baseCenterX + offset.x,
                    centerY = offset.y,
                )
            }
            val firstY = placements.firstOrNull()?.centerY ?: 0f
            val lastY = placements.lastOrNull()?.centerY ?: 0f
            return NativeFlowGeometry(
                viewportHeightPx = viewportHeightPx,
                iconSizePx = iconSizePx,
                columns = effectiveColumns,
                rowStridePx = cellSizePx,
                crescentBulgePx = bulge,
                crescentToRight = crescentToRight,
                placements = placements,
                minimumScrollOffsetPx = -lastY,
                maximumScrollOffsetPx = -firstY,
            )
        }

        /**
         * 月牙鼓出量的上限，按「峰值贴边余量」的倍数计。
         *
         * 1.5 = 设置页那条拉杆的 150%。再深下去，中段那几行的左缘也会开始越过左边界，
         * 弧就不是弧、而是「整列往右挤」了。
         */
        const val MAX_CRESCENT_BULGE_FRACTION: Float = 1.5f
    }
}
