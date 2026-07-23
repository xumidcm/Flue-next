package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDenseGridGeometryTest {
    @Test
    fun legacyMaterialFormulaKeepsPaddingSpacingAspectAndSafeInset() {
        val geometry = NativeDenseGridGeometry.create(
            itemCount = 12,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            columns = 5,
            horizontalPaddingPx = 8f,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemHeightPx = 48f,
        )

        assertEquals(57.6f, geometry.itemWidthPx, 0.001f)
        assertEquals(57.6f / 0.92f, geometry.itemHeightPx, 0.001f)
        assertEquals(36.8f, geometry.placements[0].centerX, 0.001f)
        assertEquals(283.2f, geometry.placements[4].centerX, 0.001f)
        assertEquals(8f, geometry.placements[5].centerX - geometry.itemWidthPx / 2f, 0.001f)

        val safe = NativeDenseGridGeometry.create(
            itemCount = 4,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 64,
            columns = 4,
            horizontalPaddingPx = 8f,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemHeightPx = 48f,
        )
        assertEquals(72f, safe.placements.first().centerX - safe.itemWidthPx / 2f, 0.001f)
        assertEquals(312f, safe.placements.last().centerX + safe.itemWidthPx / 2f, 0.001f)
    }

    @Test
    fun firstAndLastRowsCanBothRestAtViewportCenterWithBoundedVisibility() {
        val geometry = NativeDenseGridGeometry.create(
            itemCount = 12,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            columns = 5,
            horizontalPaddingPx = 8f,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemHeightPx = 48f,
        )

        assertEquals(
            160f,
            geometry.screenCenterY(0, geometry.maximumScrollOffsetPx),
            0.001f,
        )
        assertEquals(
            160f,
            geometry.screenCenterY(11, geometry.minimumScrollOffsetPx),
            0.001f,
        )
        assertEquals(2, geometry.placements[10].row)
        assertEquals(0, geometry.placements[10].column)
        assertEquals(1, geometry.placements[11].column)

        val visible = IntArray(2)
        geometry.visiblePositionBounds(
            scrollOffsetPx = geometry.maximumScrollOffsetPx,
            extraLayoutSpacePx = geometry.itemHeightPx,
            output = visible,
        )
        assertEquals(0, visible[0])
        assertTrue(visible[1] in 4..11)
    }

    @Test
    fun oversizedLegacyCellFallsBackToEightPixelVerticalContentPadding() {
        val geometry = NativeDenseGridGeometry.create(
            itemCount = 1,
            viewportWidthPx = 100,
            viewportHeightPx = 80,
            leftSafeInsetPx = 0,
            columns = 1,
            horizontalPaddingPx = 8f,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.5f,
            minimumItemWidthPx = 48f,
            minimumItemHeightPx = 48f,
            minimumVerticalPaddingPx = 8f,
        )

        assertEquals(8f, geometry.verticalEdgePaddingPx, 0f)
        assertEquals(92f, geometry.screenCenterY(0, geometry.maximumScrollOffsetPx), 0.001f)
        assertEquals(-12f, geometry.screenCenterY(0, geometry.minimumScrollOffsetPx), 0.001f)
    }

    @Test
    fun narrowRoundViewportReducesColumnsBeforeShrinkingBelowMinimumWidth() {
        val viewportWidth = 192
        val safeLeftInset = 24
        val horizontalPadding = 8f
        val minimumItemWidth = 48f
        val geometry = NativeDenseGridGeometry.create(
            itemCount = 7,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = 192,
            leftSafeInsetPx = safeLeftInset,
            columns = 5,
            horizontalPaddingPx = horizontalPadding,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemWidthPx = minimumItemWidth,
            minimumItemHeightPx = 48f,
        )

        assertEquals(minimumItemWidth, geometry.itemWidthPx, 0.001f)
        assertEquals(3, geometry.placements.count { it.row == 0 })
        assertEquals(1, geometry.placements[3].row)
        assertEquals(0, geometry.placements[3].column)
        geometry.placements.forEach { placement ->
            assertTrue(
                placement.centerX - geometry.itemWidthPx / 2f >=
                    safeLeftInset + horizontalPadding,
            )
            assertTrue(
                placement.centerX + geometry.itemWidthPx / 2f <=
                    viewportWidth - horizontalPadding,
            )
        }
    }

    @Test
    fun extremelyNarrowViewportShrinksOneColumnOnlyAsLastResortWithoutOverflow() {
        val viewportWidth = 40
        val safeLeftInset = 20
        val horizontalPadding = 8f
        val geometry = NativeDenseGridGeometry.create(
            itemCount = 4,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = 80,
            leftSafeInsetPx = safeLeftInset,
            columns = 5,
            horizontalPaddingPx = horizontalPadding,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemWidthPx = 48f,
            minimumItemHeightPx = 48f,
        )

        assertEquals(4f, geometry.itemWidthPx, 0.001f)
        assertEquals(48f, geometry.itemHeightPx, 0.001f)
        assertEquals(listOf(0, 1, 2, 3), geometry.placements.map { it.row })
        assertTrue(geometry.placements.all { it.column == 0 })
        geometry.placements.forEach { placement ->
            assertEquals(
                safeLeftInset + horizontalPadding,
                placement.centerX - geometry.itemWidthPx / 2f,
                0.001f,
            )
            assertEquals(
                viewportWidth - horizontalPadding,
                placement.centerX + geometry.itemWidthPx / 2f,
                0.001f,
            )
        }
    }

    @Test
    fun incompleteLastRowStaysLeftAlignedAndEmptyGeometryHasNoVisibleRange() {
        val geometry = NativeDenseGridGeometry.create(
            itemCount = 6,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            columns = 5,
            horizontalPaddingPx = 8f,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemHeightPx = 48f,
        )
        assertEquals(geometry.placements[0].centerX, geometry.placements[5].centerX, 0f)

        val empty = NativeDenseGridGeometry.create(
            itemCount = 0,
            viewportWidthPx = 320,
            viewportHeightPx = 320,
            leftSafeInsetPx = 0,
            columns = 5,
            horizontalPaddingPx = 8f,
            itemSpacingPx = 4f,
            itemAspectRatio = 0.92f,
            minimumItemHeightPx = 48f,
        )
        val visible = IntArray(2)
        empty.visiblePositionBounds(0f, 0f, visible)
        assertEquals(-1, visible[0])
        assertEquals(-1, visible[1])
    }
}
