package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 星环布局的几何与鱼眼。
 *
 * 2026-08 起星环**不分屏形**：圆屏专属的径向收拢与安全轮廓已删（老师要求一律按
 * 方屏平铺渲染），两种屏共用同一套平铺几何与「按生效行数触发的上下边缘鱼眼」。
 * 行为全部压在 [nativeRingFisheyeProgress] / [nativeRingChildVisual] 纯函数里，
 * 这里能真的把它们测出来 —— 摆在 View 层就只能靠肉眼验。
 */
class NativeRingGeometryTest {

    private fun config(
        round: Boolean = true,
        fisheyeEnabled: Boolean = true,
        fisheyeStrength: Float = 1f,
        fisheyeRangeRows: Int = 4,
        leftSafeInsetPx: Int = 0,
    ) = NativeAppListConfig(
        presentation = NativeAppListPresentation.RING,
        deviceShape = if (round) NativeDeviceShape.ROUND else NativeDeviceShape.RECTANGULAR,
        fisheyeEnabled = fisheyeEnabled,
        fisheyeStrength = fisheyeStrength,
        honeycombFisheyeRangeRows = fisheyeRangeRows,
        leftSafeInsetPx = leftSafeInsetPx,
    )

    // ---------- 格子尺寸 / 列数 / 行距 ----------

    /** 可用宽度按列数等分，一格都不剩 —— 星环不留内边距也不留格间距。 */
    @Test
    fun `cell size divides the available width with no gap left over`() {
        assertEquals(100f, nativeRingCellSizePx(400, 0, 4), 0.001f)
        assertEquals(80f, nativeRingCellSizePx(400, 0, 5), 0.001f)
        // 左安全区要从可用宽度里先扣掉。
        assertEquals(90f, nativeRingCellSizePx(400, 40, 4), 0.001f)
    }

    /**
     * 格边长横纵同值，且格心间距**正好等于**格边长。
     *
     * 这条盯的是「图标不在格心上」那类错位：几何按 cellSize 排格心，而
     * `NativeAppListRowView.onMeasure` 另外拿 `ringCellSizePx` 测量格子大小。
     * 两个数一漂，图标就会离格心越来越远。
     */
    @Test
    fun `cell pitch equals the cell edge in both axes`() {
        val geometry = NativeRingGeometry.create(
            itemCount = 12,
            viewportWidthPx = 400,
            viewportHeightPx = 400,
            leftSafeInsetPx = 0,
            columns = 4,
        )

        assertEquals(100f, geometry.cellSizePx, 0.001f)
        assertEquals(geometry.itemWidthPx, geometry.itemHeightPx, 0.001f)
        // 同一行相邻两列的横向间距 = 格边长。
        assertEquals(100f, geometry.centerX(1) - geometry.centerX(0), 0.001f)
        // 相邻两行的纵向间距 = 格边长。
        val rowPitch = geometry.screenCenterY(4, 0f) - geometry.screenCenterY(0, 0f)
        assertEquals(100f, rowPitch, 0.001f)
    }

    /** 整张网格纵向居中到 0：首项能滚到屏心，末项也能（与蜂窝 / 心流同一约定）。 */
    @Test
    fun `the grid is centred so both ends can reach the screen centre`() {
        val geometry = NativeRingGeometry.create(
            itemCount = 12,
            viewportWidthPx = 400,
            viewportHeightPx = 400,
            leftSafeInsetPx = 0,
            columns = 4,
        )

        // 3 行，行距 100 → 首行 -100、末行 +100，相对屏心对称。
        assertEquals(-100f, geometry.screenCenterY(0, 0f) - 200f, 0.001f)
        assertEquals(100f, geometry.screenCenterY(11, 0f) - 200f, 0.001f)
        assertEquals(-100f, geometry.minimumScrollOffsetPx, 0.001f)
        assertEquals(100f, geometry.maximumScrollOffsetPx, 0.001f)
    }

    /** 末行不满时按整行宽度左对齐，竖向列必须仍然对齐。 */
    @Test
    fun `a short last row keeps its columns aligned with the rows above`() {
        val geometry = NativeRingGeometry.create(
            itemCount = 6,
            viewportWidthPx = 400,
            viewportHeightPx = 400,
            leftSafeInsetPx = 0,
            columns = 4,
        )

        // 第 4、5 项是末行的第 0、1 列，横坐标必须和首行第 0、1 列一致。
        assertEquals(geometry.centerX(0), geometry.centerX(4), 0.001f)
        assertEquals(geometry.centerX(1), geometry.centerX(5), 0.001f)
    }

