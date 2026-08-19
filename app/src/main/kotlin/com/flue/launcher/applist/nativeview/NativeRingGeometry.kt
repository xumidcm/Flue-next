package com.flue.launcher.applist.nativeview

import kotlin.math.ceil
import kotlin.math.floor

internal data class NativeRingPlacement(
    val position: Int,
    val row: Int,
    val column: Int,
    val centerX: Float,
    /** Content-space Y around zero; add viewport center and scroll offset. */
    val centerY: Float,
)

/**
 * 星环布局的格心：**铺满可用宽度的正方格**。
 *
 * ## 与密集网格的关系
 *
 * 结构照 [NativeDenseGridGeometry] 抄（同一套 [NativeAppGridGeometry] 契约、同一套按行
 * 推算可见区间的回收逻辑），差别只在格子的算法：
 *
 * - 密集网格留左右内边距、留格间距、格子可以是长方形（`itemAspectRatio`）；
 * - 星环**不留任何空隙**，格边长恒为 `可用宽度 / 列数`，横纵同值。参考图里图标是铺满
 *   整屏的，留内边距就会在四周多出一圈空白，圆屏上那圈空白正好压在弧线最需要图标的地方。
 *
 * 图标本体比格子小一号（见 [nativeRingIconSizePx]），格与格之间的视觉空隙由那个比例给，
 * 而不是靠格间距 —— 只有这样「格心铺满」和「图标不相碰」才能同时成立。
 *
 * ## 那道弧不在这里
 *
 * 四边收拢是**绘制期**按格心离屏心的距离算的 translation（见
 * [nativeRingConvergenceOffset]），刻意不进几何。进几何就会变成「收拢 → 重测量 →
 * 格心变 → 算出不同的收拢量」的自反馈抽搐；`ui/common/ChordScale.kt` 开头为这件事
 * 记过一次翻车，心流的月牙也是同一个理由留在绘制期。
 */
internal class NativeRingGeometry private constructor(
    override val viewportHeightPx: Int,
    val cellSizePx: Float,
    val columns: Int,
    val placements: List<NativeRingPlacement>,
    override val minimumScrollOffsetPx: Float,
    override val maximumScrollOffsetPx: Float,
) : NativeAppGridGeometry {

    override val itemCount: Int
        get() = placements.size

    override val itemWidthPx: Float
        get() = cellSizePx

    override val itemHeightPx: Float
        get() = cellSizePx

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
        // 半格的余量按**格子**算而不是按图标算：收拢位移最多把一格拉进来大半格，
        // 少留这一份就会出现「角上的格子被回收掉、收拢之后本该可见」的空洞。
        val halfCell = cellSizePx / 2f
        val minimumY = -extraLayoutSpacePx - halfCell
        val maximumY = viewportHeightPx + extraLayoutSpacePx + halfCell
        val firstRowScreenCenterY =
            viewportHeightPx / 2f + placements[0].centerY + scrollOffsetPx
        val lastRow = placements[placements.lastIndex].row
        val firstVisibleRow = ceil(
            ((minimumY - firstRowScreenCenterY) / cellSizePx).toDouble(),
        ).toInt().coerceAtLeast(0)
        val lastVisibleRow = floor(
            ((maximumY - firstRowScreenCenterY) / cellSizePx).toDouble(),
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
            cellSizePx: Float = nativeRingCellSizePx(
                viewportWidthPx = viewportWidthPx,
                leftSafeInsetPx = leftSafeInsetPx,
                columns = columns,
            ),
        ): NativeRingGeometry {
            require(itemCount >= 0)
            require(viewportWidthPx > 0 && viewportHeightPx > 0)
            require(cellSizePx.isFinite() && cellSizePx > 0f)
            val effectiveColumns = columns.coerceIn(1, NATIVE_RING_MAX_COLUMNS)
            val safeLeft = leftSafeInsetPx.coerceIn(0, viewportWidthPx - 1)
            val availableWidth = (viewportWidthPx - safeLeft).toFloat().coerceAtLeast(1f)
            val rowWidth = effectiveColumns * cellSizePx
            // 整块网格按可用区居中。传进来的格边长通常正好铺满（差值为 0），但调用方
            // 可以给一个更小的值（例如为了让格子不至于比图标还小），那时剩下的余量要
            // 左右均分 —— 收拢是绕**屏心**算的，网格若偏在一侧，四条弧就不对称了。
            val startX = safeLeft + (availableWidth - rowWidth) / 2f
            val rowCount = if (itemCount == 0) {
                0
            } else {
                (itemCount + effectiveColumns - 1) / effectiveColumns
            }
            // 整张网格纵向居中到 0，与蜂窝 / 心流同一个约定：首项能滚到屏心，末项也能。
            val yCenterOffset = (rowCount - 1).coerceAtLeast(0) * cellSizePx / 2f
            val placements = ArrayList<NativeRingPlacement>(itemCount)
            repeat(itemCount) { position ->
                val row = position / effectiveColumns
                val column = position % effectiveColumns
                placements += NativeRingPlacement(
                    position = position,
                    row = row,
                    column = column,
                    // 末行不满时按**整行宽度**左对齐（与蜂窝 / 心流同一处理）：
                    // 竖向列对齐是网格观感的一半，末行单独居中会让最后几个图标错开半格。
                    centerX = startX + (column + 0.5f) * cellSizePx,
                    centerY = row * cellSizePx - yCenterOffset,
                )
            }
            val firstY = placements.firstOrNull()?.centerY ?: 0f
            val lastY = placements.lastOrNull()?.centerY ?: 0f
            return NativeRingGeometry(
                viewportHeightPx = viewportHeightPx,
                cellSizePx = cellSizePx,
                columns = effectiveColumns,
                placements = placements,
                minimumScrollOffsetPx = -lastY,
                maximumScrollOffsetPx = -firstY,
            )
        }
    }
}

