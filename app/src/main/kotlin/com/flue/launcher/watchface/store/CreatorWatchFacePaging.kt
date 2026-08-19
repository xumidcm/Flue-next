package com.flue.launcher.watchface.store

/** H5: returns the next creator-watch-face page, or null after the final page. */
internal fun nextCreatorWatchFacePage(pagination: WatchStorePagination?): Int? =
    pagination?.takeIf(WatchStorePagination::hasNextPage)?.page?.plus(1)

/**
 * Appends one creator page while keeping server order and replacing duplicate ids.
 * A repeated id can occur when a new face is published between page requests.
 */
internal fun appendCreatorWatchFacePage(
    current: List<WatchStoreWatchFace>,
    incoming: List<WatchStoreWatchFace>,
): List<WatchStoreWatchFace> {
    if (incoming.isEmpty()) return current
    val incomingById = incoming.associateBy(WatchStoreWatchFace::id)
    return buildList(current.size + incoming.size) {
        current.forEach { face ->
            add(incomingById[face.id] ?: face)
        }
        val existingIds = current.mapTo(HashSet(), WatchStoreWatchFace::id)
        incoming.filterTo(this) { it.id !in existingIds }
    }
}
