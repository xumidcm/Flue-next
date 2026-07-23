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
        assertEquals(30f, state.onMove(160f, atTop = true, blocked = false), 0f)
        assertEquals(200f, state.onMove(400f, atTop = true, blocked = false), 0f)
    }

    @Test
    fun `bottom pull is signed and can return toward rest without triggering face`() {
        state.onDown(300f)

        assertEquals(
            -40f,
            state.onMove(260f, atTop = false, atBottom = true, blocked = false),
            0f,
        )
        assertTrue(state.isPulling)
        assertEquals(
            -200f,
            state.onMove(0f, atTop = false, atBottom = true, blocked = false),
            0f,
        )
        assertEquals(
            -120f,
            state.onMove(80f, atTop = false, atBottom = false, blocked = false),
            0f,
        )

        val release = state.onUp()
        assertFalse(release.triggerFace)
        assertEquals(-120f, release.releaseOffsetPx, 0f)
    }

    @Test
    fun `presentation overscroll limits keep material at 140dp and other modes at half viewport`() {
        assertEquals(
            160f,
            nativeOverscrollLimitPx(
                presentation = NativeAppListPresentation.LINEAR,
                viewportHeightPx = 320,
                density = 2f,
            ),
            0f,
        )
        assertEquals(
            160f,
            nativeOverscrollLimitPx(
                presentation = NativeAppListPresentation.HONEYCOMB,
                viewportHeightPx = 320,
                density = 2f,
            ),
            0f,
        )
        assertEquals(
            280f,
            nativeOverscrollLimitPx(
                presentation = NativeAppListPresentation.DENSE_GRID,
                viewportHeightPx = 640,
                density = 2f,
            ),
            0f,
        )
    }

    @Test
    fun `apple honeycomb keeps the legacy twenty eight percent edge damping`() {
        assertEquals(
            28f,
            nativeOverscrollDragDelta(NativeAppListPresentation.HONEYCOMB, 100f),
            0f,
        )
        assertEquals(
            100f,
            nativeOverscrollDragDelta(NativeAppListPresentation.LINEAR, 100f),
            0f,
        )
        assertEquals(
            100f,
            nativeOverscrollDragDelta(NativeAppListPresentation.DENSE_GRID, 100f),
            0f,
        )
    }

    @Test
    fun `outward damping is symmetric while inward recovery remains one to one`() {
        state.onDown(100f)

        assertEquals(
            28f,
            state.onMove(
                pointerY = 200f,
                atTop = true,
                blocked = false,
                outwardDragMultiplier = 0.28f,
            ),
            0.001f,
        )
        assertEquals(
            8f,
            state.onMove(
                pointerY = 180f,
                atTop = false,
                blocked = false,
                outwardDragMultiplier = 0.28f,
            ),
            0.001f,
        )

        state.onDown(300f)
        assertEquals(
            -28f,
            state.onMove(
                pointerY = 200f,
                atTop = false,
                atBottom = true,
                blocked = false,
                outwardDragMultiplier = 0.28f,
            ),
            0.001f,
        )
        assertEquals(
            -8f,
            state.onMove(
                pointerY = 220f,
                atTop = false,
                atBottom = false,
                blocked = false,
                outwardDragMultiplier = 0.28f,
            ),
            0.001f,
        )
    }

    @Test
    fun `pull that begins before reaching an edge accumulates only post edge movement`() {
        state.onDown(100f)

        assertEquals(
            0f,
            state.onMove(60f, atTop = false, atBottom = false, blocked = false),
            0f,
        )
        assertEquals(
            -10f,
            state.onMove(50f, atTop = false, atBottom = true, blocked = false),
            0f,
        )
    }

    @Test
    fun `short list transfers a crossing delta directly to the opposite edge`() {
        state.onDown(100f)

        state.onMove(
            pointerY = 140f,
            atTop = true,
            atBottom = true,
            blocked = false,
        )

        assertEquals(
            -20f,
            state.onMove(
                pointerY = 80f,
                atTop = true,
                atBottom = true,
                blocked = false,
            ),
            0f,
        )
    }

    @Test
    fun `consumption contract returns only the inward portion owned before rest`() {
        state.onDown(100f)
        state.onMove(pointerY = 140f, atTop = true, blocked = false)

        val consumed = state.consumePointerDelta(
            pointerDeltaPx = -60f,
            atTop = true,
            atBottom = false,
            blocked = false,
        )

        assertEquals(-40f, consumed, 0f)
        assertEquals(-20f, -60f - consumed, 0f)
        assertEquals(0f, state.offsetPx, 0f)
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
