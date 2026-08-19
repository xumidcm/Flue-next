package com.flue.launcher.watchface.store

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStoreTagDraftTest {
    @Test
    fun addTrimsInputClearsItAndKeepsDistinctTags() {
        assertEquals(
            listOf("运动", "户外"),
            addWatchStoreTag(listOf("运动"), "  户外  "),
        )
        assertEquals(
            listOf("运动"),
            addWatchStoreTag(listOf("运动"), " 运动 "),
        )
    }

    @Test
    fun editRemovesTheChipAndReturnsItsTextForEditing() {
        assertEquals(
            WatchStoreTagEditResult(tags = listOf("运动"), editingText = "户外"),
            beginEditWatchStoreTag(listOf("运动", "户外"), 1),
        )
    }

    @Test
    fun removeIgnoresInvalidIndexes() {
        assertEquals(
            listOf("运动", "户外"),
            removeWatchStoreTag(listOf("运动", "户外"), 4),
        )
        assertEquals(
            listOf("户外"),
            removeWatchStoreTag(listOf("运动", "户外"), 0),
        )
    }
}
