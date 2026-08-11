package com.flue.launcher.ui.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaryHapticGateTest {

    @Test
    fun `default gate keeps a 140 millisecond minimum interval`() {
        val gate = RotaryHapticGate()

        assertTrue(gate.tryAcquire(nowMs = 1_000L))
        assertFalse(gate.tryAcquire(nowMs = 1_139L))
        assertTrue(gate.tryAcquire(nowMs = 1_140L))
    }

    @Test
    fun `first event is allowed even when elapsed realtime is zero`() {
        val gate = RotaryHapticGate(minIntervalMs = 90L)

        assertTrue(gate.tryAcquire(nowMs = 0L))
        assertFalse(gate.tryAcquire(nowMs = 0L))
    }

    @Test
    fun `events are rate limited until the full interval has elapsed`() {
        val gate = RotaryHapticGate(minIntervalMs = 90L)

        assertTrue(gate.tryAcquire(nowMs = 1_000L))
        assertFalse(gate.tryAcquire(nowMs = 1_089L))
        assertTrue(gate.tryAcquire(nowMs = 1_090L))
    }

    @Test
    fun `clock rollback resets the gate instead of locking haptics`() {
        val gate = RotaryHapticGate(minIntervalMs = 90L)

        assertTrue(gate.tryAcquire(nowMs = 1_000L))
        assertTrue(gate.tryAcquire(nowMs = 900L))
        assertFalse(gate.tryAcquire(nowMs = 989L))
        assertTrue(gate.tryAcquire(nowMs = 990L))
    }
}
