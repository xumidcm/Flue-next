package com.flue.launcher.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FisheyeTest {
    @Test
    fun `settings fisheye starts below sixty two percent of the viewport`() {
        assertEquals(1f, bottomFisheyeScaleForPosition(619f, 1000f), 0f)
        assertEquals(1f, bottomFisheyeScaleForPosition(620f, 1000f), 0f)
        assertTrue(bottomFisheyeScaleForPosition(700f, 1000f) < 1f)
    }

    @Test
    fun `settings fisheye reaches but never exceeds its minimum scale`() {
        assertEquals(0.86f, bottomFisheyeScaleForPosition(1000f, 1000f), 0.0001f)
        assertEquals(0.86f, bottomFisheyeScaleForPosition(1400f, 1000f), 0.0001f)
    }
}
