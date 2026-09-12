package com.flue.launcher.watchface.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画布尺寸 → 设计空间的语义锁：基准边恒 512，只按声明宽高比拉长另一边。
 * 方形声明必须原地不动（存量表盘零回归），非方声明才拉长长边。
 */
class WatchCanvasExtentTest {

    @Test
    fun `square declarations keep the native 512 design space`() {
        listOf(512 to 512, 466 to 466, 454 to 454, 410 to 410, 360 to 360, 240 to 240)
            .forEach { (width, height) ->
                val size = WatchCanvasSize(width, height)
                assertEquals("${width}x$height width", 512f, size.designExtentWidth, 0.001f)
                assertEquals("${width}x$height height", 512f, size.designExtentHeight, 0.001f)
            }
    }

    @Test
    fun `missing root attributes fall back to the native square canvas`() {
        val size = WatchCanvasSize.fromRootAttributes(mapOf("name" to "Legacy", "shape" to "R"))
        assertEquals(512f, size.designExtentWidth, 0.001f)
        assertEquals(512f, size.designExtentHeight, 0.001f)
    }

    @Test
    fun `portrait declarations stretch height and keep width at 512`() {
        val size = WatchCanvasSize(320, 385)
        assertEquals(512f, size.designExtentWidth, 0.001f)
        // 512 / (320/385) = 616.0
        assertEquals(616f, size.designExtentHeight, 0.5f)
    }

    @Test
    fun `the 368x448 preset matches the agreed extents`() {
        val size = WatchCanvasSize(368, 448)
        assertEquals(512f, size.designExtentWidth, 0.001f)
        assertEquals(623f, size.designExtentHeight, 0.5f)
    }

    @Test
    fun `landscape declarations stretch width and keep height at 512`() {
        val size = WatchCanvasSize(448, 368)
        assertEquals(623f, size.designExtentWidth, 0.5f)
        assertEquals(512f, size.designExtentHeight, 0.001f)
    }

    @Test
    fun `pressing the viewport centre maps to the design origin on both axes`() {
        val size = WatchCanvasSize(320, 385)
        // 视口按声明比例给：600 宽 → 600 / 0.8312 = 721.9 高
        val viewWidth = 600f
        val viewHeight = viewWidth / size.aspectRatio
        val mapping = WatchEditorCanvasMapping(
            viewWidthPx = viewWidth,
            viewHeightPx = viewHeight,
            designWidth = size.designExtentWidth,
            designHeight = size.designExtentHeight,
        )
        assertEquals(0f, mapping.designX(viewWidth / 2f), 0.01f)
        assertEquals(0f, mapping.designY(viewHeight / 2f), 0.01f)
    }

    @Test
    fun `centre still maps to the origin while the container ratio mismatches`() {
        val size = WatchCanvasSize(320, 385)
        // 分屏动画中途：容器比例和声明比例不相等，渲染器会 letterbox。
        val mapping = WatchEditorCanvasMapping(
            viewWidthPx = 600f,
            viewHeightPx = 400f,
            designWidth = size.designExtentWidth,
            designHeight = size.designExtentHeight,
        )
        assertEquals(0f, mapping.designX(300f), 0.01f)
        assertEquals(0f, mapping.designY(200f), 0.01f)
    }

    @Test
    fun `square mapping agrees with the legacy single-axis helpers`() {
        val viewSize = 480f
        val mapping = WatchEditorCanvasMapping(viewWidthPx = viewSize, viewHeightPx = viewSize)
        listOf(0f, 37f, 240f, 480f).forEach { px ->
            assertEquals(
                "designX at $px",
                watchEditorDesignFromViewPx(px, viewSize),
                mapping.designX(px),
                0.01f,
            )
        }
        listOf(-256f, -80f, 0f, 128f, 256f).forEach { design ->
            assertEquals(
                "viewX at $design",
                watchEditorViewPxFromDesign(design, viewSize),
                mapping.viewX(design),
                0.01f,
            )
        }
        assertEquals(
            watchEditorDesignLengthFromViewPx(24f, viewSize),
            mapping.designLength(24f),
            0.01f,
        )
    }

    @Test
    fun `design to view round trips through the mapping`() {
        val mapping = WatchEditorCanvasMapping(
            viewWidthPx = 512f,
            viewHeightPx = 616f,
            designWidth = 512f,
            designHeight = 616f,
        )
        listOf(-200f, -13f, 0f, 91f, 240f).forEach { design ->
            assertEquals(design, mapping.designX(mapping.viewX(design)), 0.01f)
            assertEquals(design, mapping.designY(mapping.viewY(design)), 0.01f)
        }
    }

    @Test
    fun `degenerate viewports fall back to a unit scale instead of dividing by zero`() {
        val mapping = WatchEditorCanvasMapping(viewWidthPx = 0f, viewHeightPx = 0f)
        assertEquals(1f, mapping.scale, 0.001f)
        assertEquals(0f, mapping.designX(0f), 0.001f)
    }

    @Test
    fun `fill preview center crops a square design into a portrait viewport`() {
        val mapping = WatchEditorCanvasMapping(
            viewWidthPx = 320f,
            viewHeightPx = 400f,
            designWidth = 512f,
            designHeight = 512f,
            fill = true,
        )

        assertEquals(400f / 512f, mapping.scale, 0.0001f)
        assertEquals(-40f, mapping.viewX(-256f), 0.01f)
        assertEquals(360f, mapping.viewX(256f), 0.01f)
        assertEquals(0f, mapping.viewY(-256f), 0.01f)
        assertEquals(400f, mapping.viewY(256f), 0.01f)
    }
}
