package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心流几何：等列网格的落位，以及月牙那条弧的两档语义。
 *
 * 越界是老师实机报的第一个心流问题：月牙原本是在居中网格上做 ±bulge 的对称平移，
 * 峰值那几行直接被推出右边界（截图里最右一列只剩半个图标）。现在基准取「峰值正好
 * 贴边」，偏移只在 `0..bulge` 之间往回收。
 *
 * 鼓出量按**「峰值贴边余量」的倍数**给：
 *
 * - 1.0（设置里的 100%）＝ 谁都不越界，这条硬约束由 `no row overflows` 那条守着；
 * - 最多到 1.5（150%）＝ 老师后来要的「弧再深一点」，上下两端那几行**故意**越过左边界。
 */
class NativeFlowGeometryTest {
    private val viewportWidth = 454
    private val viewportHeight = 454
    private val iconSize = 80f
    private val cellSize = iconSize * 1.02f

    /** 4 列一行实际占的宽度：3 个格心间距 + 首末各半个图标。 */
    private val rowWidth = 3 * cellSize + iconSize

    private fun geometry(
        itemCount: Int = 12,
        columns: Int = 4,
        leftSafeInsetPx: Int = 0,
        bulgeFraction: Float = 0f,
        toRight: Boolean = true,
    ) = NativeFlowGeometry.create(
        itemCount = itemCount,
        viewportWidthPx = viewportWidth,
        viewportHeightPx = viewportHeight,
        leftSafeInsetPx = leftSafeInsetPx,
        columns = columns,
        iconSizePx = iconSize,
        cellSizePx = cellSize,
        requestedCrescentBulgeFraction = bulgeFraction,
        crescentToRight = toRight,
    )

    /** 某一行在给定滚动位置下、加上月牙之后的横向占用范围。 */
    private fun rowSpan(
        flow: NativeFlowGeometry,
        row: Int,
        scrollOffsetPx: Float,
    ): ClosedFloatingPointRange<Float> {
        val positions = flow.placements.filter { it.row == row }
        val centerY = flow.screenCenterY(positions.first().position, scrollOffsetPx)
        val crescent = nativeFlowCrescentTranslationX(
            bulgePx = flow.crescentBulgePx,
            toRight = flow.crescentToRight,
            childCenterY = centerY,
            viewportHeight = viewportHeight.toFloat(),
        )
        val left = positions.minOf { it.centerX } + crescent - iconSize / 2f
        val right = positions.maxOf { it.centerX } + crescent + iconSize / 2f
        return left..right
    }

    @Test
    fun `columns stay aligned and rows are one cell apart`() {
        val flow = geometry(itemCount = 8)
        assertEquals(8, flow.itemCount)
        repeat(4) { column ->
            assertEquals(flow.centerX(column), flow.centerX(column + 4), 0f)
        }
        val firstRowY = flow.placements[0].centerY
        val secondRowY = flow.placements[4].centerY
        assertEquals(cellSize, secondRowY - firstRowY, 1e-3f)
    }

    @Test
    fun `full bulge is exactly the horizontal slack`() {
        // 100% 的定义：鼓出量正好等于「行宽之外剩下的空间」。
        val flow = geometry(bulgeFraction = 1f)
        assertEquals(viewportWidth - rowWidth, flow.crescentBulgePx, 1e-3f)
        assertTrue(flow.crescentBulgePx > 0f)
    }

    @Test
    fun `the bulge fraction is capped at one and a half`() {
        // 设置里的上限是 150%；再荒唐的请求也只到这里，否则中段那几行也会开始越界，
        // 弧就变成「整列往右挤」了。
        val slack = viewportWidth - rowWidth
        assertEquals(
            slack * NativeFlowGeometry.MAX_CRESCENT_BULGE_FRACTION,
            geometry(bulgeFraction = 99f).crescentBulgePx,
            1e-3f,
        )
        assertEquals(1.5f, NativeFlowGeometry.MAX_CRESCENT_BULGE_FRACTION, 0f)
    }

    @Test
    fun `no row overflows either edge at any scroll position`() {
        val flow = geometry(itemCount = 40, bulgeFraction = 1f)
        val rows = flow.placements.maxOf { it.row } + 1
        // 扫一遍整个滚动区间：月牙锚在视口中心，所以每一行都会依次经过弧顶。
        val steps = 24
        repeat(steps + 1) { step ->
            val scroll = flow.minimumScrollOffsetPx +
                (flow.maximumScrollOffsetPx - flow.minimumScrollOffsetPx) * step / steps
            repeat(rows) { row ->
                val span = rowSpan(flow, row, scroll)
                assertTrue(
                    "row $row at scroll $scroll starts at ${span.start}",
                    span.start >= -0.5f,
                )
                assertTrue(
                    "row $row at scroll $scroll ends at ${span.endInclusive}",
                    span.endInclusive <= viewportWidth + 0.5f,
                )
            }
        }
    }

