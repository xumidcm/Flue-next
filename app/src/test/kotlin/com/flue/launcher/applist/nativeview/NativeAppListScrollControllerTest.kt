package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppListScrollControllerTest {
    @Test
    fun consumedScrollOwnerOnlyTicksAfterTheTargetOffsetActuallyChanges() {
        val target = FakeNativeAppListScrollTarget()
        val owner = NativeAppListConsumedScrollOwner(
            target = target,
            configProvider = { NativeAppListConfig(rotaryHapticsEnabled = true) },
            hapticGate = NativeAppListHapticGate(minIntervalMs = 0L),
        )

        assertFalse(
            owner.scrollBy(
                source = NativeAppListScrollSource.RotaryEncoder,
                deltaPixels = 20,
                eventTimeMs = 0L,
            ),
        )
        assertEquals(0, target.clockTickCount)

        target.consumeScroll = true
        assertTrue(
            owner.scrollBy(
                source = NativeAppListScrollSource.RotaryEncoder,
                deltaPixels = 20,
                eventTimeMs = 1L,
            ),
        )
        assertEquals(1, target.clockTickCount)
    }

    @Test
    fun rotaryAxisUsesTheSystemVerticalScrollFactor() {
        val delta = nativeAppListScrollDeltaPixels(
            source = NativeAppListScrollSource.RotaryEncoder,
            axisValue = 1.5f,
            verticalScrollFactor = 40f,
        )

        assertEquals(-60, delta)
    }

    @Test
    fun mouseWheelUsesTheSystemVerticalScrollFactor() {
        val delta = nativeAppListScrollDeltaPixels(
            source = NativeAppListScrollSource.MouseOrTouchpad,
            axisValue = -0.5f,
            verticalScrollFactor = 40f,
        )

        assertEquals(20, delta)
    }

    @Test
    fun zeroOrInvalidInputDoesNotScroll() {
        assertEquals(
            0,
            nativeAppListScrollDeltaPixels(
                source = NativeAppListScrollSource.RotaryEncoder,
                axisValue = 0f,
                verticalScrollFactor = 40f,
            ),
        )
        assertEquals(
            0,
            nativeAppListScrollDeltaPixels(
                source = NativeAppListScrollSource.MouseOrTouchpad,
                axisValue = Float.NaN,
                verticalScrollFactor = 40f,
            ),
        )
        assertEquals(
            0,
            nativeAppListScrollDeltaPixels(
                source = NativeAppListScrollSource.MouseOrTouchpad,
                axisValue = 1f,
                verticalScrollFactor = Float.POSITIVE_INFINITY,
            ),
        )
    }

    @Test
    fun rotaryHapticGateLimitsFeedbackToNinetyMilliseconds() {
        val gate = NativeAppListHapticGate(minIntervalMs = 90L)

        assertTrue(gate.tryAcquire(nowMs = 0L))
        assertFalse(gate.tryAcquire(nowMs = 89L))
        assertTrue(gate.tryAcquire(nowMs = 90L))
    }

    @Test
    fun rotaryHapticGateRecoversFromClockRollback() {
        val gate = NativeAppListHapticGate(minIntervalMs = 90L)

        assertTrue(gate.tryAcquire(nowMs = 1_000L))
        assertTrue(gate.tryAcquire(nowMs = 900L))
        assertFalse(gate.tryAcquire(nowMs = 989L))
        assertTrue(gate.tryAcquire(nowMs = 990L))
    }

    @Test
    fun disabledRotaryHapticsNeverReachTheFeedbackCall() {
        assertFalse(
            shouldPerformNativeAppListHaptic(
                source = NativeAppListScrollSource.RotaryEncoder,
                enabled = false,
                gateAccepted = true,
                scrollOffsetChanged = true,
            ),
        )
        assertFalse(
            shouldPerformNativeAppListHaptic(
                source = NativeAppListScrollSource.MouseOrTouchpad,
                enabled = true,
                gateAccepted = true,
                scrollOffsetChanged = true,
            ),
        )
        assertFalse(
            shouldPerformNativeAppListHaptic(
                source = NativeAppListScrollSource.RotaryEncoder,
                enabled = true,
                gateAccepted = false,
                scrollOffsetChanged = true,
            ),
        )
        assertFalse(
            shouldPerformNativeAppListHaptic(
                source = NativeAppListScrollSource.RotaryEncoder,
                enabled = true,
                gateAccepted = true,
                scrollOffsetChanged = false,
            ),
        )
        assertTrue(
            shouldPerformNativeAppListHaptic(
                source = NativeAppListScrollSource.RotaryEncoder,
                enabled = true,
                gateAccepted = true,
                scrollOffsetChanged = true,
            ),
        )
    }

    private class FakeNativeAppListScrollTarget : NativeAppListScrollTarget {
        var consumeScroll: Boolean = false
        var offset: Int = 0
        var clockTickCount: Int = 0

        override fun verticalScrollOffset(): Int = offset

        override fun scrollBy(deltaPixels: Int) {
            if (consumeScroll) {
                offset += deltaPixels
            }
        }

        override fun performClockTick() {
            clockTickCount += 1
        }
    }
}
