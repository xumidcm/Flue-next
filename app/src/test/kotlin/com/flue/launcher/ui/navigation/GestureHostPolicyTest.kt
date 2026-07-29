package com.flue.launcher.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureHostPolicyTest {
    @Test
    fun positionThresholdSettlesBothOpeningAndClosingGestures() {
        assertFalse(resolveOverlaySettleOpen(0.42f, 0f, currentlyOpen = false, cancelled = false))
        assertTrue(resolveOverlaySettleOpen(0.421f, 0f, currentlyOpen = false, cancelled = false))
        assertFalse(resolveOverlaySettleOpen(0.2f, 0f, currentlyOpen = true, cancelled = false))
        assertTrue(resolveOverlaySettleOpen(0.8f, 0f, currentlyOpen = true, cancelled = false))
    }

    @Test
    fun directionalFlingWinsOverPosition() {
        assertTrue(resolveOverlaySettleOpen(0.1f, 1.6f, currentlyOpen = false, cancelled = false))
        assertFalse(resolveOverlaySettleOpen(0.9f, -1.6f, currentlyOpen = true, cancelled = false))
    }

    @Test
    fun cancellationRestoresGestureOrigin() {
        assertFalse(resolveOverlaySettleOpen(0.9f, 9f, currentlyOpen = false, cancelled = true))
        assertTrue(resolveOverlaySettleOpen(0.1f, -9f, currentlyOpen = true, cancelled = true))
    }
}
