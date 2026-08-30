package com.flue.launcher.watchface.standard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchmakerFrameBudgetTest {

    @Test
    fun `budget expires at its deadline`() {
        var nowNanos = 100L
        val budget = WatchmakerFrameBudget(10L) { nowNanos }

        budget.start()
        assertTrue(budget.hasTimeRemaining())
        nowNanos = 109L
        assertTrue(budget.hasTimeRemaining())
        nowNanos = 110L
        assertFalse(budget.hasTimeRemaining())
    }

    @Test
    fun `nonpositive budget never permits work`() {
        val budget = WatchmakerFrameBudget(0L) { 100L }

        budget.start()

        assertFalse(budget.hasTimeRemaining())
    }
}
