package com.flue.launcher.ui.store

import com.flue.launcher.watchface.store.WatchStoreComment
import com.flue.launcher.watchface.store.WatchStoreFeatureFlags

internal data class WatchStoreSocialDetailVisibility(
    val showComments: Boolean,
    val allowCommentInput: Boolean,
    val showRating: Boolean,
    val showFavorite: Boolean,
    val showReport: Boolean,
)

internal fun watchStoreSocialDetailVisibility(
    flags: WatchStoreFeatureFlags,
    signedIn: Boolean,
): WatchStoreSocialDetailVisibility {
    val social = flags.socialEnabled
    return WatchStoreSocialDetailVisibility(
        showComments = social,
        allowCommentInput = social && flags.commentsEnabled && signedIn,
        showRating = social && flags.reviewsEnabled && signedIn,
        showFavorite = social && signedIn,
        showReport = social && signedIn,
    )
}

internal data class WatchStoreCommentThread(
    val root: WatchStoreComment,
    val replies: List<WatchStoreComment>,
)

/** Groups the service's one-level replies without dropping replies whose parent disappeared. */
internal fun threadWatchStoreComments(
    comments: List<WatchStoreComment>,
): List<WatchStoreCommentThread> {
    if (comments.isEmpty()) return emptyList()
    val rootsById = comments
        .filter { it.parentCommentId == null }
        .associateBy(WatchStoreComment::id)
    val repliesByRoot = comments
        .filter { it.parentCommentId in rootsById }
        .groupBy(WatchStoreComment::parentCommentId)
    return comments.mapNotNull { comment ->
        when {
            comment.parentCommentId == null -> WatchStoreCommentThread(
                root = comment,
                replies = repliesByRoot[comment.id].orEmpty(),
            )
            comment.parentCommentId !in rootsById -> WatchStoreCommentThread(comment, emptyList())
            else -> null
        }
    }
}

internal fun isOwnWatchStoreComment(
    comment: WatchStoreComment,
    signedInUsername: String?,
): Boolean = signedInUsername != null && comment.authorUsername == signedInUsername

internal fun favoriteCountAfterToggle(
    current: Long,
    wasFavorite: Boolean,
    isFavorite: Boolean,
): Long = when {
    wasFavorite == isFavorite -> current.coerceAtLeast(0)
    isFavorite -> current.coerceAtLeast(0) + 1
    else -> (current - 1).coerceAtLeast(0)
}
