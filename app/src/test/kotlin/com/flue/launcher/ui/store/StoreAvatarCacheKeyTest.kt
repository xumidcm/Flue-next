package com.flue.launcher.ui.store

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreAvatarCacheKeyTest {

    @Test
    fun avatarVersionInvalidatesAnUnchangedUrl() {
        assertNotEquals(
            storeAvatarCacheKey("/uploads/avatar.webp", 1L),
            storeAvatarCacheKey("/uploads/avatar.webp", 2L),
        )
        assertEquals(
            storeAvatarCacheKey(" /uploads/avatar.webp ", 2L),
            storeAvatarCacheKey("/uploads/avatar.webp", 2L),
        )
    }

    @Test
    fun avatarVersionDoesNotClearTheDisplayedBitmapForTheSameUrl() {
        assertEquals(
            storeAvatarBitmapStateKey(" /uploads/avatar.webp ", 76),
            storeAvatarBitmapStateKey("/uploads/avatar.webp", 76),
        )
    }
}
