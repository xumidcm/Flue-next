package com.flue.launcher.applist.nativeview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeDragPolicyTest {
    private val policy = NativeDragPolicy(20f, 84f, 30f, 500L, 0.55f)

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
