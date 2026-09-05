package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeScrollIndicatorControllerTest {
    @Test
    fun `a new controller and unsolicited frames remain hidden without work`() {
        val controller = NativeScrollIndicatorController()

        assertEquals(0f, controller.alpha, 0f)
        assertHiddenAndStopped(controller.onFrame(10_000L))
    }

    @Test
    fun `scroll fades in holds and fades to zero without any draw callbacks`() {
        val controller = NativeScrollIndicatorController()

        val started = controller.onScroll(0L)
        assertEquals(0f, started.alpha, 0f)
        assertAnimating(started)
        assertEquals(0.5f, controller.onFrame(55L).alpha, 0.0001f)

        val held = controller.onFrame(110L)
        assertEquals(1f, held.alpha, 0f)
        assertFalse(held.needsNextFrame)
        assertEquals(700L, held.nextWakeUptimeMillis)

        val fading = controller.onFrame(700L)
        assertEquals(1f, fading.alpha, 0f)
        assertAnimating(fading)
        assertEquals(0.5f, controller.onFrame(830L).alpha, 0.0001f)
        assertHiddenAndStopped(controller.onFrame(960L))
        assertEquals(0f, controller.alpha, 0f)
        assertHiddenAndStopped(controller.onFrame(100_000L))
    }

    @Test
    fun `a held pointer stays visible without polling or fresh scroll events`() {
        val controller = NativeScrollIndicatorController()
        controller.onPointerDown(0L)

        val visible = controller.onFrame(110L)
        assertEquals(1f, visible.alpha, 0f)
        assertFalse(visible.needsNextFrame)
        assertNull(visible.nextWakeUptimeMillis)

        val stillHeld = controller.onFrame(10_000L)
        assertEquals(1f, stillHeld.alpha, 0f)
        assertFalse(stillHeld.needsNextFrame)
        assertNull(stillHeld.nextWakeUptimeMillis)
    }

    @Test
    fun `pointer release starts a complete new hold window`() {
        val controller = NativeScrollIndicatorController()
        controller.onPointerDown(0L)
        controller.onFrame(110L)

        val released = controller.onPointerUp(10_000L)
        assertEquals(1f, released.alpha, 0f)
        assertFalse(released.needsNextFrame)
        assertEquals(10_700L, released.nextWakeUptimeMillis)
        assertEquals(1f, controller.onFrame(10_699L).alpha, 0f)
        assertAnimating(controller.onFrame(10_700L))
        assertHiddenAndStopped(controller.onFrame(10_960L))
    }

    @Test
    fun `real continuous scroll refreshes one wake deadline`() {
        val controller = NativeScrollIndicatorController()
        controller.onScroll(0L)
        controller.onFrame(110L)

        for (now in 200L..2_000L step 100L) {
            val refreshed = controller.onScroll(now)
            assertEquals(1f, refreshed.alpha, 0f)
            assertFalse(refreshed.needsNextFrame)
            assertEquals(now + 700L, refreshed.nextWakeUptimeMillis)
        }

        assertAnimating(controller.onFrame(2_700L))
        assertHiddenAndStopped(controller.onFrame(2_960L))
    }

    @Test
    fun `without new events the indicator stops even if no recycler idle event arrives`() {
        val controller = NativeScrollIndicatorController()
        controller.onPointerDown(0L)
        controller.onFrame(110L)
        controller.onScroll(200L)
        controller.onPointerUp(220L)

        // No RecyclerView state transition is required to expire this activity.
        assertAnimating(controller.onFrame(920L))
        assertHiddenAndStopped(controller.onFrame(1_180L))
    }

    @Test
    fun `scroll interruption reverses a fade from its current alpha`() {
        val controller = NativeScrollIndicatorController()
        controller.onScroll(0L)
        controller.onFrame(110L)
        controller.onFrame(700L)
        assertEquals(0.5f, controller.onFrame(830L).alpha, 0.0001f)

        val resumed = controller.onScroll(840L)
        assertEquals(0.5f, resumed.alpha, 0.0001f)
        assertAnimating(resumed)
        assertEquals(0.6f, controller.onFrame(851L).alpha, 0.0001f)
        val visible = controller.onFrame(895L)
        assertEquals(1f, visible.alpha, 0f)
        assertEquals(1_540L, visible.nextWakeUptimeMillis)
    }

    @Test
    fun `fresh activity after a long hidden interval starts from a fresh frame clock`() {
        val controller = NativeScrollIndicatorController()
        controller.onScroll(0L)
        controller.onFrame(110L)
        controller.onFrame(700L)
        controller.onFrame(960L)

        assertEquals(0f, controller.onScroll(100_000L).alpha, 0f)
        assertEquals(0f, controller.onFrame(100_000L).alpha, 0f)
        assertEquals(0.1f, controller.onFrame(100_011L).alpha, 0.0001f)
    }

    @Test
    fun `a late hold wake starts fading without including the parked interval`() {
        val controller = NativeScrollIndicatorController()
        controller.onScroll(0L)
        controller.onFrame(110L)

        val lateWake = controller.onFrame(10_000L)
        assertEquals(1f, lateWake.alpha, 0f)
        assertAnimating(lateWake)
        assertEquals(0.5f, controller.onFrame(10_130L).alpha, 0.0001f)
        assertHiddenAndStopped(controller.onFrame(10_260L))
    }

    @Test
    fun `scroll events during fade in do not keep resetting its frame clock`() {
        val controller = NativeScrollIndicatorController()
        controller.onScroll(0L)
        assertEquals(0.1f, controller.onFrame(11L).alpha, 0.0001f)

        controller.onScroll(15L)
        assertEquals(0.2f, controller.onFrame(22L).alpha, 0.0001f)
        controller.onActivity(25L)
        assertEquals(0.3f, controller.onFrame(33L).alpha, 0.0001f)
    }

    @Test
    fun `reset cancels a held pointer and any stale frame remains stopped`() {
        val controller = NativeScrollIndicatorController()
        controller.onPointerDown(0L)
        controller.onFrame(110L)

        assertHiddenAndStopped(controller.reset())
        assertHiddenAndStopped(controller.onFrame(700L))
        assertHiddenAndStopped(controller.onPointerUp(800L))
    }

    @Test
    fun `reset cancels a scheduled hold and a running fade`() {
        val controller = NativeScrollIndicatorController()
        controller.onScroll(0L)
        controller.onFrame(110L)

        assertHiddenAndStopped(controller.reset())
        assertHiddenAndStopped(controller.onFrame(700L))

        controller.onScroll(1_000L)
        controller.onFrame(1_110L)
        controller.onFrame(1_700L)
        controller.onFrame(1_830L)
        assertHiddenAndStopped(controller.reset())
        assertHiddenAndStopped(controller.onFrame(1_960L))
    }

    private fun assertAnimating(result: NativeScrollIndicatorController.FrameResult) {
        assertTrue(result.needsNextFrame)
        assertNull(result.nextWakeUptimeMillis)
    }

    private fun assertHiddenAndStopped(result: NativeScrollIndicatorController.FrameResult) {
        assertEquals(0f, result.alpha, 0f)
        assertFalse(result.needsNextFrame)
        assertNull(result.nextWakeUptimeMillis)
    }
}
