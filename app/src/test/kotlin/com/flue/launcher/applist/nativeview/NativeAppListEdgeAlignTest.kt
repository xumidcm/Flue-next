package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「应用列表贴边对齐」开关的几何不变量。
 *
 * 开关关掉时五种布局各自沿用原来的约定：滚动上下界把**首项与末项都摆到视口正中**
 * （`maximumScrollOffsetPx = -firstY`），而 [NativeAppGridLayoutManager] 恰好拿
 * `maximumScrollOffsetPx` 当初始滚动位置的兜底值 —— 这就是「一进应用列表首行停在
 * 屏幕中间」的来源。开关打开后上下界改由 [nativeAppListScrollBounds] 算成贴边位置，
 * 那条兜底路径于是自动落到「首行贴顶」。
 *
 * 这里同时钉住两侧：贴边档必须贴顶贴底，居中档必须逐位保持原样（防回归）。
 */
class NativeAppListEdgeAlignTest {

    private val viewportHeight = 454
    private val edgePadding = 8f

    // ------------------------------------------------------------ 纯函数

    @Test
    fun `centred bounds keep the legacy first-and-last-row-at-centre convention`() {
        val (minimum, maximum) = nativeAppListScrollBounds(
            viewportHeightPx = viewportHeight,
            firstY = -180f,
            lastY = 180f,
            itemHeightPx = 80f,
            edgePaddingPx = edgePadding,
            centerAligned = true,
        )

        // edgePadding 在居中档必须被完全忽略：min = -lastY、max = -firstY。
        assertEquals(-180f, minimum, 0.001f)
        assertEquals(180f, maximum, 0.001f)
    }

    @Test
    fun `edge bounds put the first row at the top padding and the last row at the bottom`() {
        val firstY = -180f
        val lastY = 180f
        val itemHeight = 80f
        val (minimum, maximum) = nativeAppListScrollBounds(
            viewportHeightPx = viewportHeight,
            firstY = firstY,
            lastY = lastY,
            itemHeightPx = itemHeight,
            edgePaddingPx = edgePadding,
            centerAligned = false,
        )

        // 首行中心 = 顶部留白 + 半个行高。
        val firstRowScreenY = viewportHeight / 2f + firstY + maximum
        assertEquals(edgePadding + itemHeight / 2f, firstRowScreenY, 0.001f)
        assertEquals(48f, firstRowScreenY, 0.001f)

        // 末行中心 = 屏高 - 顶部留白 - 半个行高（上下对称）。
        val lastRowScreenY = viewportHeight / 2f + lastY + minimum
        assertEquals(
            viewportHeight - edgePadding - itemHeight / 2f,
            lastRowScreenY,
            0.001f,
        )
        assertEquals(406f, lastRowScreenY, 0.001f)

        // 内容 440 + 上下各 8 = 456 仅比视口 454 多出 2px，所以可滚动余量就是 2px。
        assertEquals(-1f, minimum, 0.001f)
        assertEquals(1f, maximum, 0.001f)
        assertTrue(minimum < maximum)
    }

    @Test
    fun `content shorter than one screen cannot scroll and still pins the first row to the top`() {
        // 只有一行：firstY == lastY == 0，内容高度 80 远小于视口 454。
        val (minimum, maximum) = nativeAppListScrollBounds(
            viewportHeightPx = viewportHeight,
            firstY = 0f,
            lastY = 0f,
            itemHeightPx = 80f,
            edgePaddingPx = edgePadding,
            centerAligned = false,
        )

        // 不产生滚动，首行落在贴顶位置（这正是开关的语义：不要居中）。
        assertEquals(minimum, maximum, 0f)
        val rowScreenY = viewportHeight / 2f + maximum
        assertEquals(edgePadding + 40f, rowScreenY, 0.001f)
    }

    @Test
    fun `negative or non-finite padding degrades to a hard flush edge without crashing`() {
        val negative = nativeAppListScrollBounds(
            viewportHeightPx = viewportHeight,
            firstY = -180f,
            lastY = 180f,
            itemHeightPx = 80f,
            edgePaddingPx = -12f,
            centerAligned = false,
        )
        val nonFinite = nativeAppListScrollBounds(
            viewportHeightPx = viewportHeight,
            firstY = -180f,
            lastY = 180f,
            itemHeightPx = 80f,
            edgePaddingPx = Float.NaN,
            centerAligned = false,
        )

        assertEquals(negative, nonFinite)
        // 留白被夹到 0：首行中心正好是半个行高。
        assertEquals(40f, viewportHeight / 2f - 180f + negative.second, 0.001f)
    }

    @Test
    fun `non-finite placement input yields a stable non-scrolling geometry`() {
        val bounds = nativeAppListScrollBounds(
            viewportHeightPx = viewportHeight,
            firstY = Float.NaN,
            lastY = 180f,
            itemHeightPx = 80f,
            edgePaddingPx = edgePadding,
            centerAligned = false,
        )

        assertEquals(0f, bounds.first, 0f)
        assertEquals(0f, bounds.second, 0f)
    }

    // ------------------------------------------------- 四种几何：贴边档

    @Test
    fun `every spatial geometry pins the first row to the top and the last row to the bottom`() {
        geometries(edgePaddingPx = edgePadding, centerAligned = false).forEach { (name, geometry) ->
            val firstScreenY = geometry.screenCenterY(0, geometry.maximumScrollOffsetPx)
            val lastScreenY = geometry.screenCenterY(
                geometry.itemCount - 1,
                geometry.minimumScrollOffsetPx,
            )

            assertEquals(
                "$name 首行应贴顶（留白 $edgePadding + 半个行高）",
                edgePadding + geometry.itemHeightPx / 2f,
                firstScreenY,
                0.001f,
            )
            assertEquals(
                "$name 末行应贴底",
                viewportHeight - edgePadding - geometry.itemHeightPx / 2f,
                lastScreenY,
                0.001f,
            )
        }
    }

