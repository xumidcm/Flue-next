package com.flue.launcher.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PressFeedbackTest {
    @Test
    fun releasedControlsReturnToIdentityScale() {
        assertEquals(1f, fluePressScaleTarget(pressed = false, pressedScale = 0.94f), 0f)
    }

    @Test
    fun pressedScaleIsClampedToSafeRange() {
        assertEquals(0.8f, fluePressScaleTarget(pressed = true, pressedScale = 0.1f), 0f)
        assertEquals(1f, fluePressScaleTarget(pressed = true, pressedScale = 1.4f), 0f)
        assertEquals(0.94f, fluePressScaleTarget(pressed = true, pressedScale = 0.94f), 0f)
    }
}
