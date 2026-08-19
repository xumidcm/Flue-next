package com.flue.launcher.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ChordScaleTest {
    // 圆心在 500，半径 500（视口 1000×1000）。
    private val width = 1000f
    private val centerY = 500f

    @Test
    fun `element at the circle center gets full width`() {
        assertEquals(1f, chordScaleForPosition(centerY, width, centerY), 0f)
    }

    @Test
    fun `chord matches hand-computed geometry`() {
        // d = 300 → chord = 2√(500²−300²) = 2×400 = 800 → 0.8
        assertEquals(0.8f, chordScaleForPosition(centerY + 300f, width, centerY), 0.0001f)
        // 对称：上下等距应得到同一个缩放
        assertEquals(0.8f, chordScaleForPosition(centerY - 300f, width, centerY), 0.0001f)
        // d = 250 → chord = 2√(500²−250²) = 2×433.0127 → 0.8660
        val expected = 2f * sqrt(500f * 500f - 250f * 250f) / width
        assertEquals(expected, chordScaleForPosition(centerY + 250f, width, centerY), 0.0001f)
    }

    @Test
    fun `scale is clamped at the readability floor`() {
        // d = r → 弦长 0，被夹到下限而不是 0
        assertEquals(MIN_CHORD_SCALE, chordScaleForPosition(centerY + 500f, width, centerY), 0f)
        // 滚出圆外（d > r）也不能产生 NaN 或负数
        val outside = chordScaleForPosition(centerY + 900f, width, centerY)
        assertEquals(MIN_CHORD_SCALE, outside, 0f)
        assertTrue(!outside.isNaN())
    }

    @Test
    fun `scale never exceeds one and decreases monotonically away from center`() {
        var previous = 1f
        for (d in 0..500 step 25) {
            val scale = chordScaleForPosition(centerY + d, width, centerY)
            assertTrue("scale $scale should not exceed 1", scale <= 1f)
            assertTrue("scale $scale should not drop below the floor", scale >= MIN_CHORD_SCALE)
            assertTrue("scale should not grow as distance grows", scale <= previous + 0.0001f)
            previous = scale
        }
    }

    @Test
    fun `unknown viewport width disables scaling`() {
        // CompositionLocal 默认值 0f 表示「还没人下发宽度」，此时不能缩放。
        assertEquals(1f, chordScaleForPosition(centerY + 400f, 0f, centerY), 0f)
        assertEquals(1f, chordScaleForPosition(centerY + 400f, -10f, centerY), 0f)
    }

    @Test
    fun `list offset is included when the screen centre belongs to the root`() {
        // A list can be laid out 200px below its root; its item offsets remain
        // list-local, so that origin has to be included before scaling.
        val itemCenterInRoot = chordItemCenterY(
            itemOffset = 440,
            itemSize = 120,
            listTopInViewport = 200f,
        )

        assertEquals(700f, itemCenterInRoot, 0f)
        assertEquals(1f, chordScaleForPosition(itemCenterInRoot, width, 700f), 0f)
        assertEquals(
            chordScaleForPosition(itemCenterInRoot - 180f, width, itemCenterInRoot),
            chordScaleForPosition(itemCenterInRoot + 180f, width, itemCenterInRoot),
            0.0001f,
        )
    }

    @Test
    fun `before content padding is converted from lazy viewport coordinates`() {
        // With 950px of before-content padding, LazyList reports the first
        // item's offset as 0 while the visible viewport starts at -950.
        // The row is therefore centred at 1000px on screen, not 50px.
        val itemCenterInRoot = chordItemCenterY(
            itemOffset = 0,
            itemSize = 100,
            viewportStartOffset = -950,
        )

        assertEquals(1000f, itemCenterInRoot, 0f)
        assertEquals(1f, chordScaleForPosition(itemCenterInRoot, 1000f, 1000f), 0f)
    }

    @Test
    fun `chord for an item spans the readable range across the viewport`() {
        // 视口顶端（元素中心贴 0）→ 夹到下限；四分位处 → 圆的 86.6% 弦宽。
        assertEquals(MIN_CHORD_SCALE, chordScaleForPosition(0f, width, centerY), 0f)
        val quarter = 2f * sqrt(500f * 500f - 250f * 250f) / width
        assertEquals(quarter, chordScaleForPosition(250f, width, centerY), 0.0001f)
        assertEquals(quarter, chordScaleForPosition(750f, width, centerY), 0.0001f)
    }

    @Test
    fun `round scroll padding centres the first card below a fixed toolbar`() {
        val padding = roundScrollablePadding(
            viewportHeight = 1_000f,
            contentTop = 240f,
            itemHeight = 160f,
            minimumPadding = 20f,
        )

        assertEquals(180f, padding.top, 0f)
        assertEquals(420f, padding.bottom, 0f)
    }

    @Test
    fun `round scroll padding never becomes negative`() {
        val padding = roundScrollablePadding(
            viewportHeight = 400f,
            contentTop = 260f,
            itemHeight = 160f,
            minimumPadding = 12f,
        )

        assertEquals(12f, padding.top, 0f)
        assertEquals(120f, padding.bottom, 0f)
    }

    @Test
    fun `tall market cards can explicitly opt into chord scaling`() {
        val marketCard = chordScaleForItemGeometry(
            itemCenterY = 750f,
            itemHeight = 600f,
            viewportWidth = width,
            screenCenterY = centerY,
            allowTallItem = true,
        )

        assertTrue(marketCard < 1f)
        assertEquals(chordScaleForPosition(750f, width, centerY), marketCard, 0f)
    }

    /**
     * 高项不再**突然**变成平铺。
     *
     * 这是老师报的「调大整体缩放后，有些选项没有鱼眼而是平铺、有些正常」的判据：
     * 以前 `itemHeight > radius` 一律返回 1f，而全局缩放放大 density 会让一部分行
     * 刚好越过这条线 —— 越线的平铺、没越线的照常缩，同一页混两种观感。
     */
    @Test
    fun `crossing the tall-item threshold no longer snaps to no scaling`() {
        val radius = width / 2f
        fun scaleAt(height: Float) = chordScaleForItemGeometry(
            itemCenterY = centerY + 300f,
            itemHeight = height,
            viewportWidth = width,
            screenCenterY = centerY,
        )

        // 半径以内：与纯位置版逐值相同（改动前的行为，不能变）。
        val short = scaleAt(radius - 1f)
        assertEquals(chordScaleForPosition(centerY + 300f, width, centerY), short, 0f)

        // 跨过阈值只允许极小的变化 —— 曾经是 0.8 → 1.0 的跳变。
        val justOver = scaleAt(radius + 1f)
        assertTrue(
            "crossing the threshold jumped from $short to $justOver",
            kotlin.math.abs(justOver - short) < 0.01f,
        )

        // 高度继续增长时单调回到 1f，且中途不越界。
        var previous = short
        for (multiple in 10..20) {
            val scale = scaleAt(radius * multiple / 10f)
            assertTrue("scale $scale should stay within [short, 1]", scale in short..1.0001f)
            assertTrue("scale should grow with height", scale >= previous - 0.0001f)
            previous = scale
        }
    }

    /**
     * 高到两倍半径以上的整段（编辑器那一大块按钮）仍然恒 1f。
     *
     * 老护栏的意图是对的 —— 一个 scale 代表不了那么高的一段，整块被夹到下限比
     * 不缩放更难看。这一档继续吃圆屏的静态内边距。
     */
    @Test
    fun `blocks taller than the diameter keep the old no-scaling behaviour`() {
        listOf(0f, 200f, 400f).forEach { offset ->
            assertEquals(
                1f,
                chordScaleForItemGeometry(
                    itemCenterY = centerY + offset,
                    itemHeight = width,
                    viewportWidth = width,
                    screenCenterY = centerY,
                ),
                0f,
            )
        }
    }
}
