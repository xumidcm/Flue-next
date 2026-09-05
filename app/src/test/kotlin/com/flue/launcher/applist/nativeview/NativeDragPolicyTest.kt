package com.flue.launcher.applist.nativeview

import org.junit.Assert.*
import org.junit.Test

class NativeDragPolicyTest {
    private val policy = NativeDragPolicy(20f, 84f, 30f)

    @Test
    fun `all layouts share 300ms dwell and density scaled jitter`() {
        val profiles = NativeDragPolicyProfiles(2f)
        NativeAppListPresentation.entries.forEach {
            val profile = profiles.forPresentation(it)
            assertEquals(300L, profile.folderHoverMillis)
            assertEquals(12f, profile.folderJitterPx, 0f)
            assertEquals(40f, profile.startThresholdPx, 0f)
        }
        assertSame(profiles.forPresentation(NativeAppListPresentation.HONEYCOMB),
            profiles.forPresentation(NativeAppListPresentation.DENSE_GRID))
    }

    @Test
    fun `auto scroll is proportional only inside edge zones`() {
        assertEquals(-30f, nativeAutoScrollDelta(0f, 400f, policy), 0f)
        assertEquals(0f, nativeAutoScrollDelta(200f, 400f, policy), 0f)
        assertEquals(30f, nativeAutoScrollDelta(400f, 400f, policy), 0f)
    }

    @Test
    fun `stationary dwell reaches deadline exactly`() {
        val gate = NativeFolderHoverGate(policy)
        assertNull(gate.update("a", 10f, 20f, 1000L))
        assertEquals(1300L, gate.deadlineMillis)
        assertNull(gate.update("a", 10f, 20f, 1299L))
        assertEquals("a", gate.update("a", 10f, 20f, 1300L))
        assertNull(gate.deadlineMillis)
    }

    @Test
    fun `jitter is measured from dwell anchor not individual sample distance`() {
        val gate = NativeFolderHoverGate(policy)
        gate.update("a", 0f, 0f, 1000L)
        gate.update("a", 3f, 0f, 1100L)
        gate.update("a", 6f, 0f, 1200L)
        assertEquals("a", gate.update("a", 6f, 0f, 1300L))
        assertNull(gate.update("a", 7f, 0f, 1301L))
        assertEquals(1601L, gate.deadlineMillis)
        assertEquals("a", gate.update("a", 7f, 0f, 1601L))
    }

    @Test
    fun `changing target and leaving restart dwell`() {
        val gate = NativeFolderHoverGate(policy)
        gate.update("a", 0f, 0f, 1000L)
        assertNull(gate.update("b", 0f, 0f, 1290L))
        assertNull(gate.update(null, 0f, 0f, 1300L))
        assertNull(gate.targetKey)
        assertNull(gate.deadlineMillis)
        assertNull(gate.update("b", 0f, 0f, 1600L))
        assertEquals("b", gate.update("b", 0f, 0f, 1900L))
    }
}
