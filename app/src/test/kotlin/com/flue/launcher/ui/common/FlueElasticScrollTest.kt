package com.flue.launcher.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test

class FlueElasticScrollTest {
    @Test
    fun zeroStrengthProducesNoElasticTravel() {
        assertEquals(
            0f,
            flueElasticMaximumOffsetPx(
                viewportPx = 1_000,
                density = 2f,
                edgeStrength = 0,
            ),
            0f,
        )
    }

    @Test
    fun consecutiveMoveDeltasMutateTheRenderedOffsetSynchronously() {
        val job = SupervisorJob()
        try {
            val controller = FlueElasticOffsetController(CoroutineScope(job))
            controller.setImmediate(18f)

            controller.setImmediate(controller.value - 5f)
            controller.setImmediate(controller.value - 5f)

            assertEquals(8f, controller.value, 0f)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun defaultStrengthKeepsCloxMaximumTravel() {
        assertEquals(
            96f,
            flueElasticMaximumOffsetPx(
                viewportPx = 1_000,
                density = 2f,
                edgeStrength = 5,
            ),
            0f,
        )
    }

    @Test
    fun nonScrollablePageOwnsBottomEdge() {
        assertEquals(true, flueScrollStateAtBottom(value = 0, maxValue = 0))
    }

    @Test
    fun scrollablePageOwnsBottomOnlyAtItsMaximum() {
        assertEquals(false, flueScrollStateAtBottom(value = 39, maxValue = 40))
        assertEquals(true, flueScrollStateAtBottom(value = 40, maxValue = 40))
    }
}
