package com.flue.launcher.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListEntryTransitionTest {

    @Test
    fun `global entry progress drives every visual channel`() {
        val visuals = appListEntryVisuals(0.42f)

        assertEquals(0.42f, visuals.backgroundProgress)
        assertEquals(0.42f, visuals.backgroundFillProgress)
        assertEquals(0.42f, visuals.iconProgress)
        assertEquals(0.42f, visuals.surfaceProgress)
        assertEquals(0.42f, visuals.edgeProgress)
    }

    @Test
    fun `entry progress is clamped before it reaches visual channels`() {
        assertVisualProgress(expected = 0f, actual = appListEntryVisuals(-0.5f))
        assertVisualProgress(expected = 1f, actual = appListEntryVisuals(1.5f))
    }

    private fun assertVisualProgress(expected: Float, actual: AppListEntryVisuals) {
        assertEquals(expected, actual.backgroundProgress)
        assertEquals(expected, actual.backgroundFillProgress)
        assertEquals(expected, actual.iconProgress)
        assertEquals(expected, actual.surfaceProgress)
        assertEquals(expected, actual.edgeProgress)
    }
}
