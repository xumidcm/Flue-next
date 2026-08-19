package com.flue.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchEditorToolbarGeometryTest {
    @Test
    fun `round editor stacks toolbar when inline controls cannot fit`() {
        assertEquals(
            WatchEditorToolbarLayout.STACKED,
            watchEditorToolbarLayout(isRound = true, maxWidthDp = 240f),
        )
        assertEquals(
            WatchEditorToolbarLayout.INLINE,
            watchEditorToolbarLayout(isRound = true, maxWidthDp = 360f),
        )
        assertEquals(
            WatchEditorToolbarLayout.INLINE,
            watchEditorToolbarLayout(isRound = false, maxWidthDp = 240f),
        )
    }

    @Test
    fun `toolbar viewport never exceeds its editor container`() {
        val roundWidth = watchEditorToolbarViewportWidth(isRound = true, maxWidthDp = 240f)
        val phoneWidth = watchEditorToolbarViewportWidth(isRound = false, maxWidthDp = 360f)

        assertTrue(roundWidth in 0f..240f)
        assertTrue(phoneWidth in 0f..360f)
    }
}
