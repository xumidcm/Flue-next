package com.flue.launcher.watchface.selection

import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFaceCardReducerTest {
    private val faces = listOf("builtin", "jbwatch:one", "watchmaker:two")

    @Test
    fun `store is a stable final item and current face is the initial focus`() {
        val items = WatchFaceCardReducer.items(faces)

        assertEquals(faces, items.dropLast(1).map { it.stableId })
        assertTrue(items.last().isStore)
        assertEquals(items.lastIndex, items.indexOfFirst { it.stableId == WatchFaceCardReducer.STORE_ID })
        assertEquals(1, WatchFaceCardReducer.initialPage(items, "jbwatch:one"))
    }

    @Test
    fun `browsing never applies until the centered card is activated`() {
        val state = WatchFaceCardState(selectedFaceId = "builtin", focusedStableId = "builtin")

        val browsed = WatchFaceCardReducer.focus(state, "watchmaker:two")
        assertEquals("builtin", browsed.selectedFaceId)
        assertEquals("watchmaker:two", browsed.focusedStableId)

        val applied = WatchFaceCardReducer.activateCentered(browsed)
        assertEquals("watchmaker:two", applied.selectedFaceId)
        assertTrue(applied.shouldClose)
    }

    @Test
    fun `deleting a face focuses the nearest surviving neighbor`() {
        val result = WatchFaceCardReducer.afterDelete(
            state = WatchFaceCardState("builtin", "jbwatch:one"),
            deletedStableId = "jbwatch:one",
            previousItems = WatchFaceCardReducer.items(faces),
            remainingFaceIds = listOf("builtin", "watchmaker:two"),
        )

        assertEquals("watchmaker:two", result.focusedStableId)
        assertEquals("builtin", result.selectedFaceId)
        assertFalse(result.shouldClose)
    }

    @Test
    fun `deleting the selected face falls back to built in default`() {
        val result = WatchFaceCardReducer.afterDelete(
            state = WatchFaceCardState("jbwatch:one", "jbwatch:one"),
            deletedStableId = "jbwatch:one",
            previousItems = WatchFaceCardReducer.items(faces),
            remainingFaceIds = listOf("builtin", "watchmaker:two"),
        )

        assertEquals(BUILT_IN_WATCHFACE_ID, result.selectedFaceId)
        assertEquals("watchmaker:two", result.focusedStableId)
    }

    @Test
    fun `deleting the final face focuses the previous face instead of the store`() {
        val result = WatchFaceCardReducer.afterDelete(
            state = WatchFaceCardState("builtin", "watchmaker:two"),
            deletedStableId = "watchmaker:two",
            previousItems = WatchFaceCardReducer.items(faces),
            remainingFaceIds = listOf("builtin", "jbwatch:one"),
        )

        assertEquals("jbwatch:one", result.focusedStableId)
    }
}
