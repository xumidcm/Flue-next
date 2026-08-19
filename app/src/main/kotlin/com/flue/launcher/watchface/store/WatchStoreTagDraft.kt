package com.flue.launcher.watchface.store

internal data class WatchStoreTagEditResult(
    val tags: List<String>,
    val editingText: String,
)

internal fun addWatchStoreTag(tags: List<String>, rawInput: String): List<String> {
    val candidate = rawInput.trim()
    if (candidate.isEmpty()) return tags
    if (tags.any { it.equals(candidate, ignoreCase = true) }) return tags
    return tags + candidate
}

internal fun beginEditWatchStoreTag(
    tags: List<String>,
    index: Int,
): WatchStoreTagEditResult {
    val editing = tags.getOrNull(index)
        ?: return WatchStoreTagEditResult(tags = tags, editingText = "")
    return WatchStoreTagEditResult(
        tags = tags.filterIndexed { candidateIndex, _ -> candidateIndex != index },
        editingText = editing,
    )
}

internal fun removeWatchStoreTag(tags: List<String>, index: Int): List<String> =
    if (index in tags.indices) {
        tags.filterIndexed { candidateIndex, _ -> candidateIndex != index }
    } else {
        tags
    }

internal fun normalizeWatchStoreTags(tags: List<String>): List<String> =
    tags.fold(emptyList()) { normalized, raw -> addWatchStoreTag(normalized, raw) }
