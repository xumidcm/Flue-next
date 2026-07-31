package com.flue.launcher.ui.controlcenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlToggleRowLayoutTest {
    @Test
    fun `wide viewport keeps preferred spacing`() {
        val layout = controlToggleRowLayout(
            availableWidthPx = 400f,
            buttonWidthPx = 58f,
            preferredSpacingPx = 14f,
        )

        assertFalse(layout.scrollable)
        assertEquals(14f, layout.spacingPx, 0f)
    }

    @Test
    fun `narrow viewport shrinks only the gaps`() {
        val layout = controlToggleRowLayout(
            availableWidthPx = 250f,
            buttonWidthPx = 58f,
            preferredSpacingPx = 14f,
        )

        assertFalse(layout.scrollable)
        assertEquals(6f, layout.spacingPx, 0f)
    }

    @Test
    fun `less than four pixels per gap switches to scrolling`() {
        val layout = controlToggleRowLayout(
            availableWidthPx = 243f,
            buttonWidthPx = 58f,
            preferredSpacingPx = 14f,
        )

        assertTrue(layout.scrollable)
        assertEquals(14f, layout.spacingPx, 0f)
    }
}