    @Test
    fun `every spatial geometry keeps scrollable range strictly positive with enough items`() {
        geometries(edgePaddingPx = edgePadding, centerAligned = false).forEach { (name, geometry) ->
            assertTrue(
                "$name 贴边后仍必须有可滚动余量",
                geometry.minimumScrollOffsetPx < geometry.maximumScrollOffsetPx,
            )
        }
    }

    @Test
    fun `every spatial geometry still centres both ends when the switch is off`() {
        geometries(edgePaddingPx = 0f, centerAligned = true).forEach { (name, geometry) ->
            assertEquals(
                "$name 居中档首行仍在屏心",
                viewportHeight / 2f,
                geometry.screenCenterY(0, geometry.maximumScrollOffsetPx),
                0.001f,
            )
            assertEquals(
                "$name 居中档末行仍在屏心",
                viewportHeight / 2f,
                geometry.screenCenterY(
                    geometry.itemCount - 1,
                    geometry.minimumScrollOffsetPx,
                ),
                0.001f,
            )
        }
    }

    /**
     * 走滚动边界的三种几何，placement **必须逐位相同** —— 开关只许改边界。
     *
     * 刻意不含密集网格：它没有可重新定义的下界（`maximumScrollOffsetPx` 恒为 0），
     * 首行落点是由 `verticalPadding` 加进 placement 的 `centerY` 决定的，所以贴边档
     * 下它的 placement 本来就会整体上移。那是它既有的定位方式，不是这里的退化。
     *
     * 若哪天有人把蜂窝 / 星环 / 心流的贴边也实现成「重建时改动 placement」，这条会
     * 先红：那会让已保存的拖拽顺序锚点、鱼眼半径基准、月牙弧全部跟着位移。
     */
    @Test
    fun `scroll-bounded geometries never move the placements themselves`() {
        val centred = geometries(edgePaddingPx = 0f, centerAligned = true)
        val edged = geometries(edgePaddingPx = edgePadding, centerAligned = false)

        listOf("honeycomb", "ring", "flow").forEach { name ->
            val centreGeometry = centred.getValue(name)
            val edgeGeometry = edged.getValue(name)
            for (position in 0 until centreGeometry.itemCount) {
                assertEquals(
                    "$name 第 $position 项横坐标被改动了",
                    centreGeometry.centerX(position),
                    edgeGeometry.centerX(position),
                    0f,
                )
                assertEquals(
                    "$name 第 $position 项纵坐标被改动了",
                    centreGeometry.screenCenterY(position, 0f),
                    edgeGeometry.screenCenterY(position, 0f),
                    0f,
                )
            }
        }
    }

    /**
     * 密集网格的贴边靠 placement 整体上移实现：首行必须落到离顶 `edgePadding` 处。
     *
     * 它 `maximumScrollOffsetPx` 恒为 0，所以不能像另外三种那样靠重定义边界来贴顶。
     */
    @Test
    fun `dense grid reaches the top padding by shifting its own placements`() {
        val geometry = geometries(edgePaddingPx = edgePadding, centerAligned = false)
            .getValue("dense-grid")

        assertEquals(0f, geometry.maximumScrollOffsetPx, 0f)
        assertEquals(
            edgePadding + geometry.itemHeightPx / 2f,
            geometry.screenCenterY(0, geometry.maximumScrollOffsetPx),
            0.001f,
        )
    }

    // ------------------------------------------------------------ 夹具

    /**
     * 四种空间几何（密集网格 / 蜂窝 / 星流 / 心流）各建一份。
     *
     * 密集网格是个特例：它不走 `nativeAppListScrollBounds`，而是用 `verticalPadding`
     * 把首行直接放到离顶 `edgePaddingPx` 的位置，`maximumScrollOffsetPx` 恒为 0。
     * 所以「首行贴顶」对它是等价的，但 `maximumScrollOffsetPx` 不参与那条公式。
     */
    private fun geometries(
        edgePaddingPx: Float,
        centerAligned: Boolean,
    ): Map<String, NativeAppGridGeometry> = linkedMapOf(
        "dense-grid" to NativeDenseGridGeometry.create(
            itemCount = 30,
            viewportWidthPx = 454,
            viewportHeightPx = viewportHeight,
            leftSafeInsetPx = 0,
            columns = 3,
            horizontalPaddingPx = 8f,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemHeightPx = 48f,
            edgePaddingPx = edgePaddingPx,
            centerAligned = centerAligned,
        ),
        "honeycomb" to NativeHoneycombGeometry.create(
            itemCount = 30,
            viewportWidthPx = 454,
            viewportHeightPx = viewportHeight,
            leftSafeInsetPx = 0,
            narrowColumns = 3,
            iconSizePx = 64f,
            edgePaddingPx = edgePaddingPx,
            centerAligned = centerAligned,
        ),
        "ring" to NativeRingGeometry.create(
            itemCount = 30,
            viewportWidthPx = 454,
            viewportHeightPx = viewportHeight,
            leftSafeInsetPx = 0,
            columns = 3,
            edgePaddingPx = edgePaddingPx,
            centerAligned = centerAligned,
        ),
        "flow" to NativeFlowGeometry.create(
            itemCount = 30,
            viewportWidthPx = 454,
            viewportHeightPx = viewportHeight,
            leftSafeInsetPx = 0,
            columns = 3,
            iconSizePx = 64f,
            cellSizePx = 64f * 1.02f,
            edgePaddingPx = edgePaddingPx,
            centerAligned = centerAligned,
        ),
    )
}
