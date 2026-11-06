package com.flue.launcher.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowCrescentExitTest {
    @Test
    fun partiallyVisibleIconsContinueAlongTheCurvePastEitherEdge() {
        fun x(y: Float, direction: FlowCrescentDirection) = flowCrescentOffsetPx(y, 227f, 454f, 100f, direction)
        for (direction in FlowCrescentDirection.entries) {
            val before = x(2f, direction)
            val edge = x(0f, direction)
            val after = x(-2f, direction)
            assertTrue("curve must continue across the edge", (edge - before) * (after - edge) > 0f)
            assertEquals(edge - before, after - edge, 0.03f)
            assertEquals(after, x(456f, direction), 0.001f)
        }
    }
}
