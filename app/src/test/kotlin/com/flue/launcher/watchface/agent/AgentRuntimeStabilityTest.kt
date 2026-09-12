package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.runtime.AgentStreamAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRuntimeStabilityTest {
    @Test
    fun `stream accumulator limits ui publications without losing text`() {
        val accumulator = AgentStreamAccumulator(minPublishIntervalMs = 50L)

        assertEquals("A", accumulator.append("A", nowMs = 0L))
        assertNull(accumulator.append("B", nowMs = 10L))
        assertNull(accumulator.append("C", nowMs = 49L))
        assertEquals("ABCD", accumulator.append("D", nowMs = 50L))
        assertEquals("ABCD", accumulator.snapshot())
    }
}
