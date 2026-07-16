package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHoneycombGeometryTest {
    @Test
    fun `alternating narrow and wide rows stay centered in the safe content width`() {
        val geometry = NativeHoneycombGeometry.create(
            itemCount = 9,
            viewportWidthPx = 400,
            viewportHeightPx = 400,
            leftSafeInsetPx = 40,
            narrowColumns = 3,
            iconSizePx = 80f,
            cellSizePx = 80f,
        )

        assertEquals(9, geometry.placements.size)
        assertEquals(listOf(140f, 220f, 300f), geometry.placements.take(3).map { it.centerX })
        assertEquals(
            listOf(100f, 180f, 260f, 340f),
            geometry.placements.drop(3).take(4).map { it.centerX },
        )
        assertEquals(0, geometry.placements[0].row)
        assertEquals(1, geometry.placements[3].row)
    }

    @Test
    fun `scroll bounds center the first and final rows without blank overscroll`() {
        val geometry = NativeHoneycombGeometry.create(
            itemCount = 20,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            narrowColumns = 3,
            iconSizePx = 64f,
        )

        assertEquals(160f, geometry.screenCenterY(0, geometry.maximumScrollOffsetPx), 0.001f)
        assertEquals(
            160f,
            geometry.screenCenterY(geometry.placements.lastIndex, geometry.minimumScrollOffsetPx),
            0.001f,
        )
        assertTrue(geometry.minimumScrollOffsetPx < geometry.maximumScrollOffsetPx)
    }

    @Test
    fun `visible query keeps a bounded buffer around viewport`() {
        val geometry = NativeHoneycombGeometry.create(
            itemCount = 100,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            narrowColumns = 3,
            iconSizePx = 64f,
        )

        val visible = geometry.visiblePositions(
            scrollOffsetPx = geometry.maximumScrollOffsetPx,
            extraLayoutSpacePx = 64f,
        )

        assertTrue(visible.isNotEmpty())
        assertEquals(0, visible.first())
        assertTrue(visible.size < 30)
    }
}
