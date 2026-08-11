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
        assertEquals(56f, layout.actualSpacingPx, 0f)
        assertEquals(114f, controlToggleLabelSlotWidthPx(58f, layout.actualSpacingPx, false), 0f)
        assertEquals(86f, controlToggleLabelSlotWidthPx(58f, layout.actualSpacingPx, true), 0f)
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

    @Test
    fun `long labels use their own marquee slot without ellipsis`() {
        assertTrue(controlToggleLabelOverflows(slotWidthPx = 58f, textWidthPx = 74f))
        assertFalse(controlToggleLabelOverflows(slotWidthPx = 58f, textWidthPx = 52f))
        assertFalse(controlToggleLabelOverflows(slotWidthPx = 74f, textWidthPx = 74f))
        assertEquals(14f, CONTROL_TOGGLE_LABEL_MARQUEE_SPEED_DP_PER_SECOND, 0f)
    }
}
