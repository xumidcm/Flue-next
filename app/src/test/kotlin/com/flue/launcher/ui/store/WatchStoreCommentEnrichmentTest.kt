package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreAuthSession
import com.flue.launcher.watchface.store.WatchStoreComment
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStoreCommentEnrichmentTest {

    @Test
    fun postedCommentImmediatelyUsesTheCurrentSessionAvatar() {
        val posted = WatchStoreComment(
            id = "comment-1",
            content = "hello",
            parentCommentId = null,
            createdAt = "now",
            authorId = "server-id",
            authorUsername = "stale-name",
        )
        val session = WatchStoreAuthSession(
            token = "token",
            username = "current-user",
            expiresAtEpochMs = Long.MAX_VALUE,
            avatarUrl = "/uploads/current.webp",
            avatarVersion = 7L,
        )

        val enriched = enrichPostedWatchStoreComment(posted, session)

        assertEquals("current-user", enriched.authorUsername)
        assertEquals("/uploads/current.webp", enriched.authorAvatarUrl)
        assertEquals(7L, enriched.authorAvatarVersion)
    }
}
