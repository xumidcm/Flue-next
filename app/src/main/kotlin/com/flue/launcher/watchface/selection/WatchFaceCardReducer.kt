package com.flue.launcher.watchface.selection

import com.flue.launcher.watchface.BUILT_IN_WATCHFACE_ID

data class WatchFaceCardItem(
    val stableId: String,
    val isStore: Boolean = false,
)

data class WatchFaceCardState(
    val selectedFaceId: String,
    val focusedStableId: String,
    val shouldClose: Boolean = false,
)

data class WatchFaceCardCapabilities(
    val canEdit: Boolean,
    val canDelete: Boolean,
)

fun watchFaceCardCapabilities(
    supportsSettings: Boolean,
    isBuiltIn: Boolean,
    isJbWatch: Boolean,
    isWatchmaker: Boolean,
): WatchFaceCardCapabilities = WatchFaceCardCapabilities(
    canEdit = supportsSettings,
    canDelete = !isBuiltIn && (isJbWatch || isWatchmaker),
)

object WatchFaceCardReducer {
    const val STORE_ID = "flue:watch-face-store"

    fun items(faceIds: List<String>): List<WatchFaceCardItem> = buildList {
        faceIds.distinct().forEach { add(WatchFaceCardItem(stableId = it)) }
        add(WatchFaceCardItem(stableId = STORE_ID, isStore = true))
    }

    fun initialPage(items: List<WatchFaceCardItem>, selectedFaceId: String): Int =
        items.indexOfFirst { it.stableId == selectedFaceId }.takeIf { it >= 0 } ?: 0

    fun focus(state: WatchFaceCardState, stableId: String): WatchFaceCardState =
        state.copy(focusedStableId = stableId, shouldClose = false)

    fun activateCentered(state: WatchFaceCardState): WatchFaceCardState =
        if (state.focusedStableId == STORE_ID) {
            state
        } else {
            state.copy(selectedFaceId = state.focusedStableId, shouldClose = true)
        }

    fun afterDelete(
        state: WatchFaceCardState,
        deletedStableId: String,
        previousItems: List<WatchFaceCardItem>,
        remainingFaceIds: List<String>,
    ): WatchFaceCardState {
        val remainingItems = items(remainingFaceIds)
        val remainingFaces = remainingItems.filterNot { it.isStore }
        val previousFaces = previousItems.filterNot { it.isStore }
        val deletedIndex = previousFaces.indexOfFirst { it.stableId == deletedStableId }
            .coerceAtLeast(0)
        val nearestIndex = deletedIndex.coerceAtMost(remainingFaces.lastIndex)
        val nearest = remainingFaces.getOrNull(nearestIndex)?.stableId
            ?: remainingFaces.firstOrNull()?.stableId
            ?: STORE_ID
        return WatchFaceCardState(
            selectedFaceId = if (state.selectedFaceId == deletedStableId) {
                BUILT_IN_WATCHFACE_ID
            } else {
                state.selectedFaceId
            },
            focusedStableId = nearest,
        )
    }
}
