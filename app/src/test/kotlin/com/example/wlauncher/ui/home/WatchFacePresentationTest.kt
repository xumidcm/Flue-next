package com.flue.launcher.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFacePresentationTest {
    @Test
    fun returningSceneWaitsForItsOwnDrawBeforeShowingStatus() {
        val gate = WatchFacePresentationGate()
        val idle = gate.update(true)
        assertFalse(gate.visible)
        gate.onFrameDrawn(idle)
        assertTrue(gate.visible)
        gate.update(false)
        assertFalse(gate.visible)
        val returned = gate.update(true)
        gate.onFrameDrawn(idle)
        assertFalse(gate.visible)
        gate.onFrameDrawn(returned)
        assertTrue(gate.visible)
    }

    @Test
    fun interruptedReturnCannotExposeStatusDuringAnotherGesture() {
        val gate = WatchFacePresentationGate()
        val firstReturn = gate.update(true)
        gate.update(false)
        gate.onFrameDrawn(firstReturn)
        assertFalse(gate.visible)
        val nextReturn = gate.update(true)
        gate.onFrameDrawn(firstReturn)
        assertFalse(gate.visible)
        gate.onFrameDrawn(nextReturn)
        assertTrue(gate.visible)
    }
}
