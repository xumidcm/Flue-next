package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WatchmakerRenderTransitionStateTest {

    @Test
    fun overlayRenderGatePausesDynamicWorkAcrossOneCompleteTransition() {
        val gate = WatchmakerOverlayRenderGate()

        assertEquals(WatchmakerRenderState.IDLE, gate.state)
        assertTrue(gate.beginOverlayFreeze())
        assertEquals(WatchmakerRenderState.FREEZING, gate.state)
        assertTrue(gate.dynamicWorkPaused)
        assertFalse(gate.beginOverlayFreeze())

        assertTrue(gate.markFrozenFrameReady())
        assertEquals(WatchmakerRenderState.FROZEN_OVERLAY, gate.state)
        assertTrue(gate.dynamicWorkPaused)
        assertFalse(gate.markFrozenFrameReady())

        assertTrue(gate.beginOverlayResume())
        assertEquals(WatchmakerRenderState.RESUMING, gate.state)
        assertTrue(gate.dynamicWorkPaused)
        assertTrue(gate.markResumeComplete())
        assertEquals(WatchmakerRenderState.IDLE, gate.state)
        assertFalse(gate.dynamicWorkPaused)
        assertFalse(gate.markResumeComplete())
    }

    @Test
    fun overlayFreezeAndResumeAreIdempotent() {
        val state = WatchmakerRenderTransitionStateMachine()

        assertEquals(WatchmakerRenderState.IDLE, state.current)
        assertTrue(state.beginFreeze())
        assertEquals(WatchmakerRenderState.FREEZING, state.current)
        assertFalse(state.beginFreeze())

        assertTrue(state.markFrozenFrameReady())
        assertEquals(WatchmakerRenderState.FROZEN_OVERLAY, state.current)
        assertFalse(state.markFrozenFrameReady())

        assertTrue(state.beginResume())
        assertEquals(WatchmakerRenderState.RESUMING, state.current)
        assertFalse(state.beginResume())
        assertTrue(state.markResumeComplete())
        assertEquals(WatchmakerRenderState.IDLE, state.current)
        assertFalse(state.markResumeComplete())
    }

    @Test
    fun bitmapIsNotRetirableWhileAnyRendererStillOwnsIt() {
        val lease = WatchmakerBitmapLease()
        lease.acquire(WatchmakerBitmapConsumer.PIXEL_COPY)
        lease.acquire(WatchmakerBitmapConsumer.COMPOSE)
        lease.acquire(WatchmakerBitmapConsumer.RENDER_THREAD)
        lease.acquire(WatchmakerBitmapConsumer.TEXTURE_VIEW)

        assertFalse(lease.canRecycle)
        lease.release(WatchmakerBitmapConsumer.PIXEL_COPY)
        lease.release(WatchmakerBitmapConsumer.COMPOSE)
        lease.release(WatchmakerBitmapConsumer.RENDER_THREAD)
        assertFalse(lease.canRecycle)
        lease.release(WatchmakerBitmapConsumer.TEXTURE_VIEW)
        assertTrue(lease.canRecycle)

        // Releasing a stale callback twice must not underflow and must remain safe.
        lease.release(WatchmakerBitmapConsumer.TEXTURE_VIEW)
        assertTrue(lease.canRecycle)
    }

    @Test
    fun retiredBitmapRunsRecycleOnlyAfterEveryConsumerReleases() {
        val recycleCalls = AtomicInteger(0)
        val lease = WatchmakerBitmapLease { recycleCalls.incrementAndGet() }
        lease.acquire(WatchmakerBitmapConsumer.COMPOSE)
        lease.acquire(WatchmakerBitmapConsumer.RENDER_THREAD)

        lease.retire()
        assertEquals(0, recycleCalls.get())
        lease.release(WatchmakerBitmapConsumer.COMPOSE)
        assertEquals(0, recycleCalls.get())
        lease.release(WatchmakerBitmapConsumer.RENDER_THREAD)
        assertEquals(1, recycleCalls.get())

        lease.release(WatchmakerBitmapConsumer.RENDER_THREAD)
        lease.retire()
        assertEquals(1, recycleCalls.get())
    }
}
