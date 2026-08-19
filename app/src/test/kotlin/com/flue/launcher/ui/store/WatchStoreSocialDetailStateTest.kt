package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreComment
import com.flue.launcher.watchface.store.WatchStoreFeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStoreSocialDetailStateTest {

    @Test
    fun `comments keep server order and group one level of replies`() {
        val first = comment("root-a", parentId = null, author = "alice")
        val reply = comment("reply-a", parentId = "root-a", author = "bob")
        val second = comment("root-b", parentId = null, author = "carol")
        val orphan = comment("orphan", parentId = "missing", author = "dave")

        assertEquals(
            listOf(
                WatchStoreCommentThread(first, listOf(reply)),
                WatchStoreCommentThread(second, emptyList()),
                WatchStoreCommentThread(orphan, emptyList()),
            ),
            threadWatchStoreComments(listOf(first, reply, second, orphan)),
        )
    }

    @Test
    fun `only the signed in author can edit or delete a comment`() {
        val mine = comment("mine", parentId = null, author = "Yuzu_1")

        assertTrue(isOwnWatchStoreComment(mine, "Yuzu_1"))
        assertFalse(isOwnWatchStoreComment(mine, "yuzu_1"))
        assertFalse(isOwnWatchStoreComment(mine, null))
    }

    @Test
    fun `favorite count follows an actual toggle and never becomes negative`() {
        assertEquals(8L, favoriteCountAfterToggle(7, wasFavorite = false, isFavorite = true))
        assertEquals(6L, favoriteCountAfterToggle(7, wasFavorite = true, isFavorite = false))
        assertEquals(0L, favoriteCountAfterToggle(0, wasFavorite = true, isFavorite = false))
        assertEquals(7L, favoriteCountAfterToggle(7, wasFavorite = true, isFavorite = true))
    }

    @Test
    fun `social visibility respects server flags and authentication`() {
        val visible = watchStoreSocialDetailVisibility(
            flags = WatchStoreFeatureFlags(),
            signedIn = true,
        )
        assertTrue(visible.showComments)
        assertTrue(visible.allowCommentInput)
        assertTrue(visible.showRating)
        assertTrue(visible.showFavorite)
        assertTrue(visible.showReport)

        val disabled = watchStoreSocialDetailVisibility(
            flags = WatchStoreFeatureFlags(
                socialEnabled = false,
                commentsEnabled = false,
                reviewsEnabled = false,
            ),
            signedIn = true,
        )
        assertFalse(disabled.showComments)
        assertFalse(disabled.allowCommentInput)
        assertFalse(disabled.showRating)
        assertFalse(disabled.showFavorite)
        assertFalse(disabled.showReport)
    }

    @Test
    fun `signed out users can read comments but cannot mutate social state`() {
        val visibility = watchStoreSocialDetailVisibility(
            flags = WatchStoreFeatureFlags(),
            signedIn = false,
        )
        assertTrue(visibility.showComments)
        assertFalse(visibility.allowCommentInput)
        assertFalse(visibility.showRating)
        assertFalse(visibility.showFavorite)
        assertFalse(visibility.showReport)
    }

    private fun comment(
        id: String,
        parentId: String?,
        author: String,
    ) = WatchStoreComment(
        id = id,
        content = id,
        parentCommentId = parentId,
        createdAt = "",
        authorId = "user-$author",
        authorUsername = author,
        authorAvatarUrl = "/avatars/$author.webp",
    )
}
