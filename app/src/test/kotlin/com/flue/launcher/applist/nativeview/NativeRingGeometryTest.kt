package com.flue.launcher.applist.nativeview

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 星环布局的几何与四边收拢。
 *
 * 这套布局的需求是老师给的：「圆屏上面和下面、左边右边都有弧度，向中间收缩靠拢；
 * 方屏那就上面下面左边右边都平铺」。这两条互斥的行为都被压在
 * [nativeRingConvergenceFraction] 一个纯函数里（`deviceShape` 判定刻意没放进布局
 * 管理器），所以这里能真的把它们测出来 —— 摆在 View 层就只能靠肉眼验。
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

    private fun offsetAt(
        centerX: Float,
        centerY: Float,
        config: NativeAppListConfig,
        viewportWidth: Float = 400f,
        viewportHeight: Float = 400f,
    ) = nativeRingConvergenceOffset(
        centerX = centerX,
        centerY = centerY,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        config = config,
    )

    // ---------- 验收点 5：格子尺寸 / 列数 / 行距 ----------

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

    // ---------- 验收点 2：方屏平铺 ----------

    /**
     * 方屏恒不收拢 —— 老师明确指定「方屏上面下面左边右边都平铺」。
     *
     * 连四个角这种收拢量最大的位置都必须是 0，否则四角会空出一片无用留白。
     */
    @Test
    fun `a rectangular screen never converges anywhere`() {
        val square = config(round = false)
        listOf(
            0f to 0f,
            400f to 0f,
            0f to 400f,
            400f to 400f,
            50f to 120f,
            380f to 390f,
        ).forEach { (x, y) ->
            val offset = offsetAt(x, y, square)
            assertEquals("x at ($x,$y)", 0f, offset.x, 0f)
            assertEquals("y at ($x,$y)", 0f, offset.y, 0f)
        }
    }

    // ---------- 验收点 1：圆屏四向收拢 ----------

    /** 收拢方向永远指向屏心，四个象限都成立。 */
    @Test
    fun `every quadrant is pulled towards the screen centre on a round screen`() {
        val round = config()
        // 左上：要往右下拉（x 正、y 正）。
        offsetAt(60f, 60f, round).let {
            assertTrue("左上应往右拉，实际 x=${it.x}", it.x > 0f)
            assertTrue("左上应往下拉，实际 y=${it.y}", it.y > 0f)
        }
        // 右上：往左下。
        offsetAt(340f, 60f, round).let {
            assertTrue(it.x < 0f)
            assertTrue(it.y > 0f)
        }
        // 左下：往右上。
        offsetAt(60f, 340f, round).let {
            assertTrue(it.x > 0f)
            assertTrue(it.y < 0f)
        }
        // 右下：往左上。
        offsetAt(340f, 340f, round).let {
            assertTrue(it.x < 0f)
            assertTrue(it.y < 0f)
        }
    }

    /**
     * 一整行被拽成弧，这就是「上面和下面都有弧度」的来源。
     *
     * 同一行里越靠外的列径向距离越大、收拢比例越高，于是**纵向**被拉得更多 ——
     * 一条直排自然弯成弧。若外侧列的纵向位移不大于内侧，那一行就还是直的。
     */
    @Test
    fun `outer columns of one row are pulled further so the row bends into an arc`() {
        val round = config()
        val rowY = 100f
        val inner = abs(offsetAt(220f, rowY, round).y)
        val middle = abs(offsetAt(280f, rowY, round).y)
        val outer = abs(offsetAt(360f, rowY, round).y)

        assertTrue("中列($middle) 应比内列($inner) 拉得多", middle > inner)
        assertTrue("外列($outer) 应比中列($middle) 拉得多", outer > middle)
    }

    /**
     * 一整列被收窄，这就是「左边右边都有弧度」的来源。
     *
     * 与上一条同一个公式的另一个分量：同一列里越靠上下端的行，横向被收得越多。
     */
    @Test
    fun `outer rows of one column are pulled further so the column bends inwards`() {
        val round = config()
        val columnX = 100f
        val inner = abs(offsetAt(columnX, 220f, round).x)
        val outer = abs(offsetAt(columnX, 360f, round).x)

        assertTrue("外行($outer) 应比内行($inner) 收得多", outer > inner)
    }

    /** 屏心那一格几乎不动 —— 弧才有「从中间鼓出去」的观感，而不是整张网平移。 */
    @Test
    fun `the cell at the screen centre barely moves`() {
        val offset = offsetAt(200f, 200f, config())

        assertEquals(0f, offset.x, 0.001f)
        assertEquals(0f, offset.y, 0.001f)
    }

    /**
     * 收拢比例恒小于 1：任何设置下格子都只会靠近屏心，绝不穿过屏心翻到对面。
     *
     * 这条守的是 `k·t²/(1+k·t²)` 这个形状本身。换成线性的 `k·t²`，强度一调大
     * 角上的格子就会翻面，画面直接是乱码。
     */
    @Test
    fun `convergence never overshoots the centre even at maximum strength`() {
        val maxStrength = config(fisheyeStrength = 2f, fisheyeRangeRows = 8)
        // 远在屏外的角（t 远大于 1）也不能翻面：位移量必须小于到屏心的距离。
        listOf(60f to 60f, 400f to 400f, 0f to 0f, 396f to 4f).forEach { (x, y) ->
            val offset = offsetAt(x, y, maxStrength)
            assertTrue(
                "($x,$y) 横向翻面：位移 ${offset.x} 越过了距屏心 ${x - 200f}",
                abs(offset.x) < abs(x - 200f) + 0.001f,
            )
            assertTrue(
                "($x,$y) 纵向翻面：位移 ${offset.y} 越过了距屏心 ${y - 200f}",
                abs(offset.y) < abs(y - 200f) + 0.001f,
            )
        }
    }

    // ---------- 验收点 3：接入已有的鱼眼设置 ----------

    /** 鱼眼关掉时完全不收拢 —— 收拢是鱼眼的一部分，不是独立效果。 */
    @Test
    fun `turning the fish-eye off flattens a round screen too`() {
        val offset = offsetAt(60f, 60f, config(fisheyeEnabled = false))

        assertEquals(0f, offset.x, 0f)
        assertEquals(0f, offset.y, 0f)
    }

    /** 鱼眼强度越大收拢越狠，强度 0 等于不收拢。 */
    @Test
    fun `convergence scales monotonically with the existing fish-eye strength`() {
        val amounts = listOf(0f, 0.5f, 1f, 1.5f, 2f).map { strength ->
            abs(offsetAt(60f, 60f, config(fisheyeStrength = strength)).x)
        }

        assertEquals(0f, amounts.first(), 0f)
        amounts.zipWithNext().forEach { (weaker, stronger) ->
            assertTrue("强度更大却收得更少：$weaker -> $stronger", stronger > weaker)
        }
    }

    /**
     * 鱼眼的「生效行数」决定中心那块不动的稳定核。
     *
     * 8 行 = 整屏都参与，1 行 = 只有最外一圈卷边。所以同一个中距点，行数越少收得越少。
     */
    @Test
    fun `the fish-eye row range controls how large the untouched core is`() {
        val wide = abs(offsetAt(140f, 140f, config(fisheyeRangeRows = 8)).x)
        val narrow = abs(offsetAt(140f, 140f, config(fisheyeRangeRows = 1)).x)

        assertTrue("生效行数 8 应比 1 收得多：$wide vs $narrow", wide > narrow)
        // 1 行时稳定核是 0.75 半径，这个点落在核内，应当完全不动。
        assertEquals(0f, narrow, 0.001f)
    }

    // ---------- 验收点 4：边缘图标更小 ----------

    /** 越靠边的格子缩得越小；鱼眼关掉时全部 1.0。 */
    @Test
    fun `icons shrink towards the edge and stay full size without the fish-eye`() {
        val round = config()
        val centre = scaleAt(200f, 200f, round)
        // 取样点必须落在稳定核**之外**：默认 4 行对应核半径 0.5，也就是距屏心 100px
        // 以内完全不缩。300f 正好压在核边界上，那里缩放本来就该是 1。
        val middle = scaleAt(200f, 340f, round)
        val edge = scaleAt(200f, 396f, round)

        assertEquals(1f, centre, 0.001f)
        assertTrue("中距($middle) 应比屏心($centre) 小", middle < centre)
        assertTrue("边缘($edge) 应比中距($middle) 更小", edge < middle)
        assertTrue("缩放不能到 0：$edge", edge > 0f)

        // 核内不缩，这是「中心那块不动」的另一面。
        assertEquals(1f, scaleAt(200f, 300f, round), 0.001f)

        // 鱼眼关掉：连最边缘也是原尺寸。
        assertEquals(1f, scaleAt(200f, 396f, config(fisheyeEnabled = false)), 0.001f)
    }

    private fun scaleAt(x: Float, y: Float, cfg: NativeAppListConfig) = nativeRingChildVisual(
        centerX = x,
        centerY = y,
        viewportWidth = 400f,
        viewportHeight = 400f,
        config = cfg,
    ).scale

    /**
     * 缩放恒在 (0, 1] 内，圆屏内切圆之外的角落也不例外。
     *
     * 等比不用测 —— [NativeSpatialChildVisual] 只有一个 `scale` 字段，横纵不可能不等，
     * 那是类型层面就成立的事。真正会出事的是**钳位**：径向进度在四个角上大于 1，
     * 缩放侧必须自己夹住，夹漏了角上图标会缩到看不见甚至翻成负数镜像。
     */
    @Test
    fun `the fish-eye scale stays within a sane range everywhere`() {
        val maxStrength = config(fisheyeStrength = 2f, fisheyeRangeRows = 8)
        listOf(
            200f to 200f,
            60f to 60f,
            340f to 390f,
            // 内切圆之外：径向进度 > 1。
            0f to 0f,
            400f to 400f,
            420f to -20f,
        ).forEach { (x, y) ->
            listOf(config(), maxStrength).forEach { cfg ->
                val scale = scaleAt(x, y, cfg)
                assertTrue("($x,$y) 缩放非正：$scale", scale > 0f)
                assertTrue("($x,$y) 缩放超过 1：$scale", scale <= 1f + 0.0001f)
            }
        }
    }

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

    // ---------- 验收点 6：边界 ----------

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
