package com.flue.launcher.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetRefreshGateTest {
    @Test fun `recomposition coalesces but another provider and retry are independent`() {
        val gate = WidgetRefreshGate()
        assertTrue(gate.acquire("one:1,2", 0))
        assertFalse(gate.acquire("one:1,2", 20))
        assertTrue(gate.acquire("two:3", 20))
        assertTrue(gate.acquire("one:1,2", 21, force = true))
        gate.failed("one:1,2")
        assertTrue(gate.acquire("one:1,2", 22))
        assertTrue(gate.acquire("one:1,2", 1022))
    }
}
