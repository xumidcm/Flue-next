package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * B4 —— 与上游 `watchmaker-definition` 对齐的刻度 / 环端帽契约。
 *
 * 这几个都是渲染器里原本内联的判断，抽成纯函数就是为了能在这里钉住：它们决定的是
 * 「表盘长什么样」，回归了肉眼要在真机上才看得出来。
 */
class WatchmakerMarkerContractsTest {

    // ── ring 端帽（WM_FORMAT_1.0.md §7.3）────────────────────────────────────

    @Test
    fun `an omitted ring radius keeps the classic semicircular cap`() {
        // 这条最要紧：没写 radius 的存量表盘一帧都不能变。
        val cap = watchRingCap(radiusValue = null, endStyle = null, stroke = 20f)
        assertEquals(10f, cap.rounding, 0f)
        assertFalse(cap.square)
    }

    @Test
    fun `ring radius zero means a flat cut`() {
        val cap = watchRingCap(radiusValue = 0f, endStyle = null, stroke = 20f)
        assertEquals(0f, cap.rounding, 0f)
    }

    @Test
    fun `ring radius is clamped to half the ring thickness`() {
        assertEquals(10f, watchRingCap(999f, null, 20f).rounding, 0f)
        assertEquals(0f, watchRingCap(-5f, null, 20f).rounding, 0f)
    }

    @Test
    fun `an explicit ring radius wins over the end_style extension`() {
        // end_style 是 Flue 自己的扩展，clox 的 ring 不读它；规范字段必须优先。
        val cap = watchRingCap(radiusValue = 3f, endStyle = "square", stroke = 20f)
        assertEquals(3f, cap.rounding, 0f)
        assertFalse(cap.square)
    }

    @Test
    fun `end_style still works when radius is absent`() {
        assertEquals(0f, watchRingCap(null, "butt", 20f).rounding, 0f)
        assertTrue(watchRingCap(null, "square", 20f).square)
    }

    // ── markers 字形（WM_FORMAT_2.0.md §12）─────────────────────────────────

    @Test
    fun `marker glyph comes from shape first`() {
        assertEquals(WatchMarkerGlyph.CIRCLE, watchMarkerGlyph("Circle", null))
        assertEquals(WatchMarkerGlyph.TRIANGLE, watchMarkerGlyph("Triangle", null))
        assertEquals(WatchMarkerGlyph.SQUARE, watchMarkerGlyph("Square", null))
    }

    @Test
    fun `markers_shape only fills in when shape is absent`() {
        // shape 在场时 markers_shape 不许改字形：clox 的渲染器根本不读后者。
        assertEquals(WatchMarkerGlyph.SQUARE, watchMarkerGlyph("Square", "Circle"))
        assertEquals(WatchMarkerGlyph.CIRCLE, watchMarkerGlyph(null, "Circle"))
        assertEquals(WatchMarkerGlyph.SQUARE, watchMarkerGlyph("  ", "Rounded"))
    }

    @Test
    fun `an unknown shape degrades to a square tick`() {
        assertEquals(WatchMarkerGlyph.SQUARE, watchMarkerGlyph("Hexagon", null))
        assertEquals(WatchMarkerGlyph.SQUARE, watchMarkerGlyph(null, null))
    }

    // ── markers_hm 档位 ─────────────────────────────────────────────────────

    @Test
    fun `hour minute presets follow the clox size table`() {
        assertEquals(0f, watchHourMinuteMarkerPreset("None").step, 0f)
        assertEquals(1f, watchHourMinuteMarkerPreset("Tiny").step, 0f)
        assertEquals(2f, watchHourMinuteMarkerPreset("Small").step, 0f)
        assertEquals(3f, watchHourMinuteMarkerPreset("Medium").step, 0f)
        assertEquals(4f, watchHourMinuteMarkerPreset("Large").step, 0f)
    }

    @Test
    fun `the 2 point 0 presets clox does not know are best-effort but never blank`() {
        assertEquals(5f, watchHourMinuteMarkerPreset("XLarge").step, 0f)
        val circle = watchHourMinuteMarkerPreset("Circle")
        assertEquals(3f, circle.step, 0f)
        assertEquals(WatchMarkerGlyph.CIRCLE, circle.glyph)
        val triangle = watchHourMinuteMarkerPreset("Triangle")
        assertEquals(WatchMarkerGlyph.TRIANGLE, triangle.glyph)
    }

    @Test
    fun `an absent or unknown preset falls back to medium rather than nothing`() {
        // 认不出来就不画，会把整层刻度吞掉 —— 那比画得略有出入糟得多。
        assertEquals(3f, watchHourMinuteMarkerPreset(null).step, 0f)
        assertEquals(3f, watchHourMinuteMarkerPreset("Gigantic").step, 0f)
        assertEquals(3f, watchHourMinuteMarkerPreset("").step, 0f)
    }

    // ── squarify ────────────────────────────────────────────────────────────

    @Test
    fun `squarify zero leaves the circle alone`() {
        assertEquals(100f, squarifiedRadius(100f, 37f, 0f), 0f)
        assertEquals(100f, squarifiedRadius(100f, 37f, -10f), 0f)
    }

    @Test
    fun `squarify does not move the four axis directions`() {
        // 正上 / 正右等方向上 max(|cos|,|sin|) = 1，公式化简成 r —— 方形的边中点。
        for (degrees in listOf(0f, 90f, 180f, 270f, -90f)) {
            assertEquals(100f, squarifiedRadius(100f, degrees, 100f), 0.01f)
        }
    }

    @Test
    fun `full squarify pushes the diagonals out to the square corner`() {
        // 45° 上 max(|cos|,|sin|) = 1/√2，所以 r / (1/√2) = r·√2 —— 正方形的角。
        assertEquals(100f * sqrt(2f), squarifiedRadius(100f, 45f, 100f), 0.01f)
    }

    @Test
    fun `partial squarify lands between the circle and the square`() {
        val half = squarifiedRadius(100f, 45f, 50f)
        assertTrue("$half", half > 100f && half < 100f * sqrt(2f))
    }
}
