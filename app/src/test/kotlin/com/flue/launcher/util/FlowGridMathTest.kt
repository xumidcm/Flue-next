package com.flue.launcher.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「心流」布局的两块纯几何：等列网格与月牙偏移。
 *
 * 这两个函数刻意不吃任何 Compose / View 类型，就是为了能在这里把边界钉死 ——
 * 月牙一旦进了布局属性就会自反馈抽搐（`ChordScale.kt` 记过一次），所以它的
 * 契约「只吃位置、只出一个数」必须由测试守住。
 */
class FlowGridMathTest {
    private val cell = 100f

    @Test
    fun `rows are equal width unlike honeycomb alternation`() {
        val offsets = generateFlowRows(count = 8, cols = 4, cellSize = cell)
        assertEquals(8, offsets.size)
        // 每行四列，两行；同列的 x 必须逐位相同（竖向列对齐）。
        repeat(4) { column ->
            assertEquals(
                offsets[column].x.toDouble(),
                offsets[column + 4].x.toDouble(),
                0.0,
            )
        }
        // 行内间距就是 cellSize，整行按整行宽度居中。
        assertEquals(-150f, offsets[0].x, 0f)
        assertEquals(-50f, offsets[1].x, 0f)
        assertEquals(50f, offsets[2].x, 0f)
        assertEquals(150f, offsets[3].x, 0f)
    }

    @Test
    fun `grid is vertically centred on zero`() {
        // 三行：y 应当是 -cell / 0 / +cell。
        val offsets = generateFlowRows(count = 9, cols = 3, cellSize = cell)
        assertEquals(-100f, offsets[0].y, 0f)
        assertEquals(0f, offsets[3].y, 0f)
        assertEquals(100f, offsets[6].y, 0f)
        // 单行时整张网格就在 0 上。
        val single = generateFlowRows(count = 2, cols = 4, cellSize = cell)
        assertTrue(single.all { it.y == 0f })
    }

    @Test
    fun `partial last row keeps column alignment instead of recentring`() {
        // 5 个图标 / 4 列：末行只有 1 个，它必须落在第 0 列上，而不是居中到 x = 0。
        val offsets = generateFlowRows(count = 5, cols = 4, cellSize = cell)
        assertEquals(offsets[0].x, offsets[4].x, 0f)
        assertEquals(-150f, offsets[4].x, 0f)
    }

    @Test
    fun `degenerate inputs yield no rows`() {
        assertTrue(generateFlowRows(count = 0, cols = 4, cellSize = cell).isEmpty())
        assertTrue(generateFlowRows(count = -3, cols = 4, cellSize = cell).isEmpty())
        assertTrue(generateFlowRows(count = 4, cols = 4, cellSize = 0f).isEmpty())
        assertTrue(generateFlowRows(count = 4, cols = 4, cellSize = Float.NaN).isEmpty())
        assertTrue(generateFlowRows(count = 4, cols = 4, cellSize = -10f).isEmpty())
    }

    @Test
    fun `columns below one are clamped to a single column`() {
        val offsets = generateFlowRows(count = 3, cols = 0, cellSize = cell)
        assertEquals(3, offsets.size)
        assertTrue(offsets.all { it.x == 0f })
        assertEquals(-100f, offsets[0].y, 0f)
        assertEquals(100f, offsets[2].y, 0f)
    }

    @Test
    fun `crescent peaks at the viewport centre and vanishes at both edges`() {
        val centre = 200f
        val height = 400f
        assertEquals(
            30f,
            crescent(rowCenterY = centre, viewportCenterY = centre, viewportHeight = height),
            0f,
        )
        // t = ±1 → 1 - t² = 0。
        assertEquals(
            0f,
            crescent(rowCenterY = 0f, viewportCenterY = centre, viewportHeight = height),
            0f,
        )
        assertEquals(
            0f,
            crescent(rowCenterY = height, viewportCenterY = centre, viewportHeight = height),
            0f,
        )
        // t = ±0.5 → 0.75 倍峰值，上下对称。
        assertEquals(
            22.5f,
            crescent(rowCenterY = centre + 100f, viewportCenterY = centre, viewportHeight = height),
            1e-4f,
        )
        assertEquals(
            22.5f,
            crescent(rowCenterY = centre - 100f, viewportCenterY = centre, viewportHeight = height),
            1e-4f,
        )
    }

    @Test
    fun `rows past the edges are clamped instead of bending back`() {
        // t 不夹取的话 |t| > 1 会让 1 - t² 变负，弧在屏外反向 —— 滚动时会看到
        // 边缘行往反方向甩。夹取之后一律停在 0。
        val far = crescent(rowCenterY = 9_000f, viewportCenterY = 200f, viewportHeight = 400f)
        assertEquals(0f, far, 0f)
        val negative = crescent(rowCenterY = -9_000f, viewportCenterY = 200f, viewportHeight = 400f)
        assertEquals(0f, negative, 0f)
    }

    @Test
    fun `left and right are exact mirrors`() {
        val ys = listOf(0f, 60f, 150f, 200f, 260f, 400f)
        ys.forEach { y ->
            val right = crescent(y, 200f, 400f, direction = FlowCrescentDirection.Right)
            val left = crescent(y, 200f, 400f, direction = FlowCrescentDirection.Left)
            assertEquals(-right, left, 0f)
        }
        assertEquals(1f, FlowCrescentDirection.Right.signum, 0f)
        assertEquals(-1f, FlowCrescentDirection.Left.signum, 0f)
    }

    @Test
    fun `unmeasured or meaningless inputs give no offset`() {
        assertEquals(0f, crescent(rowCenterY = 200f, viewportCenterY = 200f, viewportHeight = 0f), 0f)
        assertEquals(0f, crescent(rowCenterY = 200f, viewportCenterY = 200f, viewportHeight = -400f), 0f)
        assertEquals(0f, crescent(rowCenterY = Float.NaN), 0f)
        assertEquals(0f, crescent(viewportCenterY = Float.NaN), 0f)
        assertEquals(0f, crescent(viewportHeight = Float.NaN), 0f)
        assertEquals(0f, crescent(bulgePx = Float.NaN), 0f)
        assertEquals(0f, crescent(bulgePx = 0f), 0f)
    }

    @Test
    fun `a negative bulge simply reverses the arc`() {
        assertEquals(
            -crescent(bulgePx = 30f),
            crescent(bulgePx = -30f),
            0f,
        )
    }

    private fun crescent(
        rowCenterY: Float = 200f,
        viewportCenterY: Float = 200f,
        viewportHeight: Float = 400f,
        bulgePx: Float = 30f,
        direction: FlowCrescentDirection = FlowCrescentDirection.Right,
    ): Float = flowCrescentOffsetPx(
        rowCenterY = rowCenterY,
        viewportCenterY = viewportCenterY,
        viewportHeight = viewportHeight,
        bulgePx = bulgePx,
        direction = direction,
    )
}
