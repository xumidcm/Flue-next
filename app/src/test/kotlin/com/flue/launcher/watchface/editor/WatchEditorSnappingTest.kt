package com.flue.launcher.watchface.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchEditorSnappingTest {
    @Test
    fun rightAngleRotationSnapsAndUsesExitHysteresis() {
        val state = WatchEditorSnapping.RotationGestureState()

        assertEquals(
            90f,
            WatchEditorSnapping.snapRightAngleRotation(88f, state),
            0f,
        )
        assertEquals(
            90f,
            WatchEditorSnapping.snapRightAngleRotation(96f, state),
            0f,
        )
        assertEquals(
            98f,
            WatchEditorSnapping.snapRightAngleRotation(98f, state),
            0f,
        )
    }

    @Test
    fun rightAngleRotationWrapsAcrossZero() {
        val state = WatchEditorSnapping.RotationGestureState()
        assertEquals(
            360f,
            WatchEditorSnapping.snapRightAngleRotation(358f, state),
            0f,
        )
        assertEquals(
            360f,
            WatchEditorSnapping.snapRightAngleRotation(2f, state),
            0f,
        )
    }
}
