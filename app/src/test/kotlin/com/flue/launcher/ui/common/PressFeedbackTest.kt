package com.flue.launcher.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun cancelledPressAlwaysClearsItsPressedState() = runBlocking {
        var pressed = false
        runCatching {
            runInstantPressGesture(
                setPressed = { pressed = it },
                awaitRelease = { throw CancellationException("pointer left window") },
                onClick = {},
            )
        }

        assertFalse(pressed)
    }
}