    // ---------- 鱼眼：生效行数决定触发带，两种屏形同一份 ----------

    /**
     * 触发带宽度 = 生效行数 × 格边长（上限半屏）。
     *
     * 以前带宽写死一行，行数滑杆是摆设 —— 老师报「生效行数不生效」就是它。
     * 1 行只卷最贴边那排，4 行带伸到半屏（上限），屏心那格永远在带外。
     */
    @Test
    fun `the trigger band follows the fisheye row range`() {
        val oneRow = config(fisheyeRangeRows = 1).copy(ringCellSizePx = 100f)
        val fourRows = config(fisheyeRangeRows = 4).copy(ringCellSizePx = 100f)

        // 1 行：带 = 一格。150 在带外，50 在带内。
        assertEquals(0f, nativeRingFisheyeProgress(150f, 400f, oneRow), 0.001f)
        assertEquals(0f, nativeRingFisheyeProgress(200f, 400f, oneRow), 0.001f)
        assertTrue(nativeRingFisheyeProgress(50f, 400f, oneRow) > 0f)
        assertTrue(nativeRingFisheyeProgress(350f, 400f, oneRow) > 0f)

        // 4 行：带 = 四格，夹到半屏（200）。150 进了带内，屏心仍在带外。
        assertTrue(nativeRingFisheyeProgress(150f, 400f, fourRows) > 0f)
        assertEquals(0f, nativeRingFisheyeProgress(200f, 400f, fourRows), 0.001f)

        // 鱼眼关掉时严格为 0（缩放侧据此恒 1）。
        assertEquals(
            0f,
            nativeRingFisheyeProgress(50f, 400f, oneRow.copy(fisheyeEnabled = false)),
            0.001f,
        )
    }

    /**
     * 圆屏与方屏表现**完全一致** —— 星环已经没有圆屏专属路径。
     *
     * 这条钉住「删掉圆屏特殊适配」：任何（x, y）上两种屏形的缩放、透明度、
     * 位移都必须逐位相同。若哪天有人往回加形状分支，这条会先红。
     */
    @Test
    fun `round and rectangular screens render the ring identically`() {
        listOf(200f to 200f, 200f to 50f, 60f to 60f, 340f to 350f).forEach { (x, y) ->
            val roundVisual = visualAt(x, y, config(round = true), 400f, 400f)
            val rectVisual = visualAt(x, y, config(round = false), 400f, 400f)
            assertEquals("($x,$y) 缩放分屏形", roundVisual.scale, rectVisual.scale, 0f)
            assertEquals("($x,$y) 透明度分屏形", roundVisual.alpha, rectVisual.alpha, 0f)
            assertEquals("($x,$y) 横向位移分屏形", roundVisual.translationX, rectVisual.translationX, 0f)
            assertEquals("($x,$y) 纵向位移分屏形", roundVisual.translationY, rectVisual.translationY, 0f)
        }
    }

    /** 触发行缩小、屏心行保持 1.0；鱼眼关掉时整屏统一大小。 */
    @Test
    fun `icons shrink inside the band and stay full size without the fish-eye`() {
        val cfg = config().copy(ringCellSizePx = 100f)

        val interior = visualAt(200f, 200f, cfg, 400f, 400f)
        val bandTop = visualAt(200f, 50f, cfg, 400f, 400f)
        val bandEdge = visualAt(200f, 4f, cfg, 400f, 400f)
        assertEquals(1f, interior.scale, 0.001f)
        assertEquals(1f, interior.alpha, 0.001f)
        assertTrue("带内行应缩小", bandTop.scale < 1f)
        assertTrue("越贴边缩得越多", bandEdge.scale < bandTop.scale)
        assertTrue("缩放不能到 0", bandEdge.scale > 0f)
        assertEquals(0f, interior.translationX, 0.001f)

        // 鱼眼关掉：连最贴边也是原尺寸、不透明。
        val flat = config(fisheyeEnabled = false).copy(ringCellSizePx = 100f)
        assertEquals(1f, visualAt(200f, 4f, flat, 400f, 400f).scale, 0.001f)
        assertEquals(1f, visualAt(200f, 4f, flat, 400f, 400f).alpha, 0.001f)
    }

    private fun visualAt(
        x: Float,
        y: Float,
        cfg: NativeAppListConfig,
        viewportWidth: Float = 400f,
        viewportHeight: Float = 400f,
    ) = nativeRingChildVisual(
        centerX = x,
        centerY = y,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        config = cfg,
    )

