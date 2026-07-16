package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePullToFaceStateTest {
    private val state = NativePullToFaceState(triggerThresholdPx = 72f, maxOffsetPx = 200f)

    @Test
    fun `pull is owned only downward at the top and respects maximum`() {
        state.onDown(100f)

        assertEquals(0f, state.onMove(130f, atTop = false, blocked = false), 0f)
        assertEquals(30f, state.onMove(130f, atTop = true, blocked = false), 0f)
        assertEquals(200f, state.onMove(400f, atTop = true, blocked = false), 0f)
    }

    @Test
    fun `release triggers face only after legacy threshold`() {
        state.onDown(100f)
        state.onMove(171f, atTop = true, blocked = false)
        assertFalse(state.onUp().triggerFace)

        state.onDown(100f)
        state.onMove(172f, atTop = true, blocked = false)
        assertTrue(state.onUp().triggerFace)
        assertEquals(72f, state.lastReleaseOffsetPx, 0f)
    }

    @Test
    fun `blocked drag and cancel clear ownership`() {
        state.onDown(100f)
        assertEquals(0f, state.onMove(200f, atTop = true, blocked = true), 0f)
        assertFalse(state.isPulling)
        state.onDown(100f)
        state.onMove(150f, atTop = true, blocked = false)
        state.cancel()
        assertFalse(state.isPulling)
        assertEquals(0f, state.offsetPx, 0f)
    }
}
