package com.flue.launcher.watchface.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreatorWatchFacePagingTest {

    @Test
    fun `next page follows server pagination and stops at the end`() {
        assertEquals(
            3,
            nextCreatorWatchFacePage(
                WatchStorePagination(page = 2, limit = 20, total = 65, totalPages = 4),
            ),
        )
        assertNull(
            nextCreatorWatchFacePage(
                WatchStorePagination(page = 4, limit = 20, total = 65, totalPages = 4),
            ),
        )
    }

    @Test
    fun `appending a page preserves order and replaces duplicate ids`() {
        val old = face("a", "Old A")
        val staleB = face("b", "Old B")
        val freshB = face("b", "Fresh B")
        val next = face("c", "C")

        assertEquals(
            listOf(old, freshB, next),
            appendCreatorWatchFacePage(listOf(old, staleB), listOf(freshB, next)),
        )
    }

    private fun face(id: String, name: String) = WatchStoreWatchFace(
        id = id,
        name = name,
        screenType = WatchStoreScreenType.ROUND,
        category = WatchStoreCategory(id = "category", name = "Category", slug = "category"),
        previewUrl = "/preview/$id",
        downloadCount = 0,
        createdAt = "",
    )
}