    @Test
    fun `overshooting past full bulge deepens the arc on the far side only`() {
        // 150% 是**刻意**越界的一档：弧顶那侧仍然贴边不越过，越出去的是离中线最远的那几行。
        // 这正是老师要的「弧再深一点」—— 那块区域在圆屏上本来就在圆之外。
        val full = geometry(itemCount = 40, bulgeFraction = 1f)
        val deep = geometry(itemCount = 40, bulgeFraction = 1.5f)
        assertTrue(deep.crescentBulgePx > full.crescentBulgePx)
        val scroll = deep.maximumScrollOffsetPx
        val rows = deep.placements.maxOf { it.row } + 1
        var sawOverflow = false
        repeat(rows) { row ->
            val span = rowSpan(deep, row, scroll)
            // 右边界这条硬约束在任何倍数下都成立：基准 x 把弧顶钉在右缘。
            assertTrue(
                "row $row ended at ${span.endInclusive}",
                span.endInclusive <= viewportWidth + 0.5f,
            )
            if (span.start < -0.5f) sawOverflow = true
        }
        assertTrue("150% 应该至少有一行越过左边界，否则这一档等于白给", sawOverflow)
    }

    @Test
    fun `the arc peak sits flush against the chosen edge`() {
        val flow = geometry(itemCount = 40, bulgeFraction = 1f)
        // 弧顶那一行（centerY 恰好落在视口中线）应当正好贴右边。
        val scroll = flow.maximumScrollOffsetPx
        val peakRow = (0..flow.placements.maxOf { it.row }).minBy { row ->
            val position = flow.placements.first { it.row == row }.position
            kotlin.math.abs(flow.screenCenterY(position, scroll) - viewportHeight / 2f)
        }
        val span = rowSpan(flow, peakRow, scroll)
        // 弧顶行未必精确落在中线（行是离散的），所以留一格公差。
        assertTrue(
            "peak row right edge was ${span.endInclusive}",
            span.endInclusive > viewportWidth - cellSize,
        )
    }

    @Test
    fun `left facing arc mirrors to the other edge`() {
        val right = geometry(itemCount = 40, bulgeFraction = 1f, toRight = true)
        val left = geometry(itemCount = 40, bulgeFraction = 1f, toRight = false)
        assertEquals(right.crescentBulgePx, left.crescentBulgePx, 0f)
        // 两者的基准互为镜像：朝右时基准更靠左，朝左时更靠右。
        assertTrue(left.centerX(0) > right.centerX(0))
        val scroll = right.maximumScrollOffsetPx
        val leftSpan = rowSpan(left, 0, scroll)
        assertTrue("left arc must not overflow: ${leftSpan.start}", leftSpan.start >= -0.5f)
        assertTrue(
            "left arc must not overflow: ${leftSpan.endInclusive}",
            leftSpan.endInclusive <= viewportWidth + 0.5f,
        )
    }

    @Test
    fun `left safe inset is respected`() {
        val inset = 90
        val flow = geometry(itemCount = 40, leftSafeInsetPx = inset, bulgeFraction = 1f)
        // 余量是「可用宽度」之内的事，安全内边距先被扣掉。
        assertEquals(viewportWidth - inset - rowWidth, flow.crescentBulgePx, 1e-3f)
        val scroll = flow.maximumScrollOffsetPx
        repeat(flow.placements.maxOf { it.row } + 1) { row ->
            val span = rowSpan(flow, row, scroll)
            assertTrue("row $row started at ${span.start}", span.start >= inset - 0.5f)
        }
    }

    @Test
    fun `zero bulge degenerates to a plain grid pinned to the edge`() {
        val flow = geometry(itemCount = 12, bulgeFraction = 0f)
        assertEquals(0f, flow.crescentBulgePx, 0f)
        // 不鼓时弧顶与弧端重合，整张网格贴右边：最右一列的右缘正好是屏幕右边界。
        assertEquals(
            viewportWidth.toFloat(),
            flow.placements.maxOf { it.centerX } + iconSize / 2f,
            1e-3f,
        )
    }

    @Test
    fun `visible bounds cover a full row range`() {
        val flow = geometry(itemCount = 40)
        val output = IntArray(2)
        flow.visiblePositionBounds(
            scrollOffsetPx = flow.maximumScrollOffsetPx,
            extraLayoutSpacePx = 0f,
            output = output,
        )
        assertTrue(output[0] >= 0)
        assertTrue(output[1] >= output[0])
        assertTrue(output[1] <= flow.placements.lastIndex)
    }

    @Test
    fun `an empty adapter yields no placements and no bounds`() {
        val flow = geometry(itemCount = 0)
        assertEquals(0, flow.itemCount)
        val output = IntArray(2)
        flow.visiblePositionBounds(0f, 0f, output)
        assertEquals(-1, output[0])
        assertEquals(-1, output[1])
    }
}
