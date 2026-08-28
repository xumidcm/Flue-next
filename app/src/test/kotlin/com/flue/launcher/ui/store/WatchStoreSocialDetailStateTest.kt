package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreComment
import com.flue.launcher.watchface.store.WatchStoreFeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStoreSocialDetailStateTest {

    @Test
    fun `comments keep server order and flatten arbitrary depth into the root thread`() {
        val first = comment("root-a", parentId = null, author = "alice")
        val reply = comment("reply-a", parentId = "root-a", author = "bob")
        val nestedReply = comment("nested-a", parentId = "reply-a", author = "erin")
        val second = comment("root-b", parentId = null, author = "carol")
        val orphan = comment("orphan", parentId = "missing", author = "dave")

        assertEquals(
            listOf(
                WatchStoreCommentThread(
                    first,
                    listOf(
                        WatchStoreCommentReply(reply, replyToUsername = "alice"),
                        WatchStoreCommentReply(nestedReply, replyToUsername = "bob"),
                    ),
                ),
                WatchStoreCommentThread(second, emptyList()),
                WatchStoreCommentThread(orphan, emptyList()),
            ),
            threadWatchStoreComments(listOf(first, reply, nestedReply, second, orphan)),
        )
    }

    @Test
    fun `comment ownership uses immutable author id instead of username`() {
        val mine = comment("mine", parentId = null, author = "OldName", authorId = "user-42")

        assertTrue(isOwnWatchStoreComment(mine, "user-42"))
        assertFalse(isOwnWatchStoreComment(mine, "OldName"))
        assertFalse(isOwnWatchStoreComment(mine, null))
    }

    @Test
    fun `editing a reply only replaces content and keeps its parent and author`() {
        val original = comment("reply", parentId = "root", author = "alice", authorId = "user-1")
        val sparseResponse = WatchStoreComment(
            id = "reply",
            content = "edited",
            parentCommentId = null,
            createdAt = "new timestamp",
            authorId = "",
            authorUsername = "",
        )

        assertEquals(
            original.copy(content = "edited"),
            mergeEditedWatchStoreComment(original, sparseResponse),
        )
    }

    @Test
    fun `deleting a comment removes every descendant but keeps other threads`() {
        val root = comment("root", parentId = null, author = "alice")
        val child = comment("child", parentId = "root", author = "bob")
        val grandchild = comment("grandchild", parentId = "child", author = "carol")
        val other = comment("other", parentId = null, author = "dave")

        assertEquals(
            listOf(other),
            commentsAfterWatchStoreCommentDeletion(
                comments = listOf(root, child, grandchild, other),
                deletedId = "root",
            ),
        )
    }

    @Test
    fun `cyclic parent ids never loop forever or drop comments`() {
        val a = comment("a", parentId = "b", author = "alice")
        val b = comment("b", parentId = "a", author = "bob")

        val threads = threadWatchStoreComments(listOf(a, b))

        assertEquals(setOf("a", "b"), threads.flatMap { thread ->
            listOf(thread.root.id) + thread.replies.map { it.comment.id }
        }.toSet())
    }

    @Test
    fun `a chain deeper than the hop limit still shows every comment`() {
        // 深链是脏数据（上游没有深度约束），建树的跳数上限会让最深那几条自成顶级 ——
        // 摆错位置可以接受，丢评论不行。
        val chain = (0..24).map { index ->
            comment(
                id = "c$index",
                parentId = if (index == 0) null else "c${index - 1}",
                author = "u$index",
            )
        }

        val threads = threadWatchStoreComments(chain)

        assertEquals(
            chain.map { it.id }.toSet(),
            threads.flatMap { thread ->
                listOf(thread.root.id) + thread.replies.map { it.comment.id }
            }.toSet(),
        )
    }

    @Test
    fun `duplicate ids never yield two threads sharing one list key`() {
        // threads 的 root.id 直接当 LazyColumn 的 key，重复 key 会当场抛异常。
        val root = comment("root", parentId = null, author = "alice")

        val threads = threadWatchStoreComments(listOf(root, root))

        assertEquals(1, threads.size)
        assertEquals("root", threads.single().root.id)
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
        assertFalse(visible.ratingRequiresLogin)
        assertTrue(visible.showFavorite)
        assertFalse(visible.favoriteRequiresLogin)
        assertTrue(visible.showReport)
        assertFalse(visible.reportRequiresLogin)

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
        assertFalse(disabled.ratingRequiresLogin)
        assertFalse(disabled.showFavorite)
        assertFalse(disabled.favoriteRequiresLogin)
        assertFalse(disabled.showReport)
        assertFalse(disabled.reportRequiresLogin)
    }

    @Test
    fun `signed out users can read comments but cannot mutate social state`() {
        val visibility = watchStoreSocialDetailVisibility(
            flags = WatchStoreFeatureFlags(),
            signedIn = false,
        )
        assertTrue(visibility.showComments)
        assertFalse(visibility.allowCommentInput)
        assertTrue(visibility.showRating)
        assertTrue(visibility.ratingRequiresLogin)
        assertTrue(visibility.showFavorite)
        assertTrue(visibility.favoriteRequiresLogin)
        assertTrue(visibility.showReport)
        assertTrue(visibility.reportRequiresLogin)
    }

    private fun comment(
        id: String,
        parentId: String?,
        author: String,
        authorId: String = "user-$author",
    ) = WatchStoreComment(
        id = id,
        content = id,
        parentCommentId = parentId,
        createdAt = "",
        authorId = authorId,
        authorUsername = author,
        authorAvatarUrl = "/avatars/$author.webp",
    )
}