/** 列数上限，与 `honeycombNarrowColumns` / `denseGridColumns` 的夹取区间保持一致。 */
internal const val NATIVE_RING_MAX_COLUMNS: Int = 10

/**
 * 星环的格边长：可用宽度按列数等分，一格都不剩。
 *
 * 单独抽成函数是因为**两处**要用同一个数：几何在这里算格心，而
 * `nativeListDrawerConfig` 要把它写进 `ringCellSizePx` 给 `NativeAppListRowView.onMeasure`
 * 用。两边各估一份的话，格子测量出来的大小和格心间距会差几个像素，表现为「图标不在格心上」。
 */
internal fun nativeRingCellSizePx(
    viewportWidthPx: Int,
    leftSafeInsetPx: Int,
    columns: Int,
): Float {
    if (viewportWidthPx <= 0) return 0f
    val safeLeft = leftSafeInsetPx.coerceIn(0, viewportWidthPx - 1)
    val availableWidth = (viewportWidthPx - safeLeft).toFloat().coerceAtLeast(1f)
    return availableWidth / columns.coerceIn(1, NATIVE_RING_MAX_COLUMNS)
}

/**
 * 星环格子里图标的边长。
 *
 * [RING_ICON_CELL_FRACTION] 留出的那圈空隙就是图标之间的间距 —— 格心已经铺满，
 * 再想留空只能从图标身上让。`scale`（应用列表缩放设置）在这里生效，所以老师调小缩放时
 * 是图标变小、格子不动，网格的行列不会跟着重排。
 */
internal fun nativeRingIconSizePx(cellSizePx: Float, scale: Float): Int {
    if (!cellSizePx.isFinite() || cellSizePx <= 0f) return 0
    val safeScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (cellSizePx * RING_ICON_CELL_FRACTION * safeScale)
        .coerceIn(cellSizePx * 0.35f, cellSizePx)
        .toInt()
        .coerceAtLeast(1)
}

/**
 * 图标占格子的比例（缩放 100% 时）。
 *
 * 0.86 是「圆和圆之间看得出一道缝，但网格仍然读作铺满」的那一档：再大圆就贴上了，
 * 再小就退化成普通稀疏网格，星环那种「密到铺满屏」的观感消失。
 */
private const val RING_ICON_CELL_FRACTION = 0.86f
