package com.flue.launcher.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugAccessTest {
    @Test
    fun `fifth tap opens debug without toast and timeout resets sequence`() {
        var state = DebugTapState()
        repeat(4) { index -> state = state.tap(index * 200L) }
        assertFalse(state.openDebug)
        state = state.tap(800L)
        assertTrue(state.openDebug)
        assertFalse(state.showToast)

        state = DebugTapState().tap(0L).tap(2_000L)
        assertEquals(1, state.tapCount)
        assertFalse(state.openDebug)
    }
}
