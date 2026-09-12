package com.flue.launcher.ui.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPromptCapturePolicyTest {
    @Test
    fun `round and square screens capture within the bitmap budget`() {
        val size = legacyPromptCaptureSize(width = 454, height = 454)

        assertEquals(96, size.width)
        assertEquals(96, size.height)
        assertTrue(size.byteCount <= 40 * 1024)
    }

    @Test
    fun `rectangular screens preserve aspect and keep their short edge usable`() {
        val size = legacyPromptCaptureSize(width = 390, height = 450)

        assertEquals(83, size.width)
        assertEquals(96, size.height)
        assertTrue(size.shortEdge >= 40)
        assertTrue(size.longEdge <= 96)
    }

    @Test
    fun `a prompt chain captures only once`() {
        val gate = LegacyPromptCaptureGate()
        val token = gate.beginChain()

        assertTrue(gate.shouldAccept(token, foregroundStarted = false))
        gate.markCaptured(token)
        assertFalse(gate.shouldCapture(token))
        assertFalse(gate.shouldAccept(token, foregroundStarted = false))
    }

    @Test
    fun `late or contaminated pixel copy results are rejected`() {
        val gate = LegacyPromptCaptureGate()
        val first = gate.beginChain()
        val second = gate.beginChain()

        assertFalse(gate.shouldAccept(first, foregroundStarted = false))
        assertFalse(gate.shouldAccept(second, foregroundStarted = true))
        assertTrue(gate.shouldAccept(second, foregroundStarted = false))
    }
}
