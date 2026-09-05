package com.flue.launcher.watchface.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchStoreMetadataTest {
    @Test
    fun `trims and keeps a nonblank author`() {
        assertEquals("Alice", normalizedWatchStoreAuthor("  Alice  "))
    }

    @Test
    fun `omits blank author`() {
        assertNull(normalizedWatchStoreAuthor("  "))
    }
}