    // ---------- 图标尺寸 ----------

    /**
     * 图标比格子小一号，让出的那圈就是图标间距；应用列表缩放只动图标、不动格子。
     *
     * 「不动格子」很重要：缩放若改格边长，行列会随缩放重排，调一下滑块整张网就跳一次。
     */
    @Test
    fun `icon size leaves a gap inside the cell and clamps at both ends`() {
        assertEquals(86, nativeRingIconSizePx(100f, 1f))
        // 调小只影响图标。
        assertTrue(nativeRingIconSizePx(100f, 0.6f) < 86)
        // 上限是格边长本身：图标不能比格子还大，否则相邻图标相交。
        assertEquals(100, nativeRingIconSizePx(100f, 5f))
        // 下限兜住，别缩到看不见。
        assertEquals(35, nativeRingIconSizePx(100f, 0.01f))
        // 非法输入不能把布局搞崩。
        assertEquals(0, nativeRingIconSizePx(0f, 1f))
        assertEquals(0, nativeRingIconSizePx(Float.NaN, 1f))
        assertEquals(86, nativeRingIconSizePx(100f, Float.NaN))
    }

    // ---------- 边界 ----------

    /** 非法列数要被合法化，不能让 0 或负数传进除法。 */
    @Test
    fun `illegal column counts are legalised`() {
        listOf(0, -3, 999).forEach { requested ->
            val geometry = NativeRingGeometry.create(
                itemCount = 8,
                viewportWidthPx = 400,
                viewportHeightPx = 400,
                leftSafeInsetPx = 0,
                columns = requested,
            )
            assertTrue(
                "columns=$requested 被算成 ${geometry.columns}",
                geometry.columns in 1..NATIVE_RING_MAX_COLUMNS,
            )
        }
        assertEquals(0f, nativeRingCellSizePx(400, 0, 0).let { 400f / 1f - it }, 0.001f)
    }

    /** 空列表：没有格心，滚动范围为零，可见区间报 -1。 */
    @Test
    fun `an empty list has no scroll range and reports no visible window`() {
        val geometry = NativeRingGeometry.create(
            itemCount = 0,
            viewportWidthPx = 400,
            viewportHeightPx = 400,
            leftSafeInsetPx = 0,
            columns = 4,
        )
        val bounds = IntArray(2)
        geometry.visiblePositionBounds(0f, 0f, bounds)

        assertEquals(0, geometry.itemCount)
        assertEquals(0f, geometry.minimumScrollOffsetPx, 0f)
        assertEquals(0f, geometry.maximumScrollOffsetPx, 0f)
        assertEquals(-1, bounds[0])
        assertEquals(-1, bounds[1])
    }

    /** 只有一个图标：它就停在屏心，上下都不能滚。 */
    @Test
    fun `a single icon sits at the centre and cannot scroll`() {
        val geometry = NativeRingGeometry.create(
            itemCount = 1,
            viewportWidthPx = 400,
            viewportHeightPx = 400,
            leftSafeInsetPx = 0,
            columns = 4,
        )

        assertEquals(200f, geometry.screenCenterY(0, 0f), 0.001f)
        assertEquals(0f, geometry.minimumScrollOffsetPx, 0.001f)
        assertEquals(0f, geometry.maximumScrollOffsetPx, 0.001f)
    }

    /**
     * 可见区间按整行给，且留半格余量。
     *
     * 余量按**格子**算而不是按图标算：收拢位移最多把一格拉进来大半格，少留这一份
     * 就会出现「角上的格子被回收掉、收拢之后本该可见」的空洞。
     */
    @Test
    fun `the visible window is row aligned and keeps half a cell of slack`() {
        val geometry = NativeRingGeometry.create(
            itemCount = 40,
            viewportWidthPx = 400,
            viewportHeightPx = 400,
            leftSafeInsetPx = 0,
            columns = 4,
        )
        val bounds = IntArray(2)
        geometry.visiblePositionBounds(geometry.maximumScrollOffsetPx, 0f, bounds)

        // 顶部：从第 0 项开始，且区间右端落在整行边界（列数的整数倍减一）。
        assertEquals(0, bounds[0])
        assertEquals(0, (bounds[1] + 1) % geometry.columns)
        assertTrue("区间右端 ${bounds[1]} 超出列表", bounds[1] <= 39)
    }
}
