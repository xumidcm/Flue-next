package com.flue.launcher.watchface.standard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchmakerFontAtlasCachePolicyTest {
    @Test
    fun `six small bitmap font pages stay resident for one active face`() {
        assertFalse(
            WatchmakerFontAtlasCachePolicy.shouldEvict(
                entryCount = 6,
                allocationBytes = 3L * 1024L * 1024L,
            ),
        )
    }

    @Test
    fun `pathological font atlas sets remain bounded`() {
        assertTrue(
            WatchmakerFontAtlasCachePolicy.shouldEvict(
                entryCount = 17,
                allocationBytes = 3L * 1024L * 1024L,
            ),
        )
        assertTrue(
            WatchmakerFontAtlasCachePolicy.shouldEvict(
                entryCount = 6,
                allocationBytes = 25L * 1024L * 1024L,
            ),
        )
    }
}
