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
        assertTrue(layout.useSpaceBetween)
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
        assertFalse(layout.useSpaceBetween)
    }

    @Test
    fun `two pixels per gap is still allowed`() {
        val layout = controlToggleRowLayout(
            availableWidthPx = 238f,
            buttonWidthPx = 58f,
            preferredSpacingPx = 14f,
        )

        assertFalse(layout.scrollable)
        assertEquals(2f, layout.spacingPx, 0f)
        assertFalse(layout.useSpaceBetween)
    }

    @Test
    fun `less than two pixels per gap switches to scrolling`() {
        val layout = controlToggleRowLayout(
            availableWidthPx = 237f,
            buttonWidthPx = 58f,
            preferredSpacingPx = 14f,
        )

        assertTrue(layout.scrollable)
        assertEquals(14f, layout.spacingPx, 0f)
        assertFalse(layout.useSpaceBetween)
    }
}
