package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NativeDragPolicyTest {
    private val policy = NativeDragPolicy(20f, 84f, 30f, 500L, 0.55f)

    @Test
    fun `presentation maps to exact reusable drag profiles`() {
        val profiles = NativeDragPolicyProfiles(density = 2f)

        val linear = profiles.forPresentation(NativeAppListPresentation.LINEAR)
        val honeycomb = profiles.forPresentation(NativeAppListPresentation.HONEYCOMB)
        val denseGrid = profiles.forPresentation(NativeAppListPresentation.DENSE_GRID)

        assertEquals(
            NativeDragPolicy(
                startThresholdPx = 40f,
                autoScrollEdgePx = 168f,
                maxAutoScrollPxPerFrame = 30f,
                folderHoverMillis = 500L,
                folderHoverMaxSpeedPxPerMs = 0.55f,
                longPressMillis = 620L,
            ),
            linear,
        )
        assertEquals(
            NativeDragPolicy(
                startThresholdPx = 40f,
                autoScrollEdgePx = 192f,
                maxAutoScrollPxPerFrame = 26f,
                folderHoverMillis = 700L,
                folderHoverMaxSpeedPxPerMs = 0.55f,
                longPressMillis = 620L,
            ),
            honeycomb,
        )
        assertSame(honeycomb, denseGrid)
        assertSame(linear, profiles.forPresentation(NativeAppListPresentation.LINEAR))
    }

    @Test
    fun `auto scroll is proportional only inside edge zones`() {
        assertEquals(-30f, nativeAutoScrollDelta(0f, 400f, policy))
        assertEquals(0f, nativeAutoScrollDelta(200f, 400f, policy))
        assertEquals(30f, nativeAutoScrollDelta(400f, 400f, policy))
    }

    @Test
    fun `folder hover requires same slow target for full delay`() {
        val gate = NativeFolderHoverGate(policy)
        assertNull(gate.update("a", 0.2f, 1000L))
        assertNull(gate.update("a", 0.2f, 1499L))
        assertEquals("a", gate.update("a", 0.2f, 1500L))
        assertNull(gate.update("b", 0.2f, 1600L))
        assertNull(gate.update("b", 0.8f, 2200L))
    }
}
