package com.flue.launcher.watchface.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorImageCropModelsTest {
    @Test
    fun sampleSizeStaysWithinBudgetAsPowersOfTwo() {
        assertEquals(1, watchEditorCropSampleSize(800, 600, 1024))
        assertEquals(1, watchEditorCropSampleSize(1024, 1024, 1024))
        assertEquals(2, watchEditorCropSampleSize(2048, 1024, 1024))
        assertEquals(4, watchEditorCropSampleSize(4096, 4096, 1024))
        assertEquals(4, watchEditorCropSampleSize(2500, 1000, 1024))
        assertEquals(1, watchEditorCropSampleSize(0, 100, 1024))
    }

    @Test
    fun fitImageRectLetterboxesFitCenter() {
        val fit = watchEditorFitImageRect(200f, 100f, 100f, 100f)
        assertEquals(0f, fit.left, 1e-3f)
        assertEquals(25f, fit.top, 1e-3f)
        assertEquals(100f, fit.right, 1e-3f)
        assertEquals(75f, fit.bottom, 1e-3f)
    }

    @Test
    fun cropMapsBackToSourcePixels() {
        val fit = watchEditorFitImageRect(200f, 100f, 100f, 100f)
        val source = mapWatchEditorCropToSource(
            crop = WatchEditorCropRect(0f, 25f, 50f, 75f),
            fit = fit,
            sourceWidth = 200,
            sourceHeight = 100,
        )
        assertEquals(0, source.left)
        assertEquals(0, source.top)
        assertEquals(100, source.right)
        assertEquals(100, source.bottom)
    }

    @Test
    fun cropMappingNeverProducesAnEmptyRect() {
        val fit = WatchEditorCropRect(0f, 0f, 100f, 100f)
        val degenerate = mapWatchEditorCropToSource(
            crop = WatchEditorCropRect(99.9f, 99.9f, 100f, 100f),
            fit = fit,
            sourceWidth = 10,
            sourceHeight = 10,
        )
        assertTrue(degenerate.width >= 1)
        assertTrue(degenerate.height >= 1)
        assertTrue(degenerate.right <= 10 && degenerate.bottom <= 10)
    }

    @Test
    fun aspectPresetsResolveRatios() {
        assertNull(watchEditorCropAspectRatio(WatchEditorCropAspect.FREE, 400, 400))
        assertEquals(
            1f,
            watchEditorCropAspectRatio(WatchEditorCropAspect.SQUARE, 400, 300)!!,
            1e-4f,
        )
        assertEquals(
            0.75f,
            watchEditorCropAspectRatio(WatchEditorCropAspect.SCREEN, 300, 400)!!,
            1e-4f,
        )
        assertEquals(
            1f,
            watchEditorCropAspectRatio(WatchEditorCropAspect.SCREEN, 0, 0)!!,
            1e-4f,
        )
    }

    @Test
    fun constrainLocksAspectInsideBounds() {
        val bounds = WatchEditorCropRect(0f, 0f, 100f, 100f)
        val constrained = constrainWatchEditorCropRect(
            candidate = WatchEditorCropRect(10f, 30f, 90f, 70f),
            bounds = bounds,
            minSize = 10f,
            ratio = 1f,
        )
        assertEquals(constrained.width, constrained.height, 1e-3f)
        assertTrue(constrained.left >= bounds.left - 1e-3f)
        assertTrue(constrained.right <= bounds.right + 1e-3f)
        assertTrue(constrained.top >= bounds.top - 1e-3f)
        assertTrue(constrained.bottom <= bounds.bottom + 1e-3f)
    }

    @Test
    fun constrainToleratesFloatRoundingAtAnExactBoundsEdge() {
        val bounds = WatchEditorCropRect(
            left = 97.03128f,
            top = 12.12501f,
            right = 194.0625f,
            bottom = 109.15625f,
        )
        // These independently calculated dimensions reproduce the video crop
        // crash where the rounded clamp maximum was a few ULPs below minimum.
        val candidate = WatchEditorCropRect(
            left = bounds.left,
            top = bounds.top,
            right = bounds.left + 97.03125f,
            bottom = bounds.top + 97.03125f,
        )

        val constrained = constrainWatchEditorCropRect(
            candidate = candidate,
            bounds = bounds,
            minSize = 48f,
            ratio = 1f,
        )

        assertTrue(constrained.left >= bounds.left)
        assertTrue(constrained.top >= bounds.top)
        assertTrue(constrained.right <= bounds.right)
        assertTrue(constrained.bottom <= bounds.bottom)
        assertTrue(constrained.width > 0f)
        assertTrue(constrained.height > 0f)
    }

    @Test
    fun moveToleratesCropWidthRoundedSlightlyPastBounds() {
        val bounds = WatchEditorCropRect(97.03128f, 0f, 194.0625f, 100f)
        val moved = moveWatchEditorCropRect(
            rect = WatchEditorCropRect(bounds.left, 0f, bounds.left + 97.03125f, 100f),
            deltaX = 4f,
            deltaY = 0f,
            bounds = bounds,
        )

        assertTrue(moved.left >= bounds.left)
        assertTrue(moved.right <= bounds.right)
        assertTrue(moved.top >= bounds.top)
        assertTrue(moved.bottom <= bounds.bottom)
    }

    @Test
    fun moveClampsToBounds() {
        val bounds = WatchEditorCropRect(0f, 0f, 100f, 100f)
        val rect = WatchEditorCropRect(10f, 10f, 50f, 50f)
        val moved = moveWatchEditorCropRect(rect, 500f, -500f, bounds)
        assertEquals(60f, moved.left, 1e-3f)
        assertEquals(100f, moved.right, 1e-3f)
        assertEquals(0f, moved.top, 1e-3f)
        assertEquals(40f, moved.bottom, 1e-3f)
    }

    @Test
    fun cornerResizeKeepsOppositeCornerAnchored() {
        val bounds = WatchEditorCropRect(0f, 0f, 100f, 100f)
        val resized = resizeWatchEditorCropRect(
            rect = WatchEditorCropRect(10f, 10f, 50f, 50f),
            handle = WatchEditorCropHandle.BOTTOM_RIGHT,
            deltaX = 10f,
            deltaY = 10f,
            bounds = bounds,
            minSize = 10f,
            ratio = null,
        )
        assertEquals(10f, resized.left, 1e-3f)
        assertEquals(10f, resized.top, 1e-3f)
        assertEquals(60f, resized.right, 1e-3f)
        assertEquals(60f, resized.bottom, 1e-3f)
    }

    @Test
    fun edgeResizeChangesSizeAndKeepsOppositeEdgeAnchored() {
        val bounds = WatchEditorCropRect(0f, 0f, 100f, 100f)
        val resized = resizeWatchEditorCropRect(
            rect = WatchEditorCropRect(20f, 20f, 80f, 80f),
            handle = WatchEditorCropHandle.LEFT,
            deltaX = 10f,
            deltaY = 0f,
            bounds = bounds,
            minSize = 10f,
            ratio = null,
        )

        assertEquals(30f, resized.left, 1e-3f)
        assertEquals(80f, resized.right, 1e-3f)
        assertEquals(20f, resized.top, 1e-3f)
        assertEquals(80f, resized.bottom, 1e-3f)
    }

    @Test
    fun aspectLockedEdgeResizeStaysCentredOnPerpendicularAxis() {
        val bounds = WatchEditorCropRect(0f, 0f, 120f, 120f)
        val resized = resizeWatchEditorCropRect(
            rect = WatchEditorCropRect(30f, 30f, 90f, 90f),
            handle = WatchEditorCropHandle.RIGHT,
            deltaX = 20f,
            deltaY = 0f,
            bounds = bounds,
            minSize = 10f,
            ratio = 1f,
        )

        assertEquals(30f, resized.left, 1e-3f)
        assertEquals(110f, resized.right, 1e-3f)
        assertEquals(60f, resized.centerY, 1e-3f)
        assertEquals(resized.width, resized.height, 1e-3f)
    }

    @Test
    fun resizeEnforcesMinimumSize() {
        val bounds = WatchEditorCropRect(0f, 0f, 100f, 100f)
        val resized = resizeWatchEditorCropRect(
            rect = WatchEditorCropRect(10f, 10f, 50f, 50f),
            handle = WatchEditorCropHandle.BOTTOM_RIGHT,
            deltaX = -100f,
            deltaY = -100f,
            bounds = bounds,
            minSize = 16f,
            ratio = null,
        )
        assertTrue(resized.width >= 16f - 1e-3f)
        assertTrue(resized.height >= 16f - 1e-3f)
    }

    @Test
    fun scaleGrowsAroundCenterAndStaysBounded() {
        val bounds = WatchEditorCropRect(0f, 0f, 100f, 100f)
        val rect = WatchEditorCropRect(40f, 40f, 60f, 60f)
        val scaled = scaleWatchEditorCropRect(rect, 2f, bounds, 10f, null)
        assertEquals(50f, scaled.centerX, 1e-3f)
        assertEquals(50f, scaled.centerY, 1e-3f)
        assertEquals(40f, scaled.width, 1e-3f)

        val overgrown = scaleWatchEditorCropRect(rect, 100f, bounds, 10f, null)
        assertTrue(overgrown.width <= bounds.width + 1e-3f)
        assertTrue(overgrown.height <= bounds.height + 1e-3f)

        val collapsed = scaleWatchEditorCropRect(rect, 0.01f, bounds, 10f, null)
        assertTrue(collapsed.width >= 10f - 1e-3f)
    }

    @Test
    fun handleResolutionUsesTouchRadius() {
        val rect = WatchEditorCropRect(10f, 10f, 90f, 90f)
        assertEquals(
            WatchEditorCropHandle.TOP_LEFT,
            resolveWatchEditorCropHandle(rect, 12f, 8f, 6f),
        )
        assertEquals(
            WatchEditorCropHandle.BOTTOM_RIGHT,
            resolveWatchEditorCropHandle(rect, 88f, 92f, 6f),
        )
        assertEquals(
            WatchEditorCropHandle.TOP,
            resolveWatchEditorCropHandle(rect, 50f, 12f, 6f),
        )
        assertEquals(
            WatchEditorCropHandle.LEFT,
            resolveWatchEditorCropHandle(rect, 8f, 50f, 6f),
        )
        assertNull(resolveWatchEditorCropHandle(rect, 50f, 50f, 6f))
    }
}
