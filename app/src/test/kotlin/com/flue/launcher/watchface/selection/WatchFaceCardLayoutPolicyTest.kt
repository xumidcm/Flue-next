package com.flue.launcher.watchface.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFaceCardLayoutPolicyTest {
    @Test fun `small round watch still has a preview and enough room for both actions`() {
        val layout = watchFaceCardLayout(240f, 240f, true, 2)
        assertTrue(layout.previewSideDp >= 60f)
        assertTrue(layout.previewSideDp + layout.reservedVerticalDp <= 240f)
    }
    @Test
    fun `tall phone grows preview beyond the obsolete fixed cap`() {
        val layout = watchFaceCardLayout(
            availableWidthDp = 424f,
            availableHeightDp = 924f,
            isRoundScreen = false,
            actionCount = 2,
        )

        assertTrue(layout.previewSideDp > 230f)
        assertTrue(layout.previewSideDp <= layout.availablePreviewHeightDp)
        assertTrue(layout.previewSideDp <= layout.availablePreviewWidthDp)
    }

    @Test
    fun `short and round screens reserve room for chrome and actions`() {
        val short = watchFaceCardLayout(320f, 420f, isRoundScreen = false, actionCount = 2)
        val round = watchFaceCardLayout(454f, 454f, isRoundScreen = true, actionCount = 2)

        assertTrue(short.previewSideDp + short.reservedVerticalDp <= 420f)
        assertTrue(round.previewSideDp + round.reservedVerticalDp <= 454f)
        assertTrue(round.previewSideDp < 454f * 0.7f)
    }

    @Test
    fun `image mask border and hit target share one transform`() {
        val transform = watchFaceCardVisualTransform(pageOffset = 0.5f, pressed = true)

        assertEquals(transform.scale, transform.imageScale, 0f)
        assertEquals(transform.scale, transform.maskScale, 0f)
        assertEquals(transform.scale, transform.borderScale, 0f)
        assertEquals(transform.scale, transform.hitTargetScale, 0f)
    }

    @Test
    fun `unbounded overlay constraints never leak infinite card metrics`() {
        val layout = watchFaceCardLayout(
            availableWidthDp = Float.POSITIVE_INFINITY,
            availableHeightDp = Float.POSITIVE_INFINITY,
            isRoundScreen = false,
            actionCount = 2,
        )

        assertTrue(layout.previewSideDp.isFinite())
        assertTrue(layout.availablePreviewWidthDp.isFinite())
        assertTrue(layout.availablePreviewHeightDp.isFinite())
        assertTrue(layout.reservedVerticalDp.isFinite())
    }

    @Test
    fun `monochrome back foreground chooses the higher contrast side`() {
        assertFalse(watchFaceCardUseDarkForeground(backgroundLuminance = 0f))
        assertFalse(watchFaceCardUseDarkForeground(backgroundLuminance = 0.10f))
        assertTrue(watchFaceCardUseDarkForeground(backgroundLuminance = 0.30f))
        assertTrue(watchFaceCardUseDarkForeground(backgroundLuminance = 1f))
        assertFalse(watchFaceCardUseDarkForeground(backgroundLuminance = Float.NaN))
    }

    @Test
    fun `page indicators keep every page when there are at most nine`() {
        assertEquals(
            listOf(0),
            watchFacePageIndicators(totalPages = 1, currentPage = 0).map { it.page },
        )
        val indicators = watchFacePageIndicators(totalPages = 9, currentPage = 4)

        assertEquals((0 until 9).toList(), indicators.map { it.page })
        assertTrue(indicators.all { it.alpha == 1f })
    }

    @Test
    fun `page indicators use a nine point window and fade distant neighbors`() {
        val indicators = watchFacePageIndicators(totalPages = 21, currentPage = 10)

        assertEquals((6..14).toList(), indicators.map { it.page })
        assertEquals(
            listOf(0f, 0.14f, 0.32f, 0.58f, 1f, 0.58f, 0.32f, 0.14f, 0f),
            indicators.map { it.alpha },
        )
    }

    @Test
    fun `page indicator window sticks to the start and end`() {
        assertEquals(
            (1..9).toList(),
            watchFacePageIndicators(totalPages = 10, currentPage = 5).map { it.page },
        )
        assertEquals(
            (0..8).toList(),
            watchFacePageIndicators(totalPages = 21, currentPage = 0).map { it.page },
        )
        assertEquals(
            (12..20).toList(),
            watchFacePageIndicators(totalPages = 21, currentPage = 20).map { it.page },
        )
    }
}
